package com.astraupscale.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;

import java.util.Locale;

/**
 * Uygulama dili. Varsayilan olarak sistemin dili kullanilir; kullanici
 * basliktaki dugmeyle Turkce ya da Ingilizce'ye sabitleyebilir.
 */
public final class LocaleHelper {

    public static final String SYSTEM = "";
    public static final String TURKISH = "tr";
    public static final String ENGLISH = "en";

    private static final String PREFS = "astraupscale";
    private static final String KEY = "language";

    private LocaleHelper() { }

    public static String current(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM);
    }

    public static void set(Context ctx, String language) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, language).apply();
    }

    /** Secilen dilden sonraki secenek: sistem -> Turkce -> Ingilizce -> sistem. */
    public static String next(String language) {
        if (SYSTEM.equals(language)) return TURKISH;
        if (TURKISH.equals(language)) return ENGLISH;
        return SYSTEM;
    }

    /** Basliktaki dugmede gorunecek kisa etiket. */
    public static String label(Context ctx, String language) {
        if (TURKISH.equals(language)) return "TR";
        if (ENGLISH.equals(language)) return "EN";
        return ctx.getString(R.string.language_system).toUpperCase(Locale.US).substring(0, 3);
    }

    /** Secilen dili uygulanmis bir baglam dondurur. */
    public static Context wrap(Context ctx) {
        String language = current(ctx);
        if (SYSTEM.equals(language)) return ctx;

        Locale locale = new Locale(language);
        Locale.setDefault(locale);
        Configuration config = new Configuration(ctx.getResources().getConfiguration());
        if (Build.VERSION.SDK_INT >= 24) {
            config.setLocales(new android.os.LocaleList(locale));
        } else {
            config.setLocale(locale);
        }
        return ctx.createConfigurationContext(config);
    }
}
