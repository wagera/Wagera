package com.astraupscale.engine;

import java.nio.ByteBuffer;

/**
 * YUV 4:2:0 duzlemleri ile RGB arasindaki cevrim.
 *
 * <p>Video kod cozuculeri ve kodlayicilari goruntuyu RGB olarak degil, uc
 * ayri duzlem halinde (parlaklik + iki renk farki) verir; renk duzlemleri
 * yatayda ve dikeyde yariya indirilmistir. Motorun geri kalani RGB satirlari
 * uzerinde calistigi icin giris ve cikista bu cevrim yapilmak zorundadir.
 *
 * <p>Duzlemler {@link Plane} ile tarif edilir: her yonga renk duzlemlerini
 * farkli yerlestirir (I420'de renk baytlari yan yana, NV12'de ic ice
 * gecmis) ve satir uzunlugu goruntu genisliginden buyuk olabilir. Satir ve
 * piksel adimlarini disaridan almak, iki bicim icin ayri kod yazmak yerine
 * tek bir dongu yazmayi mumkun kilar.
 *
 * <p>Renk uzayi <b>tahmin edilmez</b>: kaynagin bildirdigi standart varsa o
 * kullanilir, yoksa cozunurluge gore secilir (HD ve uzeri BT.709). Yanlis
 * katsayi secmek goruntuyu bozmaz ama tenleri ve doygun renkleri gorunur
 * bicimde kaydirir; ayni katsayi hem okurken hem yazarken kullanildigi icin
 * tahmin yanlis olsa bile cevrim kendi icinde tutarli kalir.
 */
public final class Yuv {

    /** Bir renk/parlaklik duzleminin bellekteki yerlesimi. */
    public static final class Plane {
        public final ByteBuffer buffer;
        public final int offset;
        public final int rowStride;
        public final int pixelStride;

        public Plane(ByteBuffer buffer, int offset, int rowStride, int pixelStride) {
            this.buffer = buffer;
            this.offset = offset;
            this.rowStride = rowStride;
            this.pixelStride = pixelStride;
        }
    }

    /** Parlaklik katsayilari ve deger araligi. */
    public static final class Space {
        public static final Space BT601 = new Space(0.299f, 0.114f, false);
        public static final Space BT709 = new Space(0.2126f, 0.0722f, false);
        public static final Space BT2020 = new Space(0.2627f, 0.0593f, false);

        public final float kr, kb;
        public final boolean fullRange;

        public Space(float kr, float kb, boolean fullRange) {
            this.kr = kr;
            this.kb = kb;
            this.fullRange = fullRange;
        }

        public Space withRange(boolean full) {
            return full == fullRange ? this : new Space(kr, kb, full);
        }

        /** Cozunurluge gore makul varsayilan: HD ve uzeri BT.709. */
        public static Space forSize(int width, int height) {
            if (Math.min(width, height) >= 2160) return BT2020;
            return Math.min(width, height) >= 576 ? BT709 : BT601;
        }
    }

    /** 10 bitlik sabit noktali aritmetik; carpimlar {@code >> SHIFT} ile geri olceklenir. */
    private static final int SHIFT = 10;
    private static final int ONE = 1 << SHIFT;

    /**
     * Bir renk uzayi icin onceden hesaplanmis cevrim tablolari.
     *
     * <p>Piksel basina alti carpma yerine alti dizi okumasi yapilir; 8K bir
     * karede bu 130 milyon carpmadan kurtulmak demektir.
     */
    public static final class Table {
        final int[] yFrom = new int[256];      // YUV -> RGB
        final int[] rV = new int[256];
        final int[] gU = new int[256];
        final int[] gV = new int[256];
        final int[] bU = new int[256];

        final int yr, yg, yb, yOffset;         // RGB -> YUV
        final int ur, ug, ub, vr, vg, vb;

        Table(Space s) {
            final float kr = s.kr, kb = s.kb, kg = 1f - kr - kb;
            final float yScale = s.fullRange ? 255f : 219f;
            final float cScale = s.fullRange ? 255f : 224f;
            final int yOff = s.fullRange ? 0 : 16;

            for (int i = 0; i < 256; i++) {
                yFrom[i] = Math.round((i - yOff) * 255f / yScale * ONE);
                float c = (i - 128) * 255f / cScale;
                rV[i] = Math.round(2f * (1f - kr) * c * ONE);
                bU[i] = Math.round(2f * (1f - kb) * c * ONE);
                gV[i] = Math.round(-2f * (1f - kr) * kr / kg * c * ONE);
                gU[i] = Math.round(-2f * (1f - kb) * kb / kg * c * ONE);
            }

            yr = Math.round(kr * yScale / 255f * ONE);
            yg = Math.round(kg * yScale / 255f * ONE);
            yb = Math.round(kb * yScale / 255f * ONE);
            yOffset = yOff << SHIFT;

            float cu = cScale / 255f / (2f * (1f - kb));
            ur = Math.round(-kr * cu * ONE);
            ug = Math.round(-kg * cu * ONE);
            ub = Math.round((1f - kb) * cu * ONE);

            float cv = cScale / 255f / (2f * (1f - kr));
            vr = Math.round((1f - kr) * cv * ONE);
            vg = Math.round(-kg * cv * ONE);
            vb = Math.round(-kb * cv * ONE);
        }
    }

