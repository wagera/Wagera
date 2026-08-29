import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/**
 * Bildirim ve arayuz bicim dizelerini gercekten formatlar.
 *
 * <p>Bir bicim dizesindeki uyusmazlik (orn. %d yerine %s, ya da eksik
 * argüman) derleme sirasinda yakalanmaz; kod ancak o dize kullanildiginda
 * coker. Bildirimlerde bu, is bittigi anda yani en kotu anda olur.
 *
 * <p>Bu sinama her dili ayri ayri dener: Turkce ceviri ile Ingilizce
 * asil dizenin argüman sayisi ya da turleri farkliysa burada gorunur.
 *
 * <p>Kullanim:
 * <pre>
 *   javac -d /tmp/t tools/desktop/FormatStringsTest.java
 *   java -cp /tmp/t FormatStringsTest app/src/main/res
 * </pre>
 */
public class FormatStringsTest {

    /**
     * Sinanacak dizeler ve kodun onlara verdigi gercek argümanlar.
     *
     * <p>Buradaki argümanlar cagri yerlerinden birebir alinmalidir. Ilk
     * yazilisinda result_format icin argümanlar tahminle yazilmisti ve
     * sinama var olmayan bir hatayi bildirdi. Yanlis alarm veren bir
     * sinama, hic olmayandan daha kotudur: gercek hatalari da golgeler.
     */
    private static final Object[][] CASES = {
        {"notif_running_title", new Object[]{"8K"}},
        {"notif_running_text", new Object[]{45, "Buyutuluyor"}},
        {"notif_done_text", new Object[]{7680, 5760, 44.2, "162 MB", 138.0}},
        {"source_format", new Object[]{4032, 3024, 12.2}},
        {"target_format", new Object[]{7680, 5760, 44.2, 1.9}},
        // MainActivity'deki gercek cagriyla ayni sira ve turler:
        // (genislik, yukseklik, MP, motor, MB, saniye, dosya adi)
        {"result_format", new Object[]{7680, 5760, 44.2, "Real-ESRGAN (GPU)", 162.4, 138.0,
                                       "AstraUpscale_1.jpg"}},
        {"update_check_failed", new Object[]{"HTTP 404"}},
        {"summary_jpeg", new Object[]{95}},

        // Video hatti. Argümanlar VideoTranscoder, VideoService ve
        // MainActivity'deki cagri yerlerinden birebir alinmistir; kare
        // sayilari long, olculen hiz float olarak gecer.
        {"v_source_format", new Object[]{1920, 1080, "1:42", 29.97f}},
        {"v_target_format", new Object[]{7680, 4320, 33.2, 4.0}},
        {"v_target_frames", new Object[]{3060L}},
        {"v_encoder_line", new Object[]{"H.265 / HEVC  ·  48 Mbps"}},
        {"v_encoder_capped", new Object[]{3840, 2160, "16K"}},
        {"v_sequence_line", new Object[]{3060L}},
        {"v_bitrate_value", new Object[]{48.3}},
        {"v_pipe_source", new Object[]{1, 1920, 1080, 3060L, 29.97f}},
        {"v_pipe_decode", new Object[]{2, "AVC", "YUV 4:2:0"}},
        {"v_pipe_encode_video", new Object[]{7, "H.265 / HEVC", 48.3}},
        {"v_pipe_encode_sequence", new Object[]{7, "PNG", 3060L}},
        {"v_pipe_audio", new Object[]{8}},
        {"v_pipe_budget", new Object[]{6, "1.4 GB", 3060L}},
        {"v_stage_frames", new Object[]{128L, 3060L, 0.62f}},
        {"v_note_single_pass", new Object[]{1.6}},
        {"v_error_size_changed", new Object[]{1920, 1080, 1280, 720}},
        {"v_notif_running_title", new Object[]{"8K"}},
        {"v_notif_done_text", new Object[]{7680, 4320, 3060L, "1.4 GB", 96.5}},
        {"v_notif_done_sequence", new Object[]{3060L, 15360, 8640, "212 GB"}},
        {"v_result_format", new Object[]{7680, 4320, 3060L, "Real-ESRGAN Hizli (GPU)",
                                         "H.265 / HEVC  ·  48 Mbps", 1432.5, 96.5,
                                         "ses tasindi"}},
        {"v_result_sequence", new Object[]{15360, 8640, 3060L, "Real-ESRGAN Hizli (GPU)",
                                           "PNG", 217344.0, 640.0, "ses yok"}},
    };

    public static void main(String[] args) throws Exception {
        File res = new File(args[0]);
        int failures = 0;
        for (String folder : new String[]{"values", "values-tr"}) {
            File f = new File(new File(res, folder), "strings.xml");
            if (!f.exists()) continue;
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(f);
            NodeList list = doc.getElementsByTagName("string");
            for (Object[] test : CASES) {
                String name = (String) test[0];
                Object[] argv = (Object[]) test[1];
                String value = null;
                for (int i = 0; i < list.getLength(); i++) {
                    Element el = (Element) list.item(i);
                    if (name.equals(el.getAttribute("name"))) {
                        value = el.getTextContent().replace("\\'", "'");
                        break;
                    }
                }
                if (value == null) {
                    System.out.println("EKSIK  " + folder + "/" + name);
                    failures++;
                    continue;
                }
                try {
                    String out = String.format(Locale.US, value, argv);
                    System.out.printf("gecti  %-14s %-22s -> %s%n", folder, name, out);
                } catch (Exception e) {
                    System.out.println("HATA   " + folder + "/" + name + ": " + e);
                    failures++;
                }
            }
        }
        System.out.println();
        if (failures > 0) {
            System.out.println(failures + " dize basarisiz");
            System.exit(1);
        }
        System.out.println("Butun bicim dizeleri kendi argümanlariyla formatlandi.");
    }
}
