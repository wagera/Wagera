package com.astraupscale.app;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

/**
 * Bir videonun kunyesi: boyut, sure, kare hizi, donus ve ses.
 *
 * <p>Iki kaynaktan okunur cunku ikisi de tek basina yetmez.
 * {@link MediaExtractor} izlerin gercek bicimini verir ama kare hizini cogu
 * kapta bos birakir; {@link MediaMetadataRetriever} sureyi ve kare sayisini
 * bilir ama izlerin ayrintisini vermez. Ikisinin kesisimi, kullaniciya
 * dogru sayilari gosterebilmek ve hedef boyutu dogru hesaplayabilmek icin
 * gereken her seyi verir.
 */
final class VideoInfo {

    /** Kodlanmis kare boyutu (donus uygulanmamis). */
    int width, height;
    /** Kapta yazan donus acisi: 0, 90, 180 ya da 270. */
    int rotation;
    long durationUs;
    float frameRate;
    boolean hasAudio;
    String mime = "";
    String audioMime = "";
    long sizeBytes;
    /** Ust veriden okunabildiyse toplam kare sayisi, yoksa 0. */
    long frameCount;

    /** Ekranda gorunecek genislik: 90/270 donusunde kenarlar yer degistirir. */
    int displayWidth() {
        return rotation == 90 || rotation == 270 ? height : width;
    }

    int displayHeight() {
        return rotation == 90 || rotation == 270 ? width : height;
    }

    /** Toplam kare sayisi; ust veride yoksa sure ve kare hizindan tahmin. */
    long estimatedFrames() {
        if (frameCount > 0) return frameCount;
        return Math.max(1, (long) (durationUs / 1_000_000.0 * Math.max(1f, frameRate)));
    }

    boolean valid() {
        return width > 0 && height > 0 && durationUs > 0;
    }

    /**
     * Videoyu okur; okunamazsa null doner.
     *
     * <p>Hicbir kare cozulmez: yalnizca basliklar okunur, bu yuzden secim
     * aninda cagirmak ucuzdur.
     */
    static VideoInfo probe(Context ctx, Uri uri) {
        VideoInfo info = new VideoInfo();
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(ctx, uri, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/") && info.width == 0) {
                    info.mime = mime;
                    info.width = f.getInteger(MediaFormat.KEY_WIDTH);
                    info.height = f.getInteger(MediaFormat.KEY_HEIGHT);
                    // Bazi kaplarda gorunen alan kodlanmis alandan kucuktur.
                    if (f.containsKey("crop-left") && f.containsKey("crop-right")) {
                        info.width = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
                    }
                    if (f.containsKey("crop-top") && f.containsKey("crop-bottom")) {
                        info.height = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
                    }
                    if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        try {
                            info.frameRate = f.getInteger(MediaFormat.KEY_FRAME_RATE);
                        } catch (ClassCastException e) {
                            info.frameRate = f.getFloat(MediaFormat.KEY_FRAME_RATE);
                        }
                    }
                    if (f.containsKey(MediaFormat.KEY_DURATION)) {
                        info.durationUs = f.getLong(MediaFormat.KEY_DURATION);
                    }
                    if (f.containsKey(MediaFormat.KEY_ROTATION)) {
                        info.rotation = f.getInteger(MediaFormat.KEY_ROTATION);
                    }
                } else if (mime.startsWith("audio/") && !info.hasAudio) {
                    info.hasAudio = true;
                    info.audioMime = mime;
                }
            }
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
        }
        if (info.width <= 0 || info.height <= 0) return null;

        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(ctx, uri);
            long durationMs = parseLong(mmr.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION));
            if (durationMs > 0) info.durationUs = durationMs * 1000L;
            int rot = (int) parseLong(mmr.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (rot > 0) info.rotation = rot;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.frameCount = parseLong(mmr.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT));
            }
            float captured = parseFloat(mmr.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE));
            if (info.frameRate <= 0 && captured > 0) info.frameRate = captured;
        } catch (Throwable ignored) {
        } finally {
            try {
                mmr.release();
            } catch (Throwable ignored) {
            }
        }

        if (info.frameRate <= 0 && info.frameCount > 0 && info.durationUs > 0) {
            info.frameRate = (float) (info.frameCount / (info.durationUs / 1_000_000.0));
        }
        // Hicbir yerde yazmiyorsa 30: yanlis olabilir ama sifir kesinlikle yanlistir.
        if (info.frameRate <= 0 || info.frameRate > 480) info.frameRate = 30f;

        info.sizeBytes = fileSize(ctx, uri);
        return info;
    }

    private static long fileSize(Context ctx, Uri uri) {
        android.database.Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static float parseFloat(String s) {
        try {
            return s == null ? 0 : Float.parseFloat(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** "1:23" gibi kisa sure metni. */
    static String formatDuration(long durationUs) {
        long total = Math.max(0, durationUs) / 1_000_000L;
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        if (h > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(java.util.Locale.US, "%d:%02d", m, s);
    }
}
