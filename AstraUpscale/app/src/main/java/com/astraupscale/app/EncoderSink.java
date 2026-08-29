package com.astraupscale.app;

import android.content.Context;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.astraupscale.engine.ImageWriter;
import com.astraupscale.engine.Yuv;
import com.astraupscale.engine.YuvWriter;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Kareleri donanim kodlayicisina verip MP4 kabina yazar; ses izini de
 * yeniden kodlamadan tasir.
 *
 * <p>Kritik ayrinti: kodlayicinin giris tamponu <b>dogrudan</b> doldurulur.
 * Buyutulmus kare hicbir zaman ayri bir dizide toplanmaz — {@link YuvWriter}
 * motorun urettigi satiri aninda kodlayicinin duzlemlerine dagitir. 8K bir
 * kare RGB olarak 100 MB tutar; onu her karede ayirmak birkac saniyede
 * cihazi bellek hatasina sokardi.
 *
 * <p>Ses yeniden kodlanmaz, oldugu gibi kopyalanir: sesi tekrar sikistirmak
 * kaliteyi bosuna dusururdu. Kopyalama video ornekleriyle ic ice yapilir,
 * yoksa butun ses dosyanin sonuna yigilir ve oynaticilar ilerlemekte
 * zorlanir.
 */
final class EncoderSink implements FrameSink {

    private static final long TIMEOUT_US = 10_000;
    /** Kodlayici akisi kapatirken bu sureden fazla susarsa vazgecilir. */
    private static final long DRAIN_LIMIT_MS = 20_000;

    private final Context ctx;
    private final VideoJob job;
    private final int width, height;
    private final Yuv.Table table;

    private final MediaCodec encoder;
    private final MediaMuxer muxer;
    private final MediaOutput output;
    private ParcelFileDescriptor descriptor;

    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private int videoTrack = -1, audioTrack = -1;
    private boolean muxerStarted;
    private boolean released;

    /** Ses izi; yoksa null. */
    private final MediaExtractor audio;
    private final MediaFormat audioFormat;
    private final MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
    private ByteBuffer audioBuffer;
    private boolean audioDone;

    private int frameIndex = -1;
    private final String name;
    private long bytes;
    private Uri published;

    EncoderSink(Context ctx, VideoJob job, VideoCodecs.Choice choice, Yuv.Space space,
                MediaExtractor audio, MediaFormat audioFormat, String name) throws IOException {
        this.ctx = ctx;
        this.job = job;
        this.width = choice.width;
        this.height = choice.height;
        this.table = Yuv.table(space);
        this.audio = audio;
        this.audioFormat = audioFormat;
        this.name = name;

        MediaFormat format = MediaFormat.createVideoFormat(choice.mime, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, choice.bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, choice.frameRate);
        // Iki saniyede bir anahtar kare: ileri sarma akici kalsin, bit
        // butcesi de anahtar karelere bogulmasin.
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        // Renk uzayi kaynaktan ne okunduysa o yazilir; oynatici tahmin etmesin.
        format.setInteger(MediaFormat.KEY_COLOR_STANDARD, colorStandard(space));
        format.setInteger(MediaFormat.KEY_COLOR_RANGE, space.fullRange
                ? MediaFormat.COLOR_RANGE_FULL : MediaFormat.COLOR_RANGE_LIMITED);
        format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);

