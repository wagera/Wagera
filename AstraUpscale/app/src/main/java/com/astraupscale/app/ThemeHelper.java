package com.astraupscale.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * Aydinlik/karanlik tema secimi.
 *
 * Varsayilan olarak sistemin ayari izlenir; kullanici basliktaki dugmeyle
 * temayi sabitleyebilir. Secim, {@link Configuration#uiMode} degistirilerek
 * uygulanir; tum renkler {@code values} ve {@code values-night} altindan
 * geldigi icin baska hicbir yerde degisiklik gerekmez.
 */
public final class ThemeHelper {

    public static final int SYSTEM = 0;
    public static final int LIGHT = 1;
    public static final int DARK = 2;

    private static final String PREFS = "astraupscale";
    private static final String KEY = "theme";

    private ThemeHelper() { }

    public static int current(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, SYSTEM);
    }

    public static void set(Context ctx, int mode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, mode).apply();
    }

    /** Sistem -> aydinlik -> karanlik -> sistem. */
    public static int next(int mode) {
        return (mode + 1) % 3;
    }

    public static int labelRes(int mode) {
        if (mode == LIGHT) return R.string.theme_light;
        if (mode == DARK) return R.string.theme_dark;
        return R.string.theme_system;
    }

    /** O anda karanlik tema mi gecerli? */
    public static boolean isDark(Context ctx) {
        int mode = current(ctx);
        if (mode == LIGHT) return false;
        if (mode == DARK) return true;
        return (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Secilen tema uygulanmis bir baglam dondurur. */
    public static Context wrap(Context ctx) {
        int mode = current(ctx);
        if (mode == SYSTEM) return ctx;

        Configuration config = new Configuration(ctx.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (mode == DARK ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return ctx.createConfigurationContext(config);
    }
}
