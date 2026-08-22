package com.astraupscale.engine;

/**
 * Lanczos-3 tabanli, ayrilabilir (once yatay sonra dikey) yeniden orneklendirici.
 *
 * Cikis satirlari sirayla uretilir; bellekte yalnizca birkac ara satir tutuldugu icin
 * 16K (15360 x 8640) gibi 130+ megapiksellik cikislar telefonda bile uretilebilir.
 * Tum aritmetik dogrusal isikta (linear light) yapilir.
 */
public final class Resampler {

    private static final float RADIUS = 3f;

    private final PixelSource src;
    private final int srcW, srcH, dstW, dstH;

    /** Yatay filtre: her cikis sutunu icin baslangic indeksi + agirliklar. */
    private final int[] hStart;
    private final float[] hWeights;
    private final int hTaps;

    /** Dikey filtre: her cikis satiri icin baslangic indeksi + agirliklar. */
    private final int[] vStart;
    private final float[] vWeights;
    private final int vTaps;

    /** Yatay olceklenmis kaynak satirlarinin halka tamponu (dogrusal isik). */
    private final float[][] cache;
    private final int[] cacheIndex;
    private final int cacheSize;

    private final int[] srcRow;
    private final ThreadPool pool;

    public Resampler(PixelSource src, int dstW, int dstH, ThreadPool pool) {
        this.src = src;
        this.srcW = src.width();
        this.srcH = src.height();
        this.dstW = dstW;
        this.dstH = dstH;
        this.pool = pool;

        float scaleX = dstW / (float) srcW;
        float scaleY = dstH / (float) srcH;

        this.hTaps = tapCount(scaleX);
        this.vTaps = tapCount(scaleY);
        this.hStart = new int[dstW];
        this.hWeights = new float[dstW * hTaps];
        this.vStart = new int[dstH];
        this.vWeights = new float[dstH * vTaps];
        buildWeights(srcW, dstW, hTaps, hStart, hWeights);
        buildWeights(srcH, dstH, vTaps, vStart, vWeights);

        this.cacheSize = vTaps + 2;
        this.cache = new float[cacheSize][];
        this.cacheIndex = new int[cacheSize];
        for (int i = 0; i < cacheSize; i++) {
            cache[i] = new float[dstW * 3];
            cacheIndex[i] = -1;
        }
        this.srcRow = new int[srcW];
    }

    private static int tapCount(float scale) {
        float support = scale < 1f ? RADIUS / scale : RADIUS;
        return (int) Math.ceil(support * 2) + 1;
    }

    /** Lanczos-3 cekirdegi. */
    private static float lanczos(float x) {
        if (x < 0) x = -x;
        if (x < 1e-6f) return 1f;
        if (x >= RADIUS) return 0f;
        double px = Math.PI * x;
        return (float) (Math.sin(px) * Math.sin(px / RADIUS) / (px * px / RADIUS));
    }

    private static void buildWeights(int srcN, int dstN, int taps, int[] start, float[] weights) {
        float scale = dstN / (float) srcN;
        float filterScale = scale < 1f ? 1f / scale : 1f;
        float support = RADIUS * filterScale;
        for (int i = 0; i < dstN; i++) {
            float center = (i + 0.5f) / scale - 0.5f;
            int left = (int) Math.ceil(center - support);
            start[i] = left;
            float sum = 0;
            int base = i * taps;
            for (int t = 0; t < taps; t++) {
                float w = lanczos((left + t - center) / filterScale);
                weights[base + t] = w;
                sum += w;
            }
            if (sum != 0) {
                for (int t = 0; t < taps; t++) weights[base + t] /= sum;
            }
        }
    }

    /** Kaynak satirini yatayda olcekleyip halka tamponundan dondurur. */
    private float[] horizontalRow(int y) {
        if (y < 0) y = 0;
        else if (y >= srcH) y = srcH - 1;
        int slot = y % cacheSize;
        if (cacheIndex[slot] == y) return cache[slot];

        src.readRow(y, srcRow);
        final float[] out = cache[slot];
        final int[] row = srcRow;
        final float[] toLinear = Color.TO_LINEAR;
        final int w = srcW;

        pool.forRange(dstW, new ThreadPool.RangeTask() {
            @Override public void run(int from, int to) {
                for (int i = from; i < to; i++) {
                    int base = i * hTaps;
                    int left = hStart[i];
                    float r = 0, g = 0, b = 0;
                    for (int t = 0; t < hTaps; t++) {
                        float wt = hWeights[base + t];
                        if (wt == 0) continue;
                        int sx = left + t;
                        if (sx < 0) sx = 0;
                        else if (sx >= w) sx = w - 1;
                        int p = row[sx];
                        int a = (p >>> 24);
                        int pr = (p >> 16) & 0xFF, pg = (p >> 8) & 0xFF, pb = p & 0xFF;
                        if (a != 255) {
                            // Saydam pikselleri beyaz zemin uzerine yerlestir.
                            float af = a / 255f, inv = 1f - af;
                            r += wt * (toLinear[pr] * af + inv);
                            g += wt * (toLinear[pg] * af + inv);
                            b += wt * (toLinear[pb] * af + inv);
                        } else {
                            r += wt * toLinear[pr];
                            g += wt * toLinear[pg];
                            b += wt * toLinear[pb];
                        }
                    }
                    int o = i * 3;
                    out[o] = r;
                    out[o + 1] = g;
                    out[o + 2] = b;
                }
            }
        });
        cacheIndex[slot] = y;
        return out;
    }

    /**
     * {@code y} numarali cikis satirini dogrusal isikta {@code dst} icine yazar
     * (uzunluk {@code dstW * 3}). Satirlar artan sirada istenmelidir.
     */
    public void resampleRow(int y, final float[] dst) {
        final int left = vStart[y];
        final int base = y * vTaps;
        final float[][] rows = new float[vTaps][];
        for (int t = 0; t < vTaps; t++) {
            rows[t] = vWeights[base + t] == 0 ? null : horizontalRow(left + t);
        }
        pool.forRange(dstW * 3, new ThreadPool.RangeTask() {
            @Override public void run(int from, int to) {
                for (int i = from; i < to; i++) dst[i] = 0;
                for (int t = 0; t < vTaps; t++) {
                    float w = vWeights[base + t];
                    if (w == 0 || rows[t] == null) continue;
                    float[] r = rows[t];
                    for (int i = from; i < to; i++) dst[i] += w * r[i];
                }
            }
        });
    }

    public int dstWidth() { return dstW; }

    public int dstHeight() { return dstH; }
}
