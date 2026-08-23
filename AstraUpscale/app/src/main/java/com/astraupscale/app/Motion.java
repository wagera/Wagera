package com.astraupscale.app;

import android.content.Context;
import android.provider.Settings;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * Giris koreografisi.
 *
 * <p>Sayfa acildiginda ogeler sirayla yukselerek belirir. Sira, gozun
 * okumasi gereken sirayla ayni: once baslik, sonra sahne, sonra eylem
 * cubugu, en sonda ayar satirlari. Boylece hareket sussuz bir yonlendirme
 * gorevi gorur — dikkat dagitmaz, dikkati tasir.
 *
 * <p>Kullanici sistem ayarlarindan animasyonlari kapattiysa hicbir sey
 * oynatilmaz; ogeler dogrudan yerinde gorunur.
 */
final class Motion {

    private Motion() {}

    /** Ana yumusatma egrisi: hizli baslar, yumusak durur. */
    private static final PathInterpolator EASE = new PathInterpolator(0.16f, 1f, 0.3f, 1f);

    private static final long RISE_MILLIS = 460L;
    /** Ogeler arasi gecikme; sirali ama beklemeye donusmeyecek kadar kisa. */
    private static final long STEP_MILLIS = 55L;
    private static final float RISE_DP = 10f;

    /** Sistemin animasyon olcegi; 0 ise kullanici animasyonlari kapatmistir. */
    private static float scale = 1f;

    static void read(Context context) {
        scale = Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
    }

    /** Sistem olcegine gore ayarlanmis sure; animasyon kapaliysa 0. */
    static long scaled(long millis) {
        return scale <= 0f ? 0L : (long) (millis * Math.min(scale, 1.5f));
    }

    static boolean enabled() {
        return scale > 0f;
    }

    /**
     * Verilen ogeleri sirayla yukselterek gosterir.
     *
     * <p>Null ogeler atlanir, ancak sirayi bozmaz: gorunmeyen bir kart
     * yuzunden sonraki ogelerin gecikmesi kaymaz.
     */
    static void enter(View... views) {
        if (!enabled()) {
            for (View v : views) {
                if (v != null) { v.setAlpha(1f); v.setTranslationY(0f); }
            }
            return;
        }
        float rise = RISE_DP;
        for (int i = 0; i < views.length; i++) {
            View v = views[i];
            if (v == null) continue;
            rise = RISE_DP * v.getResources().getDisplayMetrics().density;
            v.setAlpha(0f);
            v.setTranslationY(rise);
            v.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(scaled(STEP_MILLIS * i))
                    .setDuration(scaled(RISE_MILLIS))
                    .setInterpolator(EASE)
                    .start();
        }
    }

    /** Durum degisiminde sahnedeki katmani yumusakca degistirir. */
    static void crossFade(final View out, final View in) {
        if (!enabled()) {
            if (out != null) out.setVisibility(View.GONE);
            if (in != null) { in.setAlpha(1f); in.setVisibility(View.VISIBLE); }
            return;
        }
        if (in != null) {
            in.setAlpha(0f);
            in.setVisibility(View.VISIBLE);
            in.animate().alpha(1f).setDuration(scaled(220L)).setInterpolator(EASE).start();
        }
        if (out != null) {
            out.animate().alpha(0f).setDuration(scaled(160L)).withEndAction(new Runnable() {
                @Override public void run() {
                    out.setVisibility(View.GONE);
                    out.setAlpha(1f);
                }
            }).start();
        }
    }
}
