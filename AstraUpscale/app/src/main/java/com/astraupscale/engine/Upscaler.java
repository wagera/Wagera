package com.astraupscale.engine;

import java.io.IOException;

/**
 * Tum buyutme hattini yonetir:
 * kaynak -> Lanczos-3 yeniden ornekleme (dogrusal isikta) -> detay keskinlestirme
 * -> akis halinde JPEG/PNG kodlama.
 *
 * Cikis satir satir uretilip dogrudan diske yazildigindan bellek kullanimi
 * cikis cozunurlugunden neredeyse bagimsizdir; 16K bile birkac MB ile uretilir.
 */
public final class Upscaler {

    /** Onizleme icin uretilen kucuk kopyanin uzun kenari. */
    public static final int PREVIEW_EDGE = 720;

    /** Asiri hale (halo) olusmamasi icin keskinlestirmenin piksel basina siniri. */
    private static final float MAX_OVERSHOOT = 48f;

    public static final class Options {
        public int targetWidth;
        public int targetHeight;
        /** 0..1 arasi keskinlestirme miktari. */
        public float sharpen = 0.45f;
        /** true ise kucuk bir onizleme goruntusu de uretilir. */
        public boolean buildPreview = true;
        /** Kullanilacak is parcacigi sayisi; 0 ise cekirdek sayisi kadar. */
        public int threads = 0;
        /**
         * Hazir bir is parcacigi havuzu. Video hattinda her kare icin yeni
         * havuz kurmak saniyede onlarca kez is parcacigi yaratmak demektir;
         * verilirse havuz paylasilir ve bu kurulum bedeli odenmez. Havuz
         * cagiranin malidir: {@link #run} onu kapatmaz.
         */
        public ThreadPool pool;
        /**
         * Keskinlestirme halkasi icin ayrilabilecek azami bellek. Cok genis
         * ciktilarda (128K ve uzeri) yaricap bu butceye gore kisilir.
         */
        public long sharpenBudgetBytes = 96L << 20;
    }

    public static final class Result {
        public int width;
        public int height;
        public long elapsedMillis;
        /** ARGB onizleme (istenmisse), aksi halde null. */
        public int[] preview;
        public int previewWidth;
        public int previewHeight;
    }

    public static final class CancelledException extends IOException {
        public CancelledException() {
            super("Islem iptal edildi");
        }
    }

    private Upscaler() { }

