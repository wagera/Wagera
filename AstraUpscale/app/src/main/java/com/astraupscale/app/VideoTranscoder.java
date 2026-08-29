package com.astraupscale.app;

import android.content.Context;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import com.astraupscale.engine.Denoiser;
import com.astraupscale.engine.ImageWriter;
import com.astraupscale.engine.PixelSource;
import com.astraupscale.engine.Progress;
import com.astraupscale.engine.RgbPixelSource;
import com.astraupscale.engine.ThreadPool;
import com.astraupscale.engine.Upscaler;
import com.astraupscale.engine.Yuv;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Videoyu kare kare buyutur.
 *
 * <p>Hattin tamami akis halindedir ve fotograf tarafiyla ayni motoru
 * kullanir: kare cozulur, istege bagli olarak gurultusu temizlenir, sinir
 * agindan gecer, Lanczos ile tam hedef boyuta oturtulur, keskinlestirilir ve
 * satir satir cikisa yazilir. Buyutulmus kare hicbir asamada butun halinde
 * bellekte durmaz — 8K bir kare RGB olarak 100 MB'dir ve saniyede otuz tane
 * uretilir.
 *
 * <p>Fotograftan ayrilan uc nokta:
 * <ul>
 *   <li><b>Model bir kez yuklenir.</b> Her kare icin yeniden yuklemek,
 *       isin kendisinden uzun surerdi.</li>
 *   <li><b>Is parcacigi havuzu paylasilir.</b> Kare basina havuz kurmak
 *       saniyede onlarca kez is parcacigi yaratmak demektir.</li>
 *   <li><b>Zaman damgalari kaynaktan tasinir.</b> Sabit kare hizi
 *       varsayilmaz; degisken kare hizli kayitlar da suresini korur.</li>
 * </ul>
 */
final class VideoTranscoder {

    private static final long TIMEOUT_US = 10_000;

    /**
     * Sinir agina verilebilecek en buyuk kaynak kare.
     *
     * <p>Bunun uzerinde model kare basina dakikalar surer; boyle bir isi
     * baslatmak kullaniciya yardim etmek degil, onu oyalamaktir. Ustunde
     * klasik yonteme dusulur ve bu kullaniciya soylenir.
     */
    private static final long MAX_NEURAL_FRAME_PIXELS = 9L * 1000 * 1000;

    /** Iki gecisli hatta ara karenin bellek tavani. */
    private static final long MAX_INTERMEDIATE_BYTES = 320L << 20;

    /** Kac karede bir arayuze onizleme uretilecegi. */
    private static final int PREVIEW_EVERY = 24;

    private final Context ctx;
    private final VideoJob job;

    private MediaExtractor videoExtractor, audioExtractor;
    private MediaFormat audioFormat;
    private MediaCodec decoder;
    private ThreadPool pool;
    private NativeSr sr, sr2;
    private FrameSink sink;

    private int srcW, srcH;
    private byte[] frameRgb, denoised, intermediate;
    private long framePtsUs;
    private boolean inputDone, outputDone;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private Yuv.Table decodeTable;
    private Yuv.Space space;

    private long framesTotal;
    private long framesDone;
    private long startedAt;

    VideoTranscoder(Context ctx, VideoJob job) {
        this.ctx = ctx;
        this.job = job;
    }

    // ------------------------------------------------------------------ akis

