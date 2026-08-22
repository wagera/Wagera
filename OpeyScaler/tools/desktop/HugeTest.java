import com.opeyscaler.engine.*;

import java.io.*;

/**
 * Cok buyuk cikislarin sinir davranisini olcer: PNG yazicinin asiri genis
 * satirlari ve JPEG'in 16 bitlik boyut siniri. Tam boyutlu dosya uretmek
 * yerine yalnizca birkac satir yazilir; amac genislik yolunun ve bellek
 * kullaniminin dogrulanmasidir.
 */
public final class HugeTest {

    public static void main(String[] args) throws Exception {
        int[] widths = {30720, 61440, 122880, 245760, 491520};
        String[] names = {"32K", "64K", "128K", "256K", "512K"};

        for (int i = 0; i < widths.length; i++) {
            int w = widths[i], h = 8;
            File f = File.createTempFile("opey", ".png");
            long before = used();
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(f), 1 << 20)) {
                PngWriter png = new PngWriter(os, 4);
                png.start(w, h);
                byte[] row = new byte[w * 3];
                for (int x = 0; x < w; x++) {
                    row[x * 3] = (byte) (x & 0xFF);
                    row[x * 3 + 1] = (byte) ((x >> 3) & 0xFF);
                    row[x * 3 + 2] = (byte) ((x >> 6) & 0xFF);
                }
                for (int y = 0; y < h; y++) png.writeRow(row);
                png.finish();
            }
            System.out.printf("%-5s PNG %6d px genislik -> %d bayt, satir tamponu %.1f MB%n",
                    names[i], w, f.length(), w * 3 / 1048576.0);
            f.delete();

            // JPEG siniri
            try {
                JpegWriter jpeg = new JpegWriter(new ByteArrayOutputStream(), 92);
                jpeg.start(w, (int) (w * 0.75));
                System.out.printf("      JPEG %d px: kabul edildi%n", w);
            } catch (IOException e) {
                System.out.printf("      JPEG %d px: reddedildi (%s)%n", w, e.getMessage());
            }
        }
        System.out.printf("azami yigin: %d MB%n", Runtime.getRuntime().maxMemory() >> 20);
    }

    private static long used() {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }
}
