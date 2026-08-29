package com.astraupscale.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.astraupscale.engine.Preset;

/**
 * Kullanicinin secimlerini acilistan acilisa tasir.
 *
 * <p>Onceden hicbir sey saklanmiyordu ve {@code onSaveInstanceState} de
 * yoktu: uygulama arka planda yeterince beklerse Android sureci sonlandirir
 * ve kullanici geri dondugunde sectigi fotograf da, kurdugu butun ayarlar da
 * gitmis olurdu. 512K'lik bir isi hazirlayip telefonu cebe koymak bunu
 * tetiklemeye yeterdi.
 *
 * <p>Instance state yerine kalici depolama kullanilir: instance state
 * yalnizca ayni surec icinde yasar, surec olumunden sonra geri gelmez.
 * Burada tutulanlar birkac yuz bayttir; fotografin kendisi degil, yalnizca
 * ona isaret eden adres saklanir.
 */
final class Session {

    private static final String PREFS = "astraupscale";

    private static final String KEY_SOURCE = "session_source";
    private static final String KEY_PRESET = "session_preset";
    private static final String KEY_MODEL = "session_model";
    private static final String KEY_STAGES = "session_stages";
    private static final String KEY_JPEG = "session_jpeg";
    private static final String KEY_QUALITY = "session_quality";
    private static final String KEY_SHARPEN = "session_sharpen";
    private static final String KEY_DENOISE = "session_denoise";
    private static final String KEY_LOAD = "session_load";
    private static final String KEY_PAGE = "session_page";

    private Session() { }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Butun secimleri yazar. */
    static void save(Context ctx, Uri source, Preset preset, SrModel model, int stages,
                     boolean jpeg, int quality, int sharpen, int denoiseMode,
                     LoadLevel load, int page) {
        prefs(ctx).edit()
                .putString(KEY_SOURCE, source == null ? "" : source.toString())
                .putString(KEY_PRESET, preset == null ? "" : preset.name())
                .putString(KEY_MODEL, model == null ? "" : model.name())
                .putInt(KEY_STAGES, stages)
                .putBoolean(KEY_JPEG, jpeg)
                .putInt(KEY_QUALITY, quality)
                .putInt(KEY_SHARPEN, sharpen)
                .putInt(KEY_DENOISE, denoiseMode)
                .putString(KEY_LOAD, load == null ? "" : load.name())
                .putInt(KEY_PAGE, page)
                .apply();
    }

    /**
     * Saklanan kaynak adresi; yoksa null.
     *
     * <p>Adresin hala okunabilir oldugu <b>burada denetlenmez</b>: kullanici
     * fotografi silmis ya da izni geri almis olabilir. Cagiran taraf zaten
     * okumayi deneyecek ve basarisiz olursa temiz bir baslangica dusecektir.
     */
    static Uri source(Context ctx) {
        String s = prefs(ctx).getString(KEY_SOURCE, "");
        return s.isEmpty() ? null : Uri.parse(s);
    }

    /** Kayitli on ayar; yoksa ya da artik taninmiyorsa varsayilan. */
    static Preset preset(Context ctx, Preset fallback) {
        String name = prefs(ctx).getString(KEY_PRESET, "");
        for (Preset p : Preset.values()) {
            if (p.name().equals(name)) return p;
        }
        return fallback;
    }

    /**
     * Kayitli model; yoksa ya da artik pakette degilse varsayilan.
     *
     * <p>Ince bir surumde model listesi degisebilir; kayitli ad artik
     * bulunmayabilir. Bu durumda coksun degil, varsayilana donsun.
     */
    static SrModel model(Context ctx, SrModel fallback) {
        String name = prefs(ctx).getString(KEY_MODEL, "");
        for (SrModel m : SrModel.values()) {
            if (m.name().equals(name)) return m;
        }
        return fallback;
    }

    static LoadLevel load(Context ctx, LoadLevel fallback) {
        String name = prefs(ctx).getString(KEY_LOAD, "");
        for (LoadLevel l : LoadLevel.values()) {
            if (l.name().equals(name)) return l;
        }
        return fallback;
    }

    static int stages(Context ctx, int fallback) {
        return prefs(ctx).getInt(KEY_STAGES, fallback);
    }

    static boolean jpeg(Context ctx, boolean fallback) {
        return prefs(ctx).getBoolean(KEY_JPEG, fallback);
    }

    static int quality(Context ctx, int fallback) {
        return prefs(ctx).getInt(KEY_QUALITY, fallback);
    }

    static int sharpen(Context ctx, int fallback) {
        return prefs(ctx).getInt(KEY_SHARPEN, fallback);
    }

    static int denoiseMode(Context ctx, int fallback) {
        return prefs(ctx).getInt(KEY_DENOISE, fallback);
    }

    static int page(Context ctx) {
        return prefs(ctx).getInt(KEY_PAGE, 0);
    }

    /** Kaynak okunamadiginda cagrilir: yalniz adres unutulur, ayarlar kalir. */
    static void forgetSource(Context ctx) {
        prefs(ctx).edit().remove(KEY_SOURCE).apply();
    }
}
