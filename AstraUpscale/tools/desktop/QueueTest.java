import com.astraupscale.app.ReportQueue;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Bildirim kuyrugunun dayanikliligini olcer: Turkce karakterlerin kayipsiz
 * gidip gelmesi, bozuk satirin kuyrugu bozmamasi, gonderilen kayitlarin
 * dogru silinmesi ve ust sinirin uygulanmasi.
 */
public final class QueueTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        File f = File.createTempFile("queue", ".jsonl");
        //noinspection ResultOfMethodCallIgnored
        f.delete();

        // 1. Turkce metin gidip geliyor mu
        String text = "Çözünürlük çok iyi! Şğüöıİ — teşekkürler 👍";
        JSONObject o = new JSONObject();
        o.put("message", text);
        o.put("kind", "idea");
        ReportQueue.add(f, "feedback", o);

        List<ReportQueue.Entry> back = ReportQueue.all(f);
        check("tek kayit okundu", back.size() == 1);
        check("Turkce metin bozulmadi", text.equals(back.get(0).payload.optString("message")));
        check("hedef korundu", "feedback".equals(back.get(0).target));

        // 2. Bozuk satir kuyrugun tamamini bozmamali
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write("{bozuk json satiri\n".getBytes(StandardCharsets.UTF_8));
        }
        JSONObject o2 = new JSONObject();
        o2.put("event", "job_done");
        ReportQueue.add(f, "event", o2);
        back = ReportQueue.all(f);
        check("bozuk satir atlandi, digerleri okundu", back.size() == 2);
        check("bozuk satirdan sonraki kayit saglam",
                "job_done".equals(back.get(1).payload.optString("event")));

        // 3. Gonderilen kayitlar silinince kalanlar korunmali
        ReportQueue.removeFirst(f, 1);
        back = ReportQueue.all(f);
        check("ilk kayit silindi", back.size() == 1);
        check("kalan kayit dogru", "job_done".equals(back.get(0).payload.optString("event")));

        // 4. Hepsi silinince dosya kalmamali
        ReportQueue.removeFirst(f, 5);
        check("kuyruk bosaldi", ReportQueue.all(f).isEmpty());

        // 5. Ust sinir: 500'un uzerine cikilmamali, en yeniler kalmali
        for (int i = 0; i < 540; i++) {
            JSONObject e = new JSONObject();
            e.put("n", i);
            ReportQueue.add(f, "event", e);
        }
        back = ReportQueue.all(f);
        check("ust sinir uygulandi (" + back.size() + ")", back.size() == 500);
        check("en yeni kayit duruyor", back.get(back.size() - 1).payload.optInt("n") == 539);
        check("en eskiler dusuruldu", back.get(0).payload.optInt("n") == 40);

        //noinspection ResultOfMethodCallIgnored
        f.delete();
        System.out.println(failures == 0 ? "\nTUM TESTLER GECTI" : "\n" + failures + " TEST BASARISIZ");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "  gecti  " : "  KALDI  ") + name);
        if (!ok) failures++;
    }
}