    public static Result run(PixelSource src, Options opt, ImageWriter writer, Progress progress)
            throws IOException {
        long t0 = System.currentTimeMillis();
        final int dstW = opt.targetWidth;
        final int dstH = opt.targetHeight;
        final int srcW = src.width();
        final int srcH = src.height();
        final int w3 = dstW * 3;

        final boolean ownPool = opt.pool == null;
        ThreadPool pool = ownPool
                ? (opt.threads > 0 ? new ThreadPool(opt.threads) : ThreadPool.auto())
                : opt.pool;
        try {
            Resampler resampler = new Resampler(src, dstW, dstH, pool);
            writer.start(dstW, dstH);

            float scale = Math.max(dstW / (float) srcW, dstH / (float) srcH);
            final boolean sharpening = opt.sharpen > 0.001f;

            /*
             * Kritik nokta: buyutulmus goruntude bir kenar yaklasik "olcek" kadar piksele
             * yayilir. Bu yuzden keskinlestirme yaricapi olcekle birlikte buyumelidir;
             * sabit 1 piksellik bir unsharp 15 kat buyutmede hicbir sey yapmaz.
             */
            int wanted = sharpening ? Math.max(1, Math.min(24, Math.round(scale * 0.6f))) : 0;
            /*
             * Halka basina maliyet: ham satir (w3 bayt) + yatay kutu toplami
             * (w3 kisa tamsayi = 2*w3 bayt). Satir sayisi 2r+3 oldugundan
             * yaricap, cikis genisligi buyudukce butceye gore kisilir.
             */
            if (sharpening) {
                long perRow = (long) w3 * 3;
                long maxRows = Math.max(5, opt.sharpenBudgetBytes / Math.max(1, perRow));
                int maxRadius = (int) Math.max(1, (maxRows - 3) / 2);
                wanted = Math.min(wanted, maxRadius);
            }
            final int radius = wanted;
            final float amountLarge = opt.sharpen;
            final float amountFine = opt.sharpen * 0.5f;
            final int ringSize = 2 * radius + 3;
            final float boxNorm = 1f / ((2 * radius + 1) * (float) (2 * radius + 1));

            byte[][] ring = new byte[sharpening ? ringSize : 1][w3];
            // Kutu toplami en fazla (2*24+1)*255 = 12495; kisa tamsayi yeter.
            short[][] hbox = sharpening ? new short[ringSize][w3] : null;
            int[] acc = sharpening ? new int[w3] : null;   // dikey kosan toplam
            float[] linear = new float[w3];
            byte[] outRow = sharpening ? new byte[w3] : null;
            int produced = 0;   // uretilmis ham satir sayisi

            Result result = new Result();
            result.width = dstW;
            result.height = dstH;
            int previewW = 0, previewH = 0;
            int[] preview = null;
            int previewStep = 1;
            if (opt.buildPreview) {
                double pr = Math.min(1.0, PREVIEW_EDGE / (double) Math.max(dstW, dstH));
                previewW = Math.max(1, (int) Math.round(dstW * pr));
                previewH = Math.max(1, (int) Math.round(dstH * pr));
                preview = new int[previewW * previewH];
                previewStep = Math.max(1, dstH / previewH);
            }

            int lastPct = -1;
            for (int y = 0; y < dstH; y++) {
                if (progress.isCancelled()) throw new CancelledException();

                int readUpTo = sharpening ? Math.min(dstH - 1, y + radius + 1) : y;
                while (produced <= readUpTo) {
                    int slot = sharpening ? produced % ringSize : 0;
                    resampler.resampleRow(produced, linear);
                    toSrgb(linear, ring[slot], w3, pool);
                    if (sharpening) horizontalBox(ring[slot], hbox[slot], dstW, radius, pool);
                    produced++;
                }

                byte[] emitted;
                if (sharpening) {
                    if (y == 0) {
                        initAccumulator(acc, hbox, ringSize, dstH, radius, w3, pool);
                    } else {
                        slideAccumulator(acc, hbox, ringSize, dstH, radius, y, w3, pool);
                    }
                    sharpenRow(ring, hbox, acc, ringSize, y, dstH, dstW, boxNorm,
                            amountLarge, amountFine, outRow, pool);
                    emitted = outRow;
                } else {
                    emitted = ring[0];
                }
                writer.writeRow(emitted);

                if (preview != null && y % previewStep == 0) {
                    int py = y / previewStep;
                    if (py < previewH) samplePreview(emitted, dstW, preview, py, previewW);
                }

                int pct = (int) ((y + 1) * 100L / dstH);
                if (pct != lastPct) {
                    lastPct = pct;
                    progress.onProgress(pct / 100f, "Buyutuluyor  %" + pct);
                }
            }
            writer.finish();

            result.preview = preview;
            result.previewWidth = previewW;
            result.previewHeight = previewH;
            result.elapsedMillis = System.currentTimeMillis() - t0;
            progress.onProgress(1f, "Tamamlandi");
            return result;
        } finally {
            if (ownPool) pool.shutdown();
        }
    }

    /** Dogrusal isik degerlerini 8 bit sRGB'ye cevirir. */
    private static void toSrgb(final float[] linear, final byte[] out, int w3, ThreadPool pool) {
        pool.forRange(w3, new ThreadPool.RangeTask() {
            @Override public void run(int from, int to) {
                for (int i = from; i < to; i++) out[i] = (byte) Color.linearToSrgb(linear[i]);
            }
        });
    }

    /** Satir icinde kosan toplamla yatay kutu bulanikligi: yaricaptan bagimsiz O(genislik). */
    private static void horizontalBox(final byte[] row, final short[] out, final int dstW,
                                      final int radius, ThreadPool pool) {
        pool.forRange(3, new ThreadPool.RangeTask() {
            @Override public void run(int from, int to) {
                for (int c = from; c < to; c++) {
                    int last = (dstW - 1) * 3 + c;
                    int sum = (row[c] & 0xFF) * (radius + 1);
                    for (int x = 1; x <= radius; x++) {
                        int idx = Math.min(x, dstW - 1) * 3 + c;
                        sum += row[idx] & 0xFF;
                    }
                    for (int x = 0; x < dstW; x++) {
                        out[x * 3 + c] = (short) sum;
                        int addX = x + radius + 1;
                        int subX = x - radius;
                        int addIdx = addX <= dstW - 1 ? addX * 3 + c : last;
                        int subIdx = subX >= 0 ? subX * 3 + c : c;
                        sum += (row[addIdx] & 0xFF) - (row[subIdx] & 0xFF);
                    }
                }
            }
        });
    }