    public static Table table(Space space) {
        return new Table(space);
    }

    private Yuv() { }

    /**
     * Bir YUV 4:2:0 karesini duz RGB tamponuna cevirir.
     *
     * @param out uzunlugu en az {@code width * height * 3} olan tampon
     */
    public static void toRgb(final Plane yP, final Plane uP, final Plane vP,
                             final int width, final int height, final byte[] out,
                             final Table t, ThreadPool pool) {
        final ByteBuffer yb = yP.buffer, ub = uP.buffer, vb = vP.buffer;
        pool.forRange(height, new ThreadPool.RangeTask() {
            @Override public void run(int from, int to) {
                for (int y = from; y < to; y++) {
                    int yRow = yP.offset + y * yP.rowStride;
                    int cRow = (y >> 1);
                    int uRow = uP.offset + cRow * uP.rowStride;
                    int vRow = vP.offset + cRow * vP.rowStride;
                    int o = y * width * 3;
                    for (int x = 0; x < width; x++) {
                        int yy = t.yFrom[yb.get(yRow + x * yP.pixelStride) & 0xFF];
                        int cx = x >> 1;
                        int u = ub.get(uRow + cx * uP.pixelStride) & 0xFF;
                        int v = vb.get(vRow + cx * vP.pixelStride) & 0xFF;
                        out[o++] = clamp(yy + t.rV[v]);
                        out[o++] = clamp(yy + t.gU[u] + t.gV[v]);
                        out[o++] = clamp(yy + t.bU[u]);
                    }
                }
            }
        });
    }

    /** Bir RGB satirini parlaklik duzlemine yazar. */
    static void luminanceRow(byte[] rgb, int width, Plane y, int row, Table t) {
        final ByteBuffer buf = y.buffer;
        int base = y.offset + row * y.rowStride;
        int i = 0;
        for (int x = 0; x < width; x++) {
            int r = rgb[i] & 0xFF, g = rgb[i + 1] & 0xFF, b = rgb[i + 2] & 0xFF;
            i += 3;
            int v = (t.yr * r + t.yg * g + t.yb * b + t.yOffset + (ONE >> 1)) >> SHIFT;
            buf.put(base + x * y.pixelStride, (byte) (v < 0 ? 0 : Math.min(v, 255)));
        }
    }

    /**
     * Iki RGB satirindan bir renk satiri yazar.
     *
     * <p>Renk duzlemi yatayda ve dikeyde yariya indigi icin her cikis
     * ornegi 2x2'lik bir blogun ortalamasidir. Nokta ornekleme (tek pikseli
     * secmek) daha ucuzdur ama ince renkli desenlerde titreme yapar.
     */
    static void chromaRow(byte[] top, byte[] bottom, int width, Plane u, Plane v, int row,
                          Table t) {
        final ByteBuffer ub = u.buffer, vb = v.buffer;
        int uBase = u.offset + row * u.rowStride;
        int vBase = v.offset + row * v.rowStride;
        int cw = (width + 1) >> 1;
        for (int cx = 0; cx < cw; cx++) {
            int x0 = cx << 1;
            int x1 = Math.min(x0 + 1, width - 1);
            int a = x0 * 3, b = x1 * 3;
            int r = (top[a] & 0xFF) + (top[b] & 0xFF) + (bottom[a] & 0xFF) + (bottom[b] & 0xFF);
            int g = (top[a + 1] & 0xFF) + (top[b + 1] & 0xFF)
                    + (bottom[a + 1] & 0xFF) + (bottom[b + 1] & 0xFF);
            int bl = (top[a + 2] & 0xFF) + (top[b + 2] & 0xFF)
                    + (bottom[a + 2] & 0xFF) + (bottom[b + 2] & 0xFF);
            r >>= 2; g >>= 2; bl >>= 2;
            int uu = ((t.ur * r + t.ug * g + t.ub * bl) >> SHIFT) + 128;
            int vv = ((t.vr * r + t.vg * g + t.vb * bl) >> SHIFT) + 128;
            ub.put(uBase + cx * u.pixelStride, (byte) (uu < 0 ? 0 : Math.min(uu, 255)));
            vb.put(vBase + cx * v.pixelStride, (byte) (vv < 0 ? 0 : Math.min(vv, 255)));
        }
    }

    private static byte clamp(int fixed) {
        int v = (fixed + (ONE >> 1)) >> SHIFT;
        return (byte) (v < 0 ? 0 : Math.min(v, 255));
    }
}
