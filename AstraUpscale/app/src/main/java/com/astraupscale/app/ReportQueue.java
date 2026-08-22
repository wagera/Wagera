package com.astraupscale.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Gonderilmeyi bekleyen kayitlarin kalici kuyrugu.
 *
 * Internet yokken uretilen her sey diske yazilir ve baglanti gelince
 * sirayla gonderilir. Dosya, her satiri bir JSON nesnesi olan duz metindir;
 * boylece yarim kalan bir yazma yalnizca son satiri bozar, kuyrugun tamamini
 * degil.
 */
public final class ReportQueue {

    /** Kuyrukta tutulabilecek azami kayit; asilirsa en eskiler dusurulur. */
    private static final int MAX_ENTRIES = 500;
    private static final String FILE = "report_queue.jsonl";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class Entry {
        public final String target;   // "feedback" veya "event"
        public final JSONObject payload;

        Entry(String target, JSONObject payload) {
            this.target = target;
            this.payload = payload;
        }
    }

    private ReportQueue() { }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE);
    }

    /** Kuyruga bir kayit ekler. */
    public static void add(Context ctx, String target, JSONObject payload) {
        add(file(ctx), target, payload);
    }

    /** Kuyruga bir kayit ekler (dosya uzerinden; masaustunde de calisir). */
    public static synchronized void add(File f, String target, JSONObject payload) {
        try {
            JSONObject line = new JSONObject();
            line.put("target", target);
            line.put("payload", payload);
            line.put("queuedAt", System.currentTimeMillis());

            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write((line.toString() + "\n").getBytes(UTF8));
            }
            trim(f);
        } catch (Throwable ignored) {
            // Kuyruga yazamamak uygulamayi etkilememeli.
        }
    }

    /** Kuyruktaki tum kayitlari sirayla okur. */
    public static List<Entry> all(Context ctx) {
        return all(file(ctx));
    }

    public static synchronized List<Entry> all(File f) {
        List<Entry> out = new ArrayList<>();
        if (!f.exists()) return out;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            String line;
            while ((line = raf.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    // readLine baytlari Latin-1 gibi okur; UTF-8'e geri cevir.
                    JSONObject o = new JSONObject(
                            new String(line.getBytes("ISO-8859-1"), UTF8));
                    out.add(new Entry(o.optString("target", "event"),
                            o.getJSONObject("payload")));
                } catch (Throwable ignored) {
                    // Bozuk satir atlanir.
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static int size(Context ctx) {
        return all(ctx).size();
    }

    /** Basariyla gonderilen ilk {@code count} kaydi siler. */
    public static void removeFirst(Context ctx, int count) {
        removeFirst(file(ctx), count);
    }

    public static synchronized void removeFirst(File f, int count) {
        if (count <= 0) return;
        List<Entry> rest = all(f);
        if (count >= rest.size()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return;
        }
        rewrite(f, rest.subList(count, rest.size()));
    }

    private static void trim(File f) {
        List<Entry> entries = all(f);
        if (entries.size() <= MAX_ENTRIES) return;
        rewrite(f, entries.subList(entries.size() - MAX_ENTRIES, entries.size()));
    }

    private static void rewrite(File f, List<Entry> entries) {
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            for (Entry e : entries) {
                JSONObject line = new JSONObject();
                line.put("target", e.target);
                line.put("payload", e.payload);
                out.write((line.toString() + "\n").getBytes(UTF8));
            }
        } catch (Throwable ignored) {
        }
    }

    /** Kuyruktaki kayitlarin JSON dizisi olarak ozeti (hata ayiklama icin). */
    public static JSONArray summary(Context ctx) {
        JSONArray a = new JSONArray();
        for (Entry e : all(ctx)) a.put(e.target);
        return a;
    }
}
