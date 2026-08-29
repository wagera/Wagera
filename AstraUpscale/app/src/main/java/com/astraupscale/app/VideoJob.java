package com.astraupscale.app;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.astraupscale.engine.VideoPreset;

/**
 * Calisan video buyutme isinin paylasilan durumu.
 *
 * <p>{@link UpscaleJob} ile ayni sozlesme: servis gunceller, ekran dinler.
 * Ayri bir sinif olmasinin sebebi, videonun fotografta karsiligi olmayan
 * seyler tasimasi — kare sayaci, kare hizi olcumu, ses izi, kodlayici
 * secimi. Bunlari fotograf isine sikistirmak iki isi de bulanik yapardi.
 */
public final class VideoJob {

    public interface Listener {
        void onVideoJobChanged(VideoJob job);
    }

    private static volatile VideoJob current;

    // ---------------------------------------------------------------- istek
    public final Uri sourceUri;
    public final VideoPreset preset;
    public final int targetWidth, targetHeight;
    public final SrModel model;
    public final int stages;
    public final boolean denoise;
    public final int threads;
    public final int tileSize;
    public final long breatherMillis;
    public final float sharpen;
    /** Kullanicinin kalite ayari; bit hizi carpani (0.5 .. 2.0). */
    public final float qualityScale;
    /** Kodlayici tavani asiliyorsa cikis kare dizisi olarak yazilir. */
    public final boolean frameSequence;
    /** Kare dizisinde JPEG mi PNG mi yazilacagi. */
    public final boolean sequenceJpeg;
    public final int sequenceQuality;
    /** Sesi de tasi (kare dizisinde anlamsizdir). */
    public final boolean keepAudio;
    public final int sourceWidth, sourceHeight, rotation;
    public final long durationUs;
    public final float frameRate;

    // ---------------------------------------------------------------- durum
    public volatile float progress;
    public volatile String stage = "Hazirlaniyor";
    public volatile boolean finished;
    public volatile boolean cancelled;
    public volatile String error;

    public volatile long framesDone;
    public volatile long framesTotal;
    /** Olculen isleme hizi: saniyede kac kare uretiliyor. */
    public volatile float framesPerSecond;
    public volatile long remainingMillis = -1;

    public volatile Uri outputUri;
    public volatile String outputName;
    public volatile long outputBytes;
    public volatile long elapsedMillis;
    public volatile int outWidth, outHeight;
    public volatile boolean usedGpu;
    public volatile SrModel usedModel;
    public volatile int usedStages = 1;
    public volatile boolean usedDenoise;
    public volatile boolean audioCopied;
    public volatile String encoderLabel = "";
    /** Kare dizisi modunda yazilan klasorun adi. */
    public volatile String sequenceFolder;
    public volatile int[] preview;
    public volatile int previewWidth, previewHeight;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Listener listener;

    public VideoJob(Uri sourceUri, VideoPreset preset, int targetWidth, int targetHeight,
                    SrModel model, int stages, boolean denoise, int threads, int tileSize,
                    long breatherMillis, float sharpen, float qualityScale,
                    boolean frameSequence, boolean sequenceJpeg, int sequenceQuality,
                    boolean keepAudio, int sourceWidth, int sourceHeight, int rotation,
                    long durationUs, float frameRate) {
        this.sourceUri = sourceUri;
        this.preset = preset;
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
        this.model = model;
        this.stages = stages;
        this.denoise = denoise;
        this.threads = threads;
        this.tileSize = tileSize;
        this.breatherMillis = breatherMillis;
        this.sharpen = sharpen;
        this.qualityScale = qualityScale;
        this.frameSequence = frameSequence;
        this.sequenceJpeg = sequenceJpeg;
        this.sequenceQuality = sequenceQuality;
        this.keepAudio = keepAudio;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.rotation = rotation;
        this.durationUs = durationUs;
        this.frameRate = frameRate;
    }

    public static VideoJob current() { return current; }

    public static void setCurrent(VideoJob job) { current = job; }

    public void setListener(Listener l) {
        this.listener = l;
        if (l != null) notifyChanged();
    }

    public void update(float progress, String stage) {
        this.progress = progress;
        this.stage = stage;
        notifyChanged();
    }

    public void notifyChanged() {
        final Listener l = listener;
        if (l == null) return;
        main.post(new Runnable() {
            @Override public void run() {
                Listener cur = listener;
                if (cur != null) cur.onVideoJobChanged(VideoJob.this);
            }
        });
    }

    public void cancel() {
        cancelled = true;
    }
}
