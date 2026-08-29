package com.astraupscale.app;

import android.content.Context;
import android.net.Uri;

import com.astraupscale.engine.ImageWriter;
import com.astraupscale.engine.JpegWriter;
import com.astraupscale.engine.PngWriter;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Kareleri tek tek goruntu dosyasi olarak yazar.
 *
 * <p>Bu, 16K'nin cikis yoludur. Hicbir telefonun donanim kodlayicisi
 * 16K bir kareyi sikistiramaz — tavan iyi cihazlarda 8K'dir. Kullaniciya
 * "olmaz" demek yerine cikisi kabsiz veriyoruz: numaralanmis kareler.
 * Bunlar bilgisayarda tek komutla videoya cevrilebilir ve renk kaybi
 * olmadan renk duzeltmeye girer; sinemada kullanilan bicim zaten budur.
 *
 * <p>Klasor adi kare hizini tasir ({@code ..._30fps}); kareleri birlestiren
 * kisinin bilmesi gereken tek sey odur ve ayri bir kunye dosyasi yazmadan
 * kaybolmayacagi tek yer dosya adidir.
 */
final class SequenceSink implements FrameSink {

    private final Context ctx;
    private final VideoJob job;
    private final String folder;
    private final boolean jpeg;
    private final int quality;

    private MediaOutput current;
    private OutputStream stream;
    private int index;
    private long bytes;
    private Uri firstFrame;

    SequenceSink(Context ctx, VideoJob job, String folder) {
        this.ctx = ctx;
        this.job = job;
        this.folder = folder;
        this.jpeg = job.sequenceJpeg;
        this.quality = job.sequenceQuality;
    }

    @Override public ImageWriter begin(long presentationTimeUs) throws IOException {
        String name = String.format(Locale.US, "frame_%06d.%s", index + 1, jpeg ? "jpg" : "png");
        current = MediaOutput.createImage(ctx, folder, name, jpeg ? "image/jpeg" : "image/png");
        stream = new BufferedOutputStream(current.openStream(ctx), 1 << 20);
        return jpeg ? new JpegWriter(stream, quality) : new PngWriter(stream, 4);
    }

    @Override public void end(long presentationTimeUs) throws IOException {
        if (stream == null) return;
        stream.flush();
        stream.close();
        stream = null;
        Uri uri = current.publish(ctx);
        if (firstFrame == null) firstFrame = uri;
        bytes += current.size(ctx);
        current = null;
        index++;
    }

    @Override public void finish() {
        job.sequenceFolder = folder;
    }

    @Override public void release() {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
            stream = null;
        }
        if (current != null) {
            current.discard(ctx);
            current = null;
        }
    }

    /** Yarim kalan iste yalnizca son (eksik) kare atilir; yazilanlar durur. */
    void discard() {
        release();
    }

    int frameCount() { return index; }

    @Override public Uri outputUri() { return firstFrame; }

    @Override public String outputName() { return folder; }

    @Override public long outputBytes() { return bytes; }
}
