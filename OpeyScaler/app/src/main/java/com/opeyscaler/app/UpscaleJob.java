package com.opeyscaler.app;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.opeyscaler.engine.Preset;

/**
 * Calisan buyutme isinin paylasilan durumu. Servis gunceller, ekran dinler;
 * boylece ekran donse veya arka plana alinsa da is devam eder.
 */
public final class UpscaleJob {

    public interface Listener {
        void onJobChanged(UpscaleJob job);
    }

    private static volatile UpscaleJob current;

    public final Uri sourceUri;
    public final Preset preset;
    public final int targetWidth, targetHeight;
    public final SrModel model;
    /** Kullanicinin sectigi sinir agi asama sayisi (1 veya 2). */
    public final int stages;
    /** Kaynaktaki gurultu buyutmeden once temizlensin mi? */
    public final boolean denoise;
    /** Cihazi zorlama seviyesinden turetilen is parcacigi sayisi ve doseme boyutu. */
    public final int threads;
    public final int tileSize;
    public final long breatherMillis;
    public final boolean jpeg;
    public final int quality;
    public final float sharpen;

    public volatile float progress;
    public volatile String stage = "Hazirlaniyor";
    public volatile boolean finished;
    public volatile boolean cancelled;
    public volatile String error;

    public volatile Uri outputUri;
    public volatile String outputName;
    public volatile long outputBytes;
    public volatile long elapsedMillis;
    public volatile int outWidth, outHeight;
    public volatile boolean usedGpu;
    public volatile SrModel usedModel;
    public volatile int usedStages = 1;
    public volatile boolean usedDenoise;
    public volatile int[] preview;
    public volatile int previewWidth, previewHeight;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Listener listener;

    public UpscaleJob(Uri sourceUri, Preset preset, int targetWidth, int targetHeight,
                      SrModel model, int stages, boolean denoise, int threads, int tileSize,
                      long breatherMillis, boolean jpeg, int quality, float sharpen) {
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
        this.jpeg = jpeg;
        this.quality = quality;
        this.sharpen = sharpen;
    }

    public static UpscaleJob current() { return current; }

    public static void setCurrent(UpscaleJob job) { current = job; }

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
                if (cur != null) cur.onJobChanged(UpscaleJob.this);
            }
        });
    }

    public void cancel() {
        cancelled = true;
    }
}
