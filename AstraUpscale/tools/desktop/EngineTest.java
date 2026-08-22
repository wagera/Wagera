import com.astraupscale.engine.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;

/**
 * Masaustu dogrulama kosucusu: motor saf Java oldugu icin Android olmadan da
 * gercek goruntulerle calistirilip olculebilir.
 *
 * Kullanim: java EngineTest <girdi> <cikti> <2K|4K|...|16K> <jpg|png> <keskinlik 0..1>
 */
public final class EngineTest {

    public static void main(String[] args) throws Exception {
        String in = args[0];
        String out = args[1];
        Preset preset = Preset.byLabel(args.length > 2 ? args[2] : "4K");
        String format = args.length > 3 ? args[3] : "jpg";
        float sharpen = args.length > 4 ? Float.parseFloat(args[4]) : 0.35f;

        BufferedImage img = ImageIO.read(new File(in));
        int w = img.getWidth(), h = img.getHeight();
        int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
        PixelSource src = new ArrayPixelSource(pixels, w, h);

        int[] target = preset.targetSize(w, h);
        Upscaler.Options opt = new Upscaler.Options();
        opt.targetWidth = target[0];
        opt.targetHeight = target[1];
        opt.sharpen = sharpen;
        opt.buildPreview = true;

        System.out.printf("kaynak %dx%d -> hedef %dx%d (%.1f MP), format=%s, keskinlik=%.2f%n",
                w, h, target[0], target[1], target[0] * (long) target[1] / 1e6, format, sharpen);

        long maxHeap = Runtime.getRuntime().maxMemory();
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 20)) {
            ImageWriter writer = format.equals("png")
                    ? new PngWriter(os, 4)
                    : new JpegWriter(os, 92);
            Upscaler.Result r = Upscaler.run(src, opt, writer, new Progress() {
                long last = 0;
                @Override public void onProgress(float f, String stage) {
                    long now = System.currentTimeMillis();
                    if (now - last > 2000 || f >= 1f) {
                        last = now;
                        System.out.printf("  %s  (kullanilan bellek: %d MB)%n", stage, used() / (1 << 20));
                    }
                }
                @Override public boolean isCancelled() { return false; }
            });
            System.out.printf("bitti: %dx%d, %.1f sn, onizleme %dx%d%n",
                    r.width, r.height, r.elapsedMillis / 1000.0, r.previewWidth, r.previewHeight);
        }
        System.out.printf("cikti dosyasi: %.2f MB, azami yigin: %d MB%n",
                new File(out).length() / 1048576.0, maxHeap / (1 << 20));
    }

    private static long used() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
