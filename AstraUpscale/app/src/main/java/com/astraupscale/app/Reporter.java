package com.astraupscale.app;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Uygulamadan cikan bildirimleri hazirlar ve kuyruga koyar.
 *
 * Iki hedef vardir: kullanici istekleri ve digerleri (is sonuclari, hatalar,
 * acilis kayitlari). Hicbir durumda goruntu verisi gonderilmez; yalnizca
 * uygulama ve cihaz bilgileriyle isin ozeti tasinir.
 */
public final class Reporter {

    public static final String TARGET_FEEDBACK = "feedback";
    public static final String TARGET_EVENT = "event";

    private Reporter() { }

    /** Her bildirime eklenen ortak baglam. */
    private static JSONObject context(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("app", BuildInfo.versionName(ctx) + " (" + BuildInfo.versionCode(ctx) + ")");
            o.put("android", "API " + Build.VERSION.SDK_INT + " / " + Build.VERSION.RELEASE);
            o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            o.put("locale", Locale.getDefault().toLanguageTag());
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** Kullanicinin yazdigi istek ya da geri bildirim. */
    public static void feedback(Context ctx, String kind, String message, String contact) {
        try {
            JSONObject o = context(ctx);
            o.put("kind", kind);
            o.put("message", message);
            if (contact != null && contact.trim().length() > 0) o.put("contact", contact.trim());
            DeviceProfile d = DeviceProfile.scan(ctx);
            o.put("hardware", d.socModel + " · " + d.cores + " cekirdek · "
                    + String.format(Locale.US, "%.1f GB", d.totalRamBytes / 1073741824.0));
            ReportQueue.add(ctx, TARGET_FEEDBACK, o);
        } catch (Throwable ignored) {
        }
        ReportSender.kick(ctx);
    }

    /** Uygulamanin kendi olaylari: is sonuclari, hatalar, acilis. */
    public static void event(Context ctx, String name, JSONObject extra) {
        try {
            JSONObject o = context(ctx);
            o.put("event", name);
            if (extra != null) o.put("detail", extra);
            ReportQueue.add(ctx, TARGET_EVENT, o);
        } catch (Throwable ignored) {
        }
        ReportSender.kick(ctx);
    }

    /** Tamamlanan bir buyutme isinin ozeti. */
    public static void jobFinished(Context ctx, UpscaleJob job) {
        try {
            JSONObject d = new JSONObject();
            d.put("preset", job.preset.label);
            d.put("target", job.targetWidth + "x" + job.targetHeight);
            d.put("model", job.usedModel == null ? "?" : job.usedModel.label);
            d.put("passes", job.usedStages);
            d.put("denoise", job.usedDenoise);
            d.put("gpu", job.usedGpu);
            d.put("format", job.jpeg ? "JPEG " + job.quality : "PNG");
            d.put("seconds", job.elapsedMillis / 1000.0);
            d.put("megabytes", job.outputBytes / 1048576.0);
            event(ctx, job.error != null ? "job_failed" : (job.cancelled ? "job_cancelled" : "job_done"), d);
            if (job.error != null) {
                JSONObject e = new JSONObject();
                e.put("message", job.error);
                event(ctx, "error", e);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Tamamlanan bir video buyutme isinin ozeti. */
    public static void videoFinished(Context ctx, VideoJob job) {
        try {
            JSONObject d = new JSONObject();
            d.put("preset", job.preset.label);
            d.put("source", job.sourceWidth + "x" + job.sourceHeight);
            d.put("target", job.outWidth + "x" + job.outHeight);
            d.put("model", job.usedModel == null ? "?" : job.usedModel.label);
            d.put("passes", job.usedStages);
            d.put("denoise", job.usedDenoise);
            d.put("gpu", job.usedGpu);
            d.put("output", job.frameSequence ? "sequence" : "mp4");
            d.put("encoder", job.encoderLabel);
            d.put("frames", job.framesDone);
            d.put("fps", Math.round(job.framesPerSecond * 100) / 100.0);
            d.put("seconds", job.elapsedMillis / 1000.0);
            d.put("megabytes", job.outputBytes / 1048576.0);
            d.put("audio", job.audioCopied);
            event(ctx, job.error != null ? "video_failed"
                    : (job.cancelled ? "video_cancelled" : "video_done"), d);
            if (job.error != null) {
                JSONObject e = new JSONObject();
                e.put("message", job.error);
                event(ctx, "error", e);
            }
        } catch (Throwable ignored) {
        }
    }
}
