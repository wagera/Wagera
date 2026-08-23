import java.io.*;
import java.net.*;

/**
 * UpdateChecker'in kullandigi adresi masaustunde sinar.
 *
 * <p>Bu sinama bir gerileme sinamasidir. Kodda adres bir surum boyunca
 * "surum.json" yaziyordu; depoda oyle bir dosya yok, dolayisiyla her
 * denetim 404 aliyor ve <b>sessizce</b> donuyordu. Sonuc: surum denetimi
 * hic calismadi, hicbir kullanici hicbir guncellemeyi gormedi ve hicbir
 * yerde iz kalmadi.
 *
 * <p>Adres UpdateChecker.java kaynagindan okunur — burada ikinci bir kopya
 * tutulmaz, yoksa ikisi birbirinden ayrilir ve sinama yalan soyler.
 *
 * <p>Kullanim:
 * <pre>
 *   javac -d /tmp/t tools/desktop/UpdateUrlTest.java
 *   java -cp /tmp/t UpdateUrlTest app/src/main/java/com/astraupscale/app/UpdateChecker.java
 * </pre>
 */
public class UpdateUrlTest {
    public static void main(String[] a) throws Exception {
        // UpdateChecker.java'daki adresin aynisi, kaynaktan okunur
        String src = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(a[0])), "UTF-8");
        int i = src.indexOf("VERSION_URL");
        String url = src.substring(src.indexOf('"', i) + 1, src.indexOf('"', src.indexOf('"', i) + 1));
        System.out.println("kaynaktan okunan adres: " + url);

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        int status = c.getResponseCode();
        System.out.println("HTTP " + status);
        if (status != 200) {
            System.out.println("BASARISIZ: 200 bekleniyordu");
            System.exit(1);
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        String body = sb.toString();
        System.out.println("govde:\n" + body.trim());
        if (!body.contains("versionCode")) {
            System.out.println("BASARISIZ: versionCode alani yok");
            System.exit(1);
        }
        System.out.println("GECTI: adres 200 donuyor ve versionCode okunabiliyor");
    }
}
