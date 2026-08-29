package com.astraupscale.app;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Range;

/**
 * Cihazin video kodlayicilarini yoklar.
 *
 * <p>Fotografta 512K'ya kadar cikabiliyoruz cunku cikisi biz yaziyoruz.
 * Videoda ise kareyi bir donanim kodlayicisi sikistirir ve <b>onun</b> bir
 * tavani vardir: cogu telefon 4K'da, iyileri 8K'da durur. 16K'yi hicbir
 * telefon kodlayamaz.
 *
 * <p>Bu yuzden burada tahmin yurutulmez, kodlayiciya sorulur. Sonuc uc
 * seyi soyler: istenen boyut kodlanabiliyor mu, kodlanamiyorsa bu cihazin
 * ayni en/boy oraninda cikabildigi en buyuk boyut nedir, ve o boyutta hangi
 * bit hizi makuldur. Kodlanamayan cozunurlukler kullanicidan gizlenmez —
 * kare dizisi olarak yazilirlar ve kullaniciya nedeni soylenir.
 */
final class VideoCodecs {

    /**
     * Aday kodlayicilar, tercih sirasiyla.
     *
     * <p>HEVC once gelir: ayni gorsel kalite icin H.264'ten yaklasik yari
     * bit hizi ister ve 4K uzeri boyutlari cogu yongada yalnizca o
     * destekler. AV1 ve VP9 listede yok cunku MP4 kabinde tasinmalari her
     * Android surumunde guvenilir degil.
     */
    private static final String[] MIMES = {
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            MediaFormat.MIMETYPE_VIDEO_AVC,
    };

