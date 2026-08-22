import com.opeyscaler.engine.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Gurultu temizleyiciyi olcer: gurultu ne kadar azaldi, kenarlar korundu mu. */
public final class DenoiseTest {

    public static void main(String[] args) throws Exception {
        BufferedImage clean = ImageIO.read(new File(args[0]));
        int w = clean.getWidth(), h = clean.getHeight();
        int[] cleanPx = clean.getRGB(0, 0, w, h, null, 0, w);

        // Gurultulu surum uret (gauss benzeri, sigma ~ 12 seviye)
        java.util.Random rnd = new java.util.Random(7);
        int[] noisy = new int[cleanPx.length];
        for (int i = 0; i < cleanPx.length; i++) {
            int p = cleanPx[i];
            int r = clampi(((p >> 16) & 0xFF) + (int) (rnd.nextGaussian() * 12));
            int g = clampi(((p >> 8) & 0xFF) + (int) (rnd.nextGaussian() * 12));
            int b = clampi((p & 0xFF) + (int) (rnd.nextGaussian() * 12));
            noisy[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        ThreadPool pool = ThreadPool.auto();
        PixelSource den = new Denoiser(new ArrayPixelSource(noisy, w, h), 0.6f, pool);
        int[] out = new int[cleanPx.length];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            den.readRow(y, row);
            System.arraycopy(row, 0, out, y * w, w);
        }
        pool.shutdown();

        System.out.printf("gurultulu  -> temiz referans: PSNR %.2f dB%n", psnr(noisy, cleanPx));
        System.out.printf("temizlenmis-> temiz referans: PSNR %.2f dB%n", psnr(out, cleanPx));
        System.out.printf("kenar dikligi  referans %.3f | gurultulu %.3f | temizlenmis %.3f%n",
                edge(cleanPx, w, h), edge(noisy, w, h), edge(out, w, h));

        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        o.setRGB(0, 0, w, h, out, 0, w);
        ImageIO.write(o, "png", new File(args[1]));
        BufferedImage n = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        n.setRGB(0, 0, w, h, noisy, 0, w);
        ImageIO.write(n, "png", new File(args[2]));
    }

    private static int clampi(int v) { return v < 0 ? 0 : Math.min(v, 255); }

    private static double psnr(int[] a, int[] b) {
        double mse = 0;
        for (int i = 0; i < a.length; i++) {
            for (int s = 0; s <= 16; s += 8) {
                int d = ((a[i] >> s) & 0xFF) - ((b[i] >> s) & 0xFF);
                mse += d * (double) d;
            }
        }
        mse /= a.length * 3.0;
        return 10 * Math.log10(255 * 255 / mse);
    }

    /** Ortalama yatay gradyan: kenarlarin ne kadar dik kaldigini gosterir. */
    private static double edge(int[] p, int w, int h) {
        double sum = 0;
        int n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 1; x < w; x++) {
                int a = gray(p[y * w + x]), b = gray(p[y * w + x - 1]);
                sum += Math.abs(a - b);
                n++;
            }
        }
        return sum / n;
    }

    private static int gray(int p) {
        return (((p >> 16) & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587 + (p & 0xFF) * 114) / 1000;
    }
}
