package com.astraupscale.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Kesfet icerigi.
 *
 * <p>Icerik Supabase'den okunur; uygulamanin surumune gomulu degildir, yani
 * yeni bir APK yayinlamadan guncellenebilir.
 *
 * <h3>Anahtar hakkinda</h3>
 * Buradaki anahtar <b>publishable</b> (anon) anahtardir ve TASARIMI GEREGI
 * herkese aciktir: APK'yi indiren herkes onu birkac dakikada cikarabilir.
 * Bu bir sorun degildir — <b>ama yalnizca Row Level Security aciksa</b>.
 *
 * <p>Sunucu tarafinda anon rolune yalnizca {@code published = true} satirlari
 * OKUMA izni verilmistir; yazma, guncelleme ve silme icin hicbir politika
 * tanimlanmadigi icin hepsi reddedilir. Sema ve politikalar
 * {@code supabase/schema.sql} dosyasindadir.
 *
 * <p><b>service_role anahtari bu dosyaya ya da baska bir yere asla
 * konmamalidir.</b> O anahtar RLS'i tumuyle atlar; APK'ya girerse uygulamayi
 * kuran herkes veritabaninda tam yetki kazanir.
 *
 * <h3>Cevrimdisi</h3>
 * Son basarili yanit diske yazilir. Ag yoksa ya da istek basarisiz olursa
 * sayfa bos kalmaz, en son bilinen icerigi gosterir ve bunun tazelenemedigini
 * soyler.
 */
final class Discover {

    /** Supabase proje adresi. */
    private static final String PROJECT = "https://iikbensnplfgtldfhmzy.supabase.co";

    /**
     * Publishable (anon) anahtar — herkese acik olmasi beklenen anahtar.
     * Guvenligi tamamen sunucudaki RLS politikalarina baglidir.
     */
    private static final String PUBLISHABLE_KEY =
            "sb_publishable_aC0ecW1iAM6-rfy8Ph4SaA_7J69N8ni";

    private static final String TABLE = "discover_items";
    private static final String PREFS = "astraupscale";
    private static final String KEY_CACHE = "discover_cache";
    private static final String KEY_CACHED_AT = "discover_cached_at";

    /** Tek bir kart. */
    static final class Item {
        final String kind, title, body, meta;

        Item(String kind, String title, String body, String meta) {
            this.kind = kind;
            this.title = title;
            this.body = body;
            this.meta = meta;
        }
    }

    /** Bir cekmenin sonucu. */
    static final class Result {
        final List<Item> items;
        /** Aga gidilip taze icerik alindi mi. */
        final boolean fresh;
        /** Basarisizsa nedeni; basariliysa bos. */
        final String failure;

        Result(List<Item> items, boolean fresh, String failure) {
            this.items = items;
            this.fresh = fresh;
            this.failure = failure;
        }
    }

    private Discover() { }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Icerigi getirir. <b>Arka planda cagrilmalidir.</b>
     *
     * @param locale "tr" ya da "en"
     */
    static Result load(Context ctx, String locale) {
        String cached = prefs(ctx).getString(KEY_CACHE, "");

        if (!UpdateChecker.online(ctx)) {
            return new Result(parse(cached), false, "offline");
        }

        HttpURLConnection conn = null;
        try {
            String url = PROJECT + "/rest/v1/" + TABLE
                    + "?select=kind,title,body,meta"
                    + "&published=eq.true"
                    + "&locale=eq." + URLEncoder.encode(locale, "UTF-8")
                    + "&order=sort.asc,id.asc"
                    + "&limit=50";

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("apikey", PUBLISHABLE_KEY);
            conn.setRequestProperty("Authorization", "Bearer " + PUBLISHABLE_KEY);
            conn.setRequestProperty("Accept", "application/json");
            // Guncelleme denetiminde oldugu gibi: takilmis bir yanit,
            // icerigin hic tazelenmemesi demektir.
            conn.setUseCaches(false);

            int status = conn.getResponseCode();
            if (status != 200) {
                return new Result(parse(cached), false, "HTTP " + status);
            }

            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && bos.size() < (1 << 20)) bos.write(buf, 0, n);
            in.close();

            String body = bos.toString("UTF-8");
            List<Item> items = parse(body);
            if (items.isEmpty() && !cached.isEmpty()) {
                // Sunucu bos donduyse elimizdekini atma: icerik henuz
                // yayinlanmamis olabilir, kullaniciyi bos ekranla birakma.
                return new Result(parse(cached), false, "empty");
            }
            prefs(ctx).edit()
                    .putString(KEY_CACHE, body)
                    .putLong(KEY_CACHED_AT, System.currentTimeMillis())
                    .apply();
            return new Result(items, true, "");
        } catch (Throwable t) {
            return new Result(parse(cached), false, t.getClass().getSimpleName());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Onbellekteki icerigin yasi (ms); hic alinmadiysa -1. */
    static long cacheAgeMillis(Context ctx) {
        long at = prefs(ctx).getLong(KEY_CACHED_AT, 0);
        return at == 0 ? -1 : System.currentTimeMillis() - at;
    }

    /**
     * JSON dizisini kartlara cevirir.
     *
     * <p>Bozuk ya da eksik bir kayit butun listeyi dusurmemeli: sunucudaki
     * tek bir hatali satir yuzunden sayfanin tamami bos kalmasin diye her
     * oge ayri ayri degerlendirilir.
     */
    private static List<Item> parse(String json) {
        List<Item> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String title = o.optString("title", "");
                String body = o.optString("body", "");
                if (title.isEmpty() || body.isEmpty()) continue;
                out.add(new Item(
                        o.optString("kind", "note"),
                        title,
                        body,
                        o.optString("meta", "")));
            }
        } catch (Throwable ignored) {
            // Bozuk onbellek: bos liste don, cagiran taraf aga gidecek.
        }
        return out;
    }
}