    void run() throws IOException {
        startedAt = System.currentTimeMillis();
        framesTotal = Math.max(1, job.framesTotal);
        job.update(0f, ctx.getString(R.string.v_stage_open));

        try {
            openSource();
            pool = new ThreadPool(Math.max(1, job.threads));

            boolean any = false;
            while (decodeNextFrame()) {
                if (job.cancelled) throw new Upscaler.CancelledException();
                if (!any) {
                    prepareOutput();
                    any = true;
                }
                processFrame();
            }
            if (!any) throw new IOException(ctx.getString(R.string.v_error_no_frames));

            job.update(0.995f, ctx.getString(R.string.v_stage_closing));
            sink.finish();
            job.outputUri = sink.outputUri();
            job.outputName = sink.outputName();
            job.outputBytes = sink.outputBytes();
            job.elapsedMillis = System.currentTimeMillis() - startedAt;
            job.framesDone = framesDone;
            job.update(1f, ctx.getString(R.string.v_stage_done));
        } catch (Throwable t) {
            discardOutput();
            if (t instanceof IOException) throw (IOException) t;
            if (t instanceof OutOfMemoryError) {
                throw new IOException(ctx.getString(R.string.v_error_memory));
            }
            throw new IOException(describe(t));
        } finally {
            release();
        }
    }

    private void openSource() throws IOException {
        videoExtractor = new MediaExtractor();
        videoExtractor.setDataSource(ctx, job.sourceUri, null);
        int track = selectTrack(videoExtractor, "video/");
        if (track < 0) throw new IOException(ctx.getString(R.string.v_error_no_video_track));
        videoExtractor.selectTrack(track);

        MediaFormat format = videoExtractor.getTrackFormat(track);
        String mime = format.getString(MediaFormat.KEY_MIME);

        if (job.keepAudio && !job.frameSequence) {
            audioExtractor = new MediaExtractor();
            audioExtractor.setDataSource(ctx, job.sourceUri, null);
            int at = selectTrack(audioExtractor, "audio/");
            if (at >= 0) {
                audioFormat = audioExtractor.getTrackFormat(at);
                audioExtractor.selectTrack(at);
            } else {
                audioExtractor.release();
                audioExtractor = null;
            }
        }

        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        decoder = MediaCodec.createDecoderByType(mime);
        decoder.configure(format, null, null, 0);
        decoder.start();
    }

    /**
     * Cikisi kurar: ilk kare cozuldukten sonra cagrilir, cunku hedef boyut
     * kaynagin gercek (kirpilmis) kare boyutundan hesaplanir — kapta yazan
     * boyut bazen kodlanmis boyuttur ve birkac piksel buyuk olur.
     */
    private void prepareOutput() throws IOException {
        int[] target = job.preset.targetSize(srcW, srcH);
        int fps = Math.max(1, Math.round(job.frameRate));

        if (job.frameSequence) {
            String folder = sequenceFolder(target, fps);
            sink = new SequenceSink(ctx, job, folder);
            job.outWidth = target[0];
            job.outHeight = target[1];
            job.encoderLabel = job.sequenceJpeg ? "JPEG" : "PNG";
        } else {
            VideoCodecs.Choice choice = VideoCodecs.choose(target[0], target[1], fps,
                    job.qualityScale);
            if (choice == null) throw new IOException(ctx.getString(R.string.v_error_no_encoder));
            sink = new EncoderSink(ctx, job, choice, space, audioExtractor, audioFormat,
                    videoName(choice));
            job.outWidth = choice.width;
            job.outHeight = choice.height;
            job.encoderLabel = choice.label() + "  ·  "
                    + String.format(Locale.getDefault(), "%.0f Mbps", choice.bitrate / 1e6);
        }
        openModels();
    }