    /** Ilk satir icin dikey toplami kurar (kenarda ilk satir tekrarlanir). */
    private static void initAccumulator(final int[] acc, final short[][] hbox, final int ringSize,
                                        final int dstH, final int radius, int w3, ThreadPool pool) {
        pool.forRange(w3, new ThreadPool.RangeTask() {
            @Override public void run(int from, int to) {
                for (int i = from; i < to; i++) acc[i] = 0;
                for (int dy = -radius; dy <= radius; dy++) {
                    short[] r = hbox[clamp(dy, dstH) % ringSize];
                    for (int i = from; i < to; i++) acc[i] += r[i];
                }
            }
        });
    }

    /** Bir sonraki satira gecerken toplama giren satiri ekler, cikani cikarir. */
    private static void slideAccumulator(final int[] acc, final short[][] hbox, final int ringSize,
                                         final int dstH, final int radius, final int y, int w3,
                                         ThreadPool pool) {
        final short[] in = hbox[clamp(y + radius, dstH) % ringSize];
        final short[] out = hbox[clamp(y - radius - 1, dstH) % ringSize];
        pool.forRange(w3, new ThreadPool.RangeTask() {
            @Override public void run(int from, int to) {
                for (int i = from; i < to; i++) acc[i] += in[i] - out[i];
            }
        });
    }

    /**
     * Iki olcekli unsharp mask:
     *  - buyuk yaricap (olcekle orantili): kenarlari dikleştirir, buyutmenin yumusakligini alir
     *  - 1 piksellik ince yaricap: mikro kontrasti geri getirir
     * Hale olusmamasi icin degisim {@link #MAX_OVERSHOOT} ile sinirlanir.
     */
    private static void sharpenRow(final byte[][] ring, final short[][] hbox, final int[] acc,
                                   final int ringSize, final int y, final int dstH, final int dstW,
                                   final float boxNorm, final float amountLarge, final float amountFine,
                                   final byte[] out, ThreadPool pool) {
        final byte[] rowPrev = ring[clamp(y - 1, dstH) % ringSize];
        final byte[] row0 = ring[y % ringSize];
        final byte[] rowNext = ring[clamp(y + 1, dstH) % ringSize];

        pool.forRange(dstW, new ThreadPool.RangeTask() {
            @Override public void run(int from, int to) {
                int w3 = dstW * 3;
                for (int x = from; x < to; x++) {
                    int base = x * 3;
                    for (int c = 0; c < 3; c++) {
                        int i = base + c;
                        int im1 = i >= 3 ? i - 3 : c;
                        int ip1 = i + 3 < w3 ? i + 3 : w3 - 3 + c;

                        int center = row0[i] & 0xFF;
                        float blurLarge = acc[i] * boxNorm;
                        float blurFine = (
                                (rowPrev[im1] & 0xFF) + 2 * (rowPrev[i] & 0xFF) + (rowPrev[ip1] & 0xFF)
                                        + 2 * ((row0[im1] & 0xFF) + 2 * center + (row0[ip1] & 0xFF))
                                        + (rowNext[im1] & 0xFF) + 2 * (rowNext[i] & 0xFF) + (rowNext[ip1] & 0xFF)
                        ) / 16f;

                        float delta = amountLarge * (center - blurLarge) + amountFine * (center - blurFine);
                        if (delta > MAX_OVERSHOOT) delta = MAX_OVERSHOOT;
                        else if (delta < -MAX_OVERSHOOT) delta = -MAX_OVERSHOOT;

                        int v = (int) (center + delta + 0.5f);
                        out[i] = (byte) (v < 0 ? 0 : Math.min(v, 255));
                    }
                }
            }
        });
    }

    private static int clamp(int v, int n) {
        return v < 0 ? 0 : Math.min(v, n - 1);
    }

    private static void samplePreview(byte[] row, int dstW, int[] preview, int py, int previewW) {
        int o = py * previewW;
        for (int px = 0; px < previewW; px++) {
            int i = (int) ((long) px * dstW / previewW) * 3;
            preview[o + px] = 0xFF000000 | ((row[i] & 0xFF) << 16) | ((row[i + 1] & 0xFF) << 8) | (row[i + 2] & 0xFF);
        }
    }
}
