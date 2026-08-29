import com.astraupscale.engine.ThreadPool;
import com.astraupscale.engine.Yuv;
import com.astraupscale.engine.YuvWriter;

import java.nio.ByteBuffer;

/**
 * YUV cevriminin gidis-donusunu olcer.
 *
 * <p>Video hattinin en sessiz kirilma noktasi burasi. Yanlis bir katsayi,
 * ters bir renk duzlemi ya da satir adiminin gozden kacmasi coku uretmez;
 * yalnizca renkleri kaydirir ya da goruntuyu tuhaf sekilde yesillestirir —
 * ve bu, ancak bir saatlik bir buyutmenin sonunda fark edilir. O yuzden
 * cevrim burada, saniyeler icinde, sayilarla sinanir.
 *
 * <p>Sinanan sey: bir RGB karesi kodlayici duzlemlerine yazilip geri
 * okundugunda ayni kare geri geliyor mu. Kayipsiz olamaz — 4:2:0 renk
 * duzlemini yariya indirir ve sinirli aralik 8 bitin 219'unu kullanir —
 * ama hata duz alanlarda birkac seviyeyi gecmemeli.
 *
 * <p>Kullanim:
 * <pre>
 *   javac -d /tmp/t app/src/main/java/com/astraupscale/engine/*.java \
 *                   tools/desktop/YuvTest.java
 *   java -cp /tmp/t YuvTest
 * </pre>
 */
public final class YuvTest {

    private static int failures;

    public static void main(String[] args) {
        // Uc yerlesim de sinanir: I420 (ayri duzlemler), NV12 (ic ice
        // gecmis renk) ve satir adimi genislikten buyuk olan hizalanmis
        // yerlesim. Ucu de gercek yongalarda karsimiza cikiyor.
        for (Yuv.Space space : new Yuv.Space[]{
                Yuv.Space.BT709, Yuv.Space.BT601, Yuv.Space.BT2020,
                Yuv.Space.BT709.withRange(true)}) {
            roundTrip("I420", space, 64, 48, false, 0);
            roundTrip("NV12", space, 64, 48, true, 0);
            roundTrip("padded", space, 62, 48, false, 32);
        }
        grayRamp();
        primaries();

        if (failures > 0) {
            System.out.println("\n" + failures + " sinama basarisiz.");
            System.exit(1);
        }
        System.out.println("\nButun YUV sinamalari gecti.");
    }

    /** Duz renkli bloklardan olusan bir kare yazip geri okur. */
    private static void roundTrip(String layout, Yuv.Space space, int w, int h,
                                  boolean semiPlanar, int extraStride) {
        byte[] source = blocks(w, h);
        byte[] back = new byte[w * h * 3];

        Frame frame = new Frame(w, h, semiPlanar, extraStride);
        write(frame, source, w, h, space);
        Yuv.toRgb(frame.y, frame.u, frame.v, w, h, back, Yuv.table(space), new ThreadPool(1));

        // Blok ortalari karsilastirilir: 4:2:0 blok sinirlarinda rengi
        // komsusuyla harmanlar, orada sapma beklenir ve dogaldir.
        int worst = 0;
        for (int y = 2; y < h - 2; y++) {
            for (int x = 2; x < w - 2; x++) {
                if (nearEdge(x, y, w, h)) continue;
                int i = (y * w + x) * 3;
                for (int c = 0; c < 3; c++) {
                    worst = Math.max(worst,
                            Math.abs((source[i + c] & 0xFF) - (back[i + c] & 0xFF)));
                }
            }
        }
        // Sinirli aralik 8 bitin 219 seviyesini kullanir; yuvarlama ile
        // birlikte uc seviyelik bir sapma bu cevrimin tabanidir.
        check(String.format("%-8s %-7s %dx%d  en buyuk sapma %d",
                        layout, name(space), w, h, worst),
                worst <= 3);
    }

    /** Siyahtan beyaza gri gecis: parlaklik olcegi dogru mu. */
    private static void grayRamp() {
        int w = 256, h = 16;
        byte[] source = new byte[w * h * 3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 3;
                source[i] = source[i + 1] = source[i + 2] = (byte) x;
            }
        }
        byte[] back = new byte[w * h * 3];
        Frame frame = new Frame(w, h, false, 0);
        write(frame, source, w, h, Yuv.Space.BT709);
        Yuv.toRgb(frame.y, frame.u, frame.v, w, h, back, Yuv.table(Yuv.Space.BT709),
                new ThreadPool(1));