    /** Piksel basina bit butcesi: HEVC ayni kaliteyi daha az bitle tasir. */
    private static float bitsPerPixel(String mime) {
        return MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime) ? 0.055f : 0.095f;
    }

    static final class Choice {
        String mime;
        String encoderName;
        /** Gercekten kodlanacak kare boyutu (kodlayicinin hizalamasina yuvarlanmis). */
        int width, height;
        /** Bu cihazin ayni oranda cikabildigi en buyuk boyut. */
        int maxWidth, maxHeight;
        /** Istenen boyuta ulasilabildi mi? */
        boolean fits;
        int bitrate;
        int frameRate;

        String label() {
            return MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime) ? "H.265 / HEVC" : "H.264 / AVC";
        }
    }

    private VideoCodecs() { }

    /**
     * Istenen boyut icin en iyi kodlayiciyi secer.
     *
     * @param qualityScale kullanicinin kalite ayari; 1.0 varsayilan bit hizi
     * @return secim; hicbir kodlayici bulunamazsa null
     */
    static Choice choose(int wantW, int wantH, int fps, float qualityScale) {
        Choice best = null;
        for (String mime : MIMES) {
            Choice c = bestFor(mime, wantW, wantH, fps, qualityScale);
            if (c == null) continue;
            if (c.fits) return c;                       // tam boyut: aramaya gerek yok
            if (best == null || area(c) > area(best)) best = c;
        }
        return best;
    }

    /** Bu cihazin herhangi bir kodlayiciyla cikabildigi en uzun kenar. */
    static int maxLongEdge() {
        int max = 0;
        for (String mime : MIMES) {
            for (MediaCodecInfo info : encoders(mime)) {
                MediaCodecInfo.VideoCapabilities vc = videoCaps(info, mime);
                if (vc == null) continue;
                try {
                    Range<Integer> w = vc.getSupportedWidths();
                    Range<Integer> h = vc.getSupportedHeights();
                    max = Math.max(max, Math.max(w.getUpper(), h.getUpper()));
                } catch (Throwable ignored) {
                }
            }
        }
        return max;
    }

    private static long area(Choice c) {
        return (long) c.width * c.height;
    }

    private static Choice bestFor(String mime, int wantW, int wantH, int fps, float qualityScale) {
        Choice best = null;
        for (MediaCodecInfo info : encoders(mime)) {
            MediaCodecInfo.VideoCapabilities vc = videoCaps(info, mime);
            if (vc == null) continue;

            int wa = Math.max(1, vc.getWidthAlignment());
            int ha = Math.max(1, vc.getHeightAlignment());

            int[] size = largestSupported(vc, wantW, wantH, wa, ha, fps);
            if (size == null) continue;

            Choice c = new Choice();
            c.mime = mime;
            c.encoderName = info.getName();
            c.width = size[0];
            c.height = size[1];
            c.maxWidth = size[0];
            c.maxHeight = size[1];
            c.fits = size[0] >= align(wantW, wa) && size[1] >= align(wantH, ha);
            c.frameRate = supportedRate(vc, size[0], size[1], fps);
            c.bitrate = bitrate(vc, mime, size[0], size[1], c.frameRate, qualityScale);
            if (best == null || area(c) > area(best)) best = c;
        }
        return best;
    }

    /**
     * Istenen orani koruyarak desteklenen en buyuk boyutu bulur.
     *
     * <p>Kodlayicinin sinirlari dikdortgen degildir: genislik tek basina,
     * yukseklik tek basina ve ikisinin carpimi (makroblok butcesi) ayri ayri
     * sinirlidir. Bu yuzden formulle hesaplanmaz, kucultulerek sorulur.
     */
    private static int[] largestSupported(MediaCodecInfo.VideoCapabilities vc,
                                          int wantW, int wantH, int wa, int ha, int fps) {
        for (double s = 1.0; s > 0.04; s -= 0.02) {
            int w = align((int) Math.round(wantW * s), wa);
            int h = align((int) Math.round(wantH * s), ha);
            if (w < wa || h < ha) continue;
            if (isSupported(vc, w, h)) return new int[]{w, h};
        }
        return null;
    }

    private static boolean isSupported(MediaCodecInfo.VideoCapabilities vc, int w, int h) {
        try {
            return vc.isSizeSupported(w, h);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Istenen kare hizi bu boyutta desteklenmiyorsa desteklenen en yakini. */
    private static int supportedRate(MediaCodecInfo.VideoCapabilities vc, int w, int h, int fps) {
        int wanted = Math.max(1, fps);
        try {
            if (vc.areSizeAndRateSupported(w, h, wanted)) return wanted;
            Range<Double> range = vc.getSupportedFrameRatesFor(w, h);
            int clamped = (int) Math.floor(range.getUpper());
            return Math.max(1, Math.min(wanted, clamped));
        } catch (Throwable t) {
            return wanted;
        }
    }

    private static int bitrate(MediaCodecInfo.VideoCapabilities vc, String mime,
                               int w, int h, int fps, float qualityScale) {
        double bits = (double) w * h * Math.max(1, fps) * bitsPerPixel(mime)
                * Math.max(0.25f, qualityScale);
        long value = (long) bits;
        try {
            Range<Integer> range = vc.getBitrateRange();
            value = Math.max(range.getLower(), Math.min(range.getUpper(), value));
        } catch (Throwable ignored) {
        }
        return (int) Math.max(100_000L, Math.min(Integer.MAX_VALUE, value));
    }

    private static int align(int value, int alignment) {
        return (value / alignment) * alignment;
    }

    private static java.util.List<MediaCodecInfo> encoders(String mime) {
        java.util.List<MediaCodecInfo> out = new java.util.ArrayList<>(2);
        try {
            MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    .getCodecInfos();
            for (MediaCodecInfo info : infos) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mime)) {
                        out.add(info);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // Donanim kodlayicilari once denensin: yazilim kodlayicisi 4K'da
        // gercek zamanin onda biri hizinda calisir.
        java.util.Collections.sort(out, new java.util.Comparator<MediaCodecInfo>() {
            @Override public int compare(MediaCodecInfo a, MediaCodecInfo b) {
                return Integer.compare(softwareRank(a), softwareRank(b));
            }
        });
        return out;
    }

    private static int softwareRank(MediaCodecInfo info) {
        String n = info.getName().toLowerCase(java.util.Locale.US);
        boolean software = n.startsWith("omx.google.") || n.startsWith("c2.android.")
                || n.contains(".sw.");
        return software ? 1 : 0;
    }

    private static MediaCodecInfo.VideoCapabilities videoCaps(MediaCodecInfo info, String mime) {
        try {
            return info.getCapabilitiesForType(mime).getVideoCapabilities();
        } catch (Throwable t) {
            return null;
        }
    }
}