    private String stamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new java.util.Date());
    }

    private String videoName(VideoCodecs.Choice choice) {
        return "AstraUpscale_" + job.preset.label + "_" + stamp() + ".mp4";
    }

    /** Kare dizisi klasoru; kare hizini adinda tasir. */
    private String sequenceFolder(int[] target, int fps) {
        return "AstraUpscale_" + job.preset.label + "_" + stamp() + "_" + fps + "fps";
    }

    /** Modelleri bir kez yukler; kareler arasinda yeniden kullanilir. */
    private void openModels() throws IOException {
        long pixels = (long) srcW * srcH;
        boolean neural = job.model.isNeural() && NativeSr.available()
                && pixels <= MAX_NEURAL_FRAME_PIXELS;

        if (!neural) {
            job.usedModel = SrModel.LANCZOS;
            job.usedStages = 1;
            if (job.model.isNeural() && pixels > MAX_NEURAL_FRAME_PIXELS) {
                job.stage = ctx.getString(R.string.v_note_source_too_big);
                job.notifyChanged();
            }
            return;
        }

        sr = NativeSr.open(ctx.getAssets(), job.model, true, job.tileSize);
        if (sr == null) {
            job.usedModel = SrModel.LANCZOS;
            job.usedStages = 1;
            job.stage = ctx.getString(R.string.v_note_model_failed);
            job.notifyChanged();
            return;
        }
        job.usedModel = job.model;
        job.usedGpu = sr.usingGpu();
        job.usedStages = 1;

        if (job.stages > 1) {
            long midBytes = (long) srcW * sr.scale() * srcH * sr.scale() * 3L;
            if (midBytes > MAX_INTERMEDIATE_BYTES) {
                // Ikinci gecis bu kaynakta bellege sigmaz; tek gecisle devam
                // edilir ve sebep kullaniciya soylenir.
                job.stage = ctx.getString(R.string.v_note_single_pass,
                        midBytes / 1073741824.0);
                job.notifyChanged();
            } else {
                sr2 = NativeSr.open(ctx.getAssets(), job.model, true, job.tileSize);
                if (sr2 != null) {
                    job.usedStages = 2;
                    intermediate = new byte[(int) midBytes];
                }
            }
        }
    }

    // ------------------------------------------------------------------ kare cozme

    /**
     * Sonraki kareyi {@link #frameRgb} icine cozer.
     *
     * @return kare varsa true, akis bittiyse false
     */
    private boolean decodeNextFrame() throws IOException {
        while (!outputDone) {
            if (job.cancelled) throw new Upscaler.CancelledException();

            if (!inputDone) {
                int index = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (index >= 0) {
                    ByteBuffer buffer = decoder.getInputBuffer(index);
                    int size = buffer == null ? -1 : videoExtractor.readSampleData(buffer, 0);
                    if (size < 0) {
                        decoder.queueInputBuffer(index, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        decoder.queueInputBuffer(index, 0, size,
                                videoExtractor.getSampleTime(), 0);
                        videoExtractor.advance();
                    }
                }
            }

            int index = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                readColorSpace(decoder.getOutputFormat());
                continue;
            }
            if (index < 0) continue;

            boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            boolean usable = info.size > 0
                    && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
            if (usable) {
                Image image = decoder.getOutputImage(index);
                if (image != null) {
                    readFrame(image);
                    framePtsUs = info.presentationTimeUs;
                    decoder.releaseOutputBuffer(index, false);
                    if (end) outputDone = true;
                    return true;
                }
            }
            decoder.releaseOutputBuffer(index, false);
            if (end) {
                outputDone = true;
                return false;
            }
        }
        return false;
    }

    /** Kod cozucunun bildirdigi renk uzayi; bildirmiyorsa cozunurluge gore. */
    private void readColorSpace(MediaFormat format) {
        if (space != null) return;
        Yuv.Space s = null;
        try {
            if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                switch (format.getInteger(MediaFormat.KEY_COLOR_STANDARD)) {
                    case MediaFormat.COLOR_STANDARD_BT709: s = Yuv.Space.BT709; break;
                    case MediaFormat.COLOR_STANDARD_BT601_PAL:
                    case MediaFormat.COLOR_STANDARD_BT601_NTSC: s = Yuv.Space.BT601; break;
                    case MediaFormat.COLOR_STANDARD_BT2020: s = Yuv.Space.BT2020; break;
                    default: break;
                }
            }
            if (s != null && format.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                s = s.withRange(format.getInteger(MediaFormat.KEY_COLOR_RANGE)
                        == MediaFormat.COLOR_RANGE_FULL);
            }
        } catch (Throwable ignored) {
        }
        if (s == null) s = Yuv.Space.forSize(job.sourceWidth, job.sourceHeight);
        space = s;
        decodeTable = Yuv.table(s);
    }

    /** Cozulmus kareyi duz RGB'ye cevirir; tampon kareler arasinda yeniden kullanilir. */
    private void readFrame(Image image) throws IOException {
        Rect crop = image.getCropRect();
        int w = crop.width() & ~1;
        int h = crop.height() & ~1;
        if (w <= 0 || h <= 0) throw new IOException(ctx.getString(R.string.v_error_no_frames));

        if (frameRgb == null) {
            srcW = w;
            srcH = h;
            frameRgb = new byte[srcW * srcH * 3];
        } else if (w != srcW || h != srcH) {
            /*
             * Kare boyutu akis ortasinda degisti. Eski boyutta okumaya
             * devam etmek tamponun disini okumak, yeni boyuta gecmek de
             * zaten baslamis kodlayiciyi bozmak olurdu. Ikisi de sessizce
             * bozuk bir cikti uretirdi; onun yerine durup sebebini
             * soyluyoruz.
             */
            throw new IOException(ctx.getString(R.string.v_error_size_changed,
                    srcW, srcH, w, h));
        }
        if (space == null) readColorSpace(decoder.getOutputFormat());

        Image.Plane[] p = image.getPlanes();
        Yuv.Plane y = new Yuv.Plane(p[0].getBuffer(),
                crop.top * p[0].getRowStride() + crop.left * p[0].getPixelStride(),
                p[0].getRowStride(), p[0].getPixelStride());
        Yuv.Plane u = new Yuv.Plane(p[1].getBuffer(),
                (crop.top / 2) * p[1].getRowStride() + (crop.left / 2) * p[1].getPixelStride(),
                p[1].getRowStride(), p[1].getPixelStride());
        Yuv.Plane v = new Yuv.Plane(p[2].getBuffer(),
                (crop.top / 2) * p[2].getRowStride() + (crop.left / 2) * p[2].getPixelStride(),
                p[2].getRowStride(), p[2].getPixelStride());
        Yuv.toRgb(y, u, v, srcW, srcH, frameRgb, decodeTable, pool);
    }

    // ------------------------------------------------------------------ kare isleme

    private void processFrame() throws IOException {
        final float base = progressAt(framePtsUs);
        final float span = Math.max(0.0005f, 1f / framesTotal);

        ImageWriter writer = sink.begin(framePtsUs);
        PixelSource source = buildSource();

        Upscaler.Options opt = new Upscaler.Options();
        opt.targetWidth = job.outWidth;
        opt.targetHeight = job.outHeight;
        opt.sharpen = job.sharpen;
        opt.threads = job.threads;
        opt.pool = pool;
        opt.buildPreview = framesDone % PREVIEW_EVERY == 0;

        Upscaler.Result result = Upscaler.run(source, opt, writer, new Progress() {
            @Override public void onProgress(float fraction, String stage) {
                job.progress = Math.min(0.995f, base + span * fraction);
                job.notifyChanged();
            }

            @Override public boolean isCancelled() {
                return job.cancelled;
            }
        });
        sink.end(framePtsUs);

        framesDone++;
        job.framesDone = framesDone;
        if (result.preview != null) {
            job.preview = result.preview;
            job.previewWidth = result.previewWidth;
            job.previewHeight = result.previewHeight;
        }
        reportPace();

        if (job.breatherMillis > 0) {
            try {
                Thread.sleep(job.breatherMillis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Bir karenin buyutme zincirini kurar. */
    private PixelSource buildSource() throws IOException {
        byte[] modelInput = frameRgb;
        PixelSource source = new RgbPixelSource(frameRgb, srcW, srcH);

        if (job.denoise) {
            if (denoised == null) denoised = new byte[srcW * srcH * 3];
            materialise(new Denoiser(source, 0.6f, pool), denoised, srcW, srcH);
            source = new RgbPixelSource(denoised, srcW, srcH);
            modelInput = denoised;
            job.usedDenoise = true;
        }

        if (sr == null) return source;

        sr.setSource(modelInput, srcW, srcH);
        prepareGaps(sr);
        source = neuralSource(sr, srcW, srcH);

        if (sr2 != null && intermediate != null) {
            int midW = srcW * sr.scale(), midH = srcH * sr.scale();
            materialise(source, intermediate, midW, midH);
            sr2.setSource(intermediate, midW, midH);
            prepareGaps(sr2);
            source = neuralSource(sr2, midW, midH);
        }
        return source;
    }

    private NeuralPixelSource neuralSource(NativeSr engine, int w, int h) throws IOException {
        return new NeuralPixelSource(engine, w, h, engine.tileSize(),
                new NeuralPixelSource.BandListener() {
                    @Override public void onBandDone(int done, int total) { }

                    @Override public boolean isCancelled() { return job.cancelled; }
                });
    }

    /** SE modellerinde kuresel havuzlama degerleri her karede yeniden hesaplanir. */
    private void prepareGaps(NativeSr engine) throws IOException {
        for (int i = 0; i < engine.gapStages(); i++) {
            if (job.cancelled) throw new Upscaler.CancelledException();
            if (!engine.prepareStage(i)) throw new IOException(engine.lastError());
        }
    }

    /** Bir kaynagi duz RGB tamponuna yazar (bir sonraki asamanin girdisi). */
    private void materialise(PixelSource src, byte[] out, int w, int h) throws IOException {
        int[] row = new int[w];
        int i = 0;
        for (int y = 0; y < h; y++) {
            if (job.cancelled) throw new Upscaler.CancelledException();
            src.readRow(y, row);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                out[i++] = (byte) (p >> 16);
                out[i++] = (byte) (p >> 8);
                out[i++] = (byte) p;
            }
        }
    }

    // ------------------------------------------------------------------ ilerleme

    private float progressAt(long ptsUs) {
        if (job.durationUs > 0) {
            float f = ptsUs / (float) job.durationUs;
            return Math.max(0f, Math.min(0.995f, f));
        }
        return Math.min(0.995f, framesDone / (float) framesTotal);
    }

    /**
     * Olculen hizi ve kalan sureyi yazar.
     *
     * <p>Fotografta sure tahmini yoktu cunku olculecek tek bir is vardi.
     * Videoda yuzlerce ayni is var: ilk kareler bittikten sonra kalan sure
     * tahmin degil, olcumdur.
     */
    private void reportPace() {
        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed <= 0) return;
        float fps = framesDone * 1000f / elapsed;
        job.framesPerSecond = fps;
        long left = framesTotal - framesDone;
        job.remainingMillis = fps > 0.0001f && left > 0 ? (long) (left / fps * 1000f) : 0;
        job.stage = ctx.getString(R.string.v_stage_frames,
                framesDone, framesTotal, fps);
        job.notifyChanged();
    }

    // ------------------------------------------------------------------ temizlik

    private void discardOutput() {
        if (sink instanceof EncoderSink) ((EncoderSink) sink).discard();
        else if (sink instanceof SequenceSink) ((SequenceSink) sink).discard();
        else if (sink != null) sink.release();
        sink = null;
    }

    private void release() {
        if (sink != null) {
            sink.release();
            sink = null;
        }
        if (decoder != null) {
            try {
                decoder.stop();
            } catch (Throwable ignored) {
            }
            try {
                decoder.release();
            } catch (Throwable ignored) {
            }
            decoder = null;
        }
        releaseExtractor(videoExtractor);
        releaseExtractor(audioExtractor);
        videoExtractor = audioExtractor = null;
        if (sr != null) {
            sr.close();
            sr = null;
        }
        if (sr2 != null) {
            sr2.close();
            sr2 = null;
        }
        if (pool != null) {
            pool.shutdown();
            pool = null;
        }
        frameRgb = denoised = intermediate = null;
    }

    private static void releaseExtractor(MediaExtractor extractor) {
        if (extractor == null) return;
        try {
            extractor.release();
        } catch (Throwable ignored) {
        }
    }

    private static int selectTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