        int worst = 0;
        boolean monotonic = true;
        int previous = -1;
        for (int x = 0; x < w; x++) {
            int i = (8 * w + x) * 3;
            int value = back[i] & 0xFF;
            worst = Math.max(worst, Math.abs(value - x));
            if (value < previous) monotonic = false;
            previous = value;
        }
        check("gri gecis    en buyuk sapma " + worst, worst <= 3);
        check("gri gecis    tek yonlu artiyor", monotonic);
    }

    /** Saf kirmizi/yesil/mavi: renk duzlemleri yer degistirmis mi. */
    private static void primaries() {
        int[][] colors = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255},
                          {255, 255, 0}, {0, 255, 255}, {255, 0, 255}};
        String[] names = {"kirmizi", "yesil", "mavi", "sari", "camgobegi", "eflatun"};
        for (int k = 0; k < colors.length; k++) {
            int w = 32, h = 32;
            byte[] source = new byte[w * h * 3];
            for (int i = 0; i < w * h; i++) {
                source[i * 3] = (byte) colors[k][0];
                source[i * 3 + 1] = (byte) colors[k][1];
                source[i * 3 + 2] = (byte) colors[k][2];
            }
            byte[] back = new byte[w * h * 3];
            Frame frame = new Frame(w, h, false, 0);
            write(frame, source, w, h, Yuv.Space.BT709);
            Yuv.toRgb(frame.y, frame.u, frame.v, w, h, back, Yuv.table(Yuv.Space.BT709),
                    new ThreadPool(1));

            int i = (16 * w + 16) * 3;
            int dr = Math.abs((back[i] & 0xFF) - colors[k][0]);
            int dg = Math.abs((back[i + 1] & 0xFF) - colors[k][1]);
            int db = Math.abs((back[i + 2] & 0xFF) - colors[k][2]);
            int worst = Math.max(dr, Math.max(dg, db));
            check(String.format("%-11s geri geldi (sapma %d)", names[k], worst), worst <= 3);
        }
    }

    // ------------------------------------------------------------------ yardimcilar

    /** Kodlayici giris tamponunu taklit eden duzlem ucusu. */
    private static final class Frame {
        final Yuv.Plane y, u, v;

        Frame(int w, int h, boolean semiPlanar, int extraStride) {
            int yStride = w + extraStride;
            int cw = (w + 1) / 2, ch = (h + 1) / 2;
            if (semiPlanar) {
                int cStride = yStride;
                ByteBuffer buf = ByteBuffer.allocate(yStride * h + cStride * ch + 8);
                y = new Yuv.Plane(buf, 0, yStride, 1);
                u = new Yuv.Plane(buf, yStride * h, cStride, 2);
                v = new Yuv.Plane(buf, yStride * h + 1, cStride, 2);
            } else {
                int cStride = cw + extraStride / 2;
                ByteBuffer buf = ByteBuffer.allocate(yStride * h + 2 * cStride * ch + 8);
                y = new Yuv.Plane(buf, 0, yStride, 1);
                u = new Yuv.Plane(buf, yStride * h, cStride, 1);
                v = new Yuv.Plane(buf, yStride * h + cStride * ch, cStride, 1);
            }
        }
    }

    /** Kareyi {@link YuvWriter} ile satir satir yazar; motorun yaptigi budur. */
    private static void write(Frame frame, byte[] rgb, int w, int h, Yuv.Space space) {
        try {
            YuvWriter writer = new YuvWriter(frame.y, frame.u, frame.v, w, h, Yuv.table(space));
            writer.start(w, h);
            byte[] row = new byte[w * 3];
            for (int y = 0; y < h; y++) {
                System.arraycopy(rgb, y * w * 3, row, 0, w * 3);
                writer.writeRow(row);
            }
            writer.finish();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 8x8'lik duz renk bloklari: renk alt ornekleme icinde bozulmayan bir desen. */
    private static byte[] blocks(int w, int h) {
        byte[] out = new byte[w * h * 3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int bx = x / 8, by = y / 8;
                int i = (y * w + x) * 3;
                out[i] = (byte) ((bx * 37 + by * 11) & 0xFF);
                out[i + 1] = (byte) ((bx * 91 + by * 53) & 0xFF);
                out[i + 2] = (byte) ((bx * 17 + by * 143) & 0xFF);
            }
        }
        return out;
    }

    private static boolean nearEdge(int x, int y, int w, int h) {
        return x % 8 < 2 || x % 8 > 5 || y % 8 < 2 || y % 8 > 5;
    }

    private static String name(Yuv.Space s) {
        String base = s.kr == Yuv.Space.BT601.kr ? "601"
                : (s.kr == Yuv.Space.BT2020.kr ? "2020" : "709");
        return base + (s.fullRange ? "/tam" : "");
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "gecti  " : "KALDI  ") + what);
        if (!ok) failures++;
    }
}