        MediaCodec codec = null;
        MediaMuxer mux = null;
        MediaOutput out = null;
        try {
            codec = MediaCodec.createByCodecName(choice.encoderName);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            out = MediaOutput.createVideo(ctx, name, "video/mp4");
            if (out.file != null) {
                mux = new MediaMuxer(out.file.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                descriptor = out.openDescriptor(ctx);
                mux = new MediaMuxer(descriptor.getFileDescriptor(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } else {
                throw new IOException("Bu Android surumunde video kaydi acilamadi");
            }
            if (job.rotation != 0) mux.setOrientationHint(job.rotation);
        } catch (Throwable t) {
            if (codec != null) {
                try {
                    codec.release();
                } catch (Throwable ignored) {
                }
            }
            if (mux != null) {
                try {
                    mux.release();
                } catch (Throwable ignored) {
                }
            }
            closeDescriptor();
            if (out != null) out.discard(ctx);
            throw t instanceof IOException ? (IOException) t
                    : new IOException("Kodlayici baslatilamadi: " + describe(t));
        }
        this.encoder = codec;
        this.muxer = mux;
        this.output = out;

        if (audio != null && audioFormat != null) {
            int max = audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                    ? audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
            audioBuffer = ByteBuffer.allocate(Math.max(64 << 10, max));
        } else {
            audioDone = true;
        }
    }

    private static int colorStandard(Yuv.Space space) {
        if (space.kr == Yuv.Space.BT2020.kr) return MediaFormat.COLOR_STANDARD_BT2020;
        if (space.kr == Yuv.Space.BT601.kr) return MediaFormat.COLOR_STANDARD_BT601_NTSC;
        return MediaFormat.COLOR_STANDARD_BT709;
    }

    // ------------------------------------------------------------------ kareler

    @Override public ImageWriter begin(long presentationTimeUs) throws IOException {
        int index;
        long deadline = System.currentTimeMillis() + DRAIN_LIMIT_MS;
        while (true) {
            drain(false);
            index = encoder.dequeueInputBuffer(TIMEOUT_US);
            if (index >= 0) break;
            if (job.cancelled) throw new com.astraupscale.engine.Upscaler.CancelledException();
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("Kodlayici giris tamponu vermedi");
            }
        }
        frameIndex = index;

        Image image = encoder.getInputImage(index);
        if (image == null) {
            throw new IOException("Kodlayici duzlem erisimi vermedi");
        }
        Image.Plane[] planes = image.getPlanes();
        Yuv.Plane y = plane(planes[0]);
        Yuv.Plane u = plane(planes[1]);
        Yuv.Plane v = plane(planes[2]);
        return new YuvWriter(y, u, v, width, height, table);
    }

    private static Yuv.Plane plane(Image.Plane p) {
        return new Yuv.Plane(p.getBuffer(), 0, p.getRowStride(), p.getPixelStride());
    }

    @Override public void end(long presentationTimeUs) throws IOException {
        if (frameIndex < 0) return;
        int size = width * height * 3 / 2;
        encoder.queueInputBuffer(frameIndex, 0, size, presentationTimeUs, 0);
        frameIndex = -1;
        drain(false);
    }

    @Override public void finish() throws IOException {
        int index;
        long deadline = System.currentTimeMillis() + DRAIN_LIMIT_MS;
        while ((index = encoder.dequeueInputBuffer(TIMEOUT_US)) < 0) {
            drain(false);
            if (System.currentTimeMillis() > deadline) break;
        }
        if (index >= 0) {
            encoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }
        drain(true);
        if (!muxerStarted) {
            // Kodlayici hicbir ornek uretmedi: kabin izi bile acilmadi.
            // Boyle bir dosyayi yayimlamak, galeriye acilmayan bir video
            // koymak olurdu.
            throw new IOException("Kodlayici hicbir kare uretmedi");
        }
        // Kalan ses: video bittikten sonra hala ornek varsa sonuna eklenir.
        writeAudioUpTo(Long.MAX_VALUE);

        if (muxerStarted) {
            muxer.stop();
            muxerStarted = false;
        }
        release();
        published = output.publish(ctx);
        bytes = output.size(ctx);
    }

    /**
     * Kodlayicinin urettigi ornekleri kaba yazar.
     *
     * @param toEndOfStream true ise akis sonu isareti gelene kadar beklenir
     */
    private void drain(boolean toEndOfStream) throws IOException {
        long deadline = System.currentTimeMillis() + DRAIN_LIMIT_MS;
        while (true) {
            int index = encoder.dequeueOutputBuffer(info, toEndOfStream ? TIMEOUT_US : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!toEndOfStream) return;
                if (System.currentTimeMillis() > deadline) return;
                continue;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                startMuxer(encoder.getOutputFormat());
                continue;
            }
            if (index < 0) continue;

            ByteBuffer buffer = encoder.getOutputBuffer(index);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                // Kod cozucu basligi kabin izini acarken zaten yazildi.
                info.size = 0;
            }
            if (info.size > 0 && buffer != null) {
                if (!muxerStarted) startMuxer(encoder.getOutputFormat());
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);
                writeAudioUpTo(info.presentationTimeUs);
                muxer.writeSampleData(videoTrack, buffer, info);
            }
            encoder.releaseOutputBuffer(index, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    private void startMuxer(MediaFormat videoFormat) {
        if (muxerStarted) return;
        videoTrack = muxer.addTrack(videoFormat);
        if (audio != null && audioFormat != null) {
            try {
                audioTrack = muxer.addTrack(audioFormat);
            } catch (Throwable t) {
                // Ses bicimi bu kaba sigmiyorsa video yine de yazilir.
                audioTrack = -1;
                audioDone = true;
            }
        }
        muxer.start();
        muxerStarted = true;
        job.audioCopied = audioTrack >= 0;
    }

    /** Video zaman damgasina kadar olan ses orneklerini yazar. */
    private void writeAudioUpTo(long limitUs) {
        if (audioDone || audioTrack < 0 || !muxerStarted || audio == null) return;
        while (true) {
            long time = audio.getSampleTime();
            if (time < 0) {
                audioDone = true;
                return;
            }
            if (time > limitUs) return;
            int size = audio.readSampleData(audioBuffer, 0);
            if (size < 0) {
                audioDone = true;
                return;
            }
            audioInfo.set(0, size, time,
                    (audio.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            audioBuffer.position(0);
            audioBuffer.limit(size);
            try {
                muxer.writeSampleData(audioTrack, audioBuffer, audioInfo);
            } catch (Throwable t) {
                audioDone = true;
                return;
            }
            if (!audio.advance()) {
                audioDone = true;
                return;
            }
        }
    }

    @Override public void release() {
        if (released) return;
        released = true;
        try {
            encoder.stop();
        } catch (Throwable ignored) {
        }
        try {
            encoder.release();
        } catch (Throwable ignored) {
        }
        try {
            muxer.release();
        } catch (Throwable ignored) {
        }
        closeDescriptor();
    }

    /** Basarisiz bir isten sonra yarim kalan dosyayi siler. */
    void discard() {
        release();
        output.discard(ctx);
    }

    private void closeDescriptor() {
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (Throwable ignored) {
            }
            descriptor = null;
        }
    }

    @Override public Uri outputUri() { return published; }

    @Override public String outputName() { return name; }

    @Override public long outputBytes() { return bytes; }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
