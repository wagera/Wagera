package com.astraupscale.engine;

/**
 * Kenarlari koruyan gurultu/grain temizleyici (cift tarafli filtre).
 *
 * Cok yuksek buyutmelerde kaynaktaki sensor gurultusu ve film graini de
 * buyutulur; 60 kat buyutmede tek bir gurultu pikseli ekranda avuc ici
 * buyuklugunde bir lekeye donusur. Bu yuzden buyutmeden **once** temizlenir.
 *
 * Filtre satir satir calisir: bellekte yalnizca (2*yaricap+1) kaynak satiri
 * tutulur, dolayisiyla maliyeti kaynak boyutundan bagimsizdir. Kenarlar
 * korunur cunku agirlik hem uzakliga hem de renk farkina baglidir; duz
 * alanlardaki gurultu silinirken kenarlar ve dokular yerinde kalir.
 */
public final class Denoiser implements PixelSource {

    private final PixelSource src;
    private final int width, height, radius;
    private final float[] spatial;      // uzaklik agirliklari
    private final float[] range;        // renk farki agirliklari (0..255 farki icin)
    private final int[][] ring;         // kaynak satir halkasi
    private final int[] ringRow;
    private final int ringSize;
    private final ThreadPool pool;

    /**
     * @param strength 0..1; 0 filtreyi kapatir, 1 en guclu temizlik
     */
    public Denoiser(PixelSource src, float strength, ThreadPool pool) {
        this.src = src;
        this.width = src.width();
        this.height = src.height();
        this.pool = pool;

        float s = Math.max(0.05f, Math.min(1f, strength));
        this.radius = s < 0.4f ? 2 : 3;
        this.ringSize = 2 * radius + 1;

        // Uzaklik agirligi: yaricapin yarisi kadar sigma
        float sigmaSpace = radius / 1.6f;
        this.spatial = new float[(2 * radius + 1) * (2 * radius + 1)];
        int k = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                spatial[k++] = (float) Math.exp(-(dx * dx + dy * dy) / (2.0 * sigmaSpace * sigmaSpace));
            }
        }

        /*
         * Renk farki agirligi onceden tabloya alinir. Sigma buyudukce daha
         * buyuk farklar da "gurultu" sayilir; guclu ayarda bile kenarlarin
         * kaybolmamasi icin ust sinir dusuk tutulur.
         */
        float sigmaRange = 6f + 26f * s;
        this.range = new float[256];
        for (int i = 0; i < 256; i++) {
            range[i] = (float) Math.exp(-(i * (double) i) / (2.0 * sigmaRange * sigmaRange));
        }

        this.ring = new int[ringSize][width];
        this.ringRow = new int[ringSize];
        for (int i = 0; i < ringSize; i++) ringRow[i] = -1;
    }

    @Override public int width() { return width; }

    @Override public int height() { return height; }

    /** Kaynak satirini halkaya alir (kenarlarda ilk/son satir tekrarlanir). */
    private int[] sourceRow(int y) {
        if (y < 0) y = 0;
        else if (y >= height) y = height - 1;
        int slot = y % ringSize;
        if (ringRow[slot] != y) {
            src.readRow(y, ring[slot]);
            ringRow[slot] = y;
        }
        return ring[slot];
    }

    @Override public void readRow(final int y, final int[] out) {
        final int[][] rows = new int[ringSize][];
        for (int i = 0; i < ringSize; i++) rows[i] = sourceRow(y - radius + i);
        final int[] center = rows[radius];

        pool.forRange(width, new ThreadPool.RangeTask() {
            @Override public void run(int from, int to) {
                for (int x = from; x < to; x++) {
                    final int c = center[x];
                    final int cr = (c >> 16) & 0xFF, cg = (c >> 8) & 0xFF, cb = c & 0xFF;
                    float sr = 0, sg = 0, sb = 0, wr = 0, wg = 0, wb = 0;
                    int k = 0;
                    for (int i = 0; i < ringSize; i++) {
                        final int[] row = rows[i];
                        for (int dx = -radius; dx <= radius; dx++) {
                            int sx = x + dx;
                            if (sx < 0) sx = 0;
                            else if (sx >= width) sx = width - 1;
                            final int p = row[sx];
                            final int pr = (p >> 16) & 0xFF, pg = (p >> 8) & 0xFF, pb = p & 0xFF;
                            final float sp = spatial[k++];
                            float w = sp * range[pr > cr ? pr - cr : cr - pr];
                            sr += w * pr; wr += w;
                            w = sp * range[pg > cg ? pg - cg : cg - pg];
                            sg += w * pg; wg += w;
                            w = sp * range[pb > cb ? pb - cb : cb - pb];
                            sb += w * pb; wb += w;
                        }
                    }
                    out[x] = 0xFF000000
                            | (clamp(sr / wr) << 16)
                            | (clamp(sg / wg) << 8)
                            | clamp(sb / wb);
                }
            }
        });
    }

    private static int clamp(float v) {
        int i = (int) (v + 0.5f);
        return i < 0 ? 0 : Math.min(i, 255);
    }
}
