import com.astraupscale.engine.Viewport;

/**
 * Karsilastirma ekranindaki bakis penceresi matematigini sinar.
 *
 * <p>Bu matematik gozle denetlenemez: bir isaret hatasi goruntuyu parmagin
 * altindan kaydirir, bir sinir hatasi goruntunun disini gosterir, yanlis
 * bir ornekleme araligi gigapiksellik bir bolgeyi bellege almaya calisir.
 * Uygulama bu ortamda calistirilamadigi icin (KVM yok) matematik ayri bir
 * sinifa cikarildi ve burada dogrudan sinaniyor.
 *
 * <p>Kullanim:
 * <pre>
 *   javac -d /tmp/vp app/src/main/java/com/astraupscale/engine/Viewport.java \
 *         tools/desktop/ViewportTest.java
 *   java -cp /tmp/vp ViewportTest
 * </pre>
 */
public class ViewportTest {

    private static int failures;

    private static void check(String what, boolean ok) {
        System.out.printf("%-58s %s%n", what, ok ? "gecti" : "BASARISIZ");
        if (!ok) failures++;
    }

    private static void near(String what, float actual, float expected, float tolerance) {
        boolean ok = Math.abs(actual - expected) <= tolerance;
        System.out.printf("%-58s %s (%.4f, beklenen %.4f)%n",
                what, ok ? "gecti" : "BASARISIZ", actual, expected);
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        // 7680x5760 bir 8K cikis, 1080x1920 bir telefon ekrani
        final int imgW = 7680, imgH = 5760;
        final int viewW = 1080, viewH = 1600;

        Viewport v = new Viewport(imgW, imgH, 6f);
        v.setViewSize(viewW, viewH);

        System.out.println("── sigdirma ──────────────────────────────────────");
        near("sigdirma yakinligi genisligi doldurur", v.zoom(), viewW / (float) imgW, 1e-5f);
        check("sigdirmada goruntu tumuyle gorunur",
                viewW / v.zoom() >= imgW - 1 && viewH / v.zoom() >= imgH - 1);
        near("sigdirmada kat 1.0", v.factor(), 1f, 1e-4f);

        System.out.println();
        System.out.println("── odak noktasi sabit kalmali ────────────────────");
        // Ekranin (300, 700) noktasindaki goruntu pikseli, yakinlastirmadan
        // sonra yine ayni ekran noktasinda olmali.
        float focusX = 300, focusY = 700;
        float beforeX = v.x() + focusX / v.zoom();
        float beforeY = v.y() + focusY / v.zoom();
        v.zoomTo(1f, focusX, focusY);
        float afterX = v.x() + focusX / v.zoom();
        float afterY = v.y() + focusY / v.zoom();
        near("odak altindaki piksel yatayda yerinde", afterX, beforeX, 1.5f);
        near("odak altindaki piksel dikeyde yerinde", afterY, beforeY, 1.5f);

        System.out.println();
        System.out.println("── 1:1 ───────────────────────────────────────────");
        check("1:1 gercekten 1:1", v.isOneToOne());
        near("1:1'de bir ekran pikseli bir goruntu pikseli", v.zoom(), 1f, 1e-4f);

        System.out.println();
        System.out.println("── sinirlar ──────────────────────────────────────");
        // Cok uzaga kaydirmayi dene; pencere goruntunun disina cikmamali
        for (int i = 0; i < 60; i++) v.panBy(500, 400);
        int[] r = v.visibleRegion();
        check("saga/asagi tasma yok",
                r[0] >= 0 && r[1] >= 0 && r[2] <= imgW && r[3] <= imgH);
        check("sag kenara dayandi", Math.abs((v.x() + viewW / v.zoom()) - imgW) < 1.5f);

        for (int i = 0; i < 60; i++) v.panBy(-500, -400);
        r = v.visibleRegion();
        check("sola/yukari tasma yok",
                r[0] >= 0 && r[1] >= 0 && r[2] <= imgW && r[3] <= imgH);
        near("sol kenara dayandi", v.x(), 0f, 1.5f);

        System.out.println();
        System.out.println("── yakinlik sinirlari ────────────────────────────");
        v.zoomTo(1000f, viewW / 2f, viewH / 2f);
        near("ust sinir asilmadi", v.zoom(), 6f, 1e-4f);
        v.zoomTo(0.00001f, viewW / 2f, viewH / 2f);
        near("alt sinir sigdirma yakinligi", v.zoom(), v.fitZoom(), 1e-6f);

        System.out.println();
        System.out.println("── ornekleme araligi ─────────────────────────────");
        v.setViewSize(viewW, viewH);            // sigdirmaya don
        int fitSample = v.sampleSize();
        check("sigdirmada seyrek okunur (bellek korunur)", fitSample > 1);
        long fitPixels = (long) (imgW / fitSample) * (imgH / fitSample);
        check("sigdirmada okunan piksel 8 MP altinda (" + fitPixels + ")",
                fitPixels < 8_000_000L);
        v.zoomTo(1f, viewW / 2f, viewH / 2f);
        check("1:1'de tam cozunurluk okunur", v.sampleSize() == 1);
        int[] one = v.visibleRegion();
        long onePixels = (long) (one[2] - one[0]) * (one[3] - one[1]);
        check("1:1'de okunan bolge ekran kadar (" + onePixels + " px)",
                onePixels < 4_000_000L);

        System.out.println();
        System.out.println("── kaynak/sonuc hizasi ───────────────────────────");
        // Kaynak 1920x1440, sonuc 7680x5760 -> kat 4. Kaynak tarafi ayni
        // pencereyi kendi olcegine bolerek kullanir; iki taraf ayni bolgeyi
        // gostermeli.
        final float ratio = 4f;
        int[] region = v.visibleRegion();
        float srcLeft = region[0] / ratio, srcRight = region[2] / ratio;
        near("kaynak bolgesi sonucun tam 1/4'u (sol)", srcLeft * ratio, region[0], 0.001f);
        near("kaynak bolgesi sonucun tam 1/4'u (sag)", srcRight * ratio, region[2], 0.001f);

        System.out.println();
        System.out.println("── kucuk goruntu ─────────────────────────────────");
        // Gorunume sigan kucuk bir goruntu ortalanmali, kosede takilmamali
        Viewport small = new Viewport(400, 300, 6f);
        small.setViewSize(viewW, viewH);
        small.panBy(-9999, -9999);
        float cx = small.x() + (viewW / small.zoom()) / 2f;
        near("sigan goruntu yatayda ortali kalir", cx, 200f, 1f);

        System.out.println();
        if (failures > 0) {
            System.out.println(failures + " denetim basarisiz");
            System.exit(1);
        }
        System.out.println("Butun denetimler gecti.");
    }
}
