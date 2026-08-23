package com.astraupscale.app;

import android.content.Context;
import android.provider.Settings;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * Giris koreografisi.
 *
 * <p>Sayfa acildiginda ogeler tek seferlik bir zaman cizgisinde belirir.
 * Sira, gozun okumasi gereken sirayla ayni: once marka, sonra baslik
 * satirlari, sonra aciklama, sonra birincil eylem, en sonda ayar satirlari.
 * Boylece hareket sussuz bir yonlendirme gorevi gorur.
 *
 * <p>Gecikmeler referans tasarimin merdiveninden alinmistir; iki egri
 * kullanilir: govde ogeleri icin {@link #EASE}, baslik satirlarinin
 * acilmasi icin biraz daha uzun sonlanan {@link #EASE_LINE}.
 *
 * <p>Kullanici sistem ayarlarindan animasyonlari kapattiysa hicbir sey
 * oynatilmaz; ogeler dogrudan yerinde gorunur. Bu bir sus degil, erisim
 * gereksinimi: hareket duyarliligi olan kullanicilar icin kapali kalmali.
 */
final class Motion {

    private Motion() {}

    /** Govde ogeleri: hizli baslar, yumusak durur. */
    private static final PathInterpolator EASE = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    /** Baslik satirlari: daha uzun kuyruk, harfler yerine otururken sakin. */
    private static final PathInterpolator EASE_LINE = new PathInterpolator(0.22f, 1f, 0.36f, 1f);

    /** Yukselme mesafesi (dp). */
    private static final float RISE_DP = 8f;

    /** Sistemin animasyon olcegi; 0 ise kullanici animasyonlari kapatmistir. */
    private static float scale = 1f;

    static void read(Context context) {
        scale = Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
    }

    static long scaled(long millis) {
        return scale <= 0f ? 0L : (long) (millis * Math.min(scale, 1.5f));
    }

    static boolean enabled() {
        return scale > 0f;
    }

    /** Bir ogenin zaman cizgisindeki yeri: sure ve gecikme (ms). */
    static final class Step {
        final View view;
        final long duration;
        final long delay;

        Step(View view, long duration, long delay) {
            this.view = view;
            this.duration = duration;
            this.delay = delay;
        }
    }

    static Step step(View view, long duration, long delay) {
        return new Step(view, duration, delay);
    }

    /**
     * Verilen ogeleri kendi sure ve gecikmeleriyle yukselterek gosterir.
     *
     * <p>Her ogenin gecikmesi acikca verilir; bir onceki ogeye gore
     * hesaplanmaz. Boylece aradaki bir oge gizlenirse sonrakilerin
     * zamanlamasi kaymaz.
     */
    static void enter(Step... steps) {
        for (Step s : steps) {
            if (s == null || s.view == null) continue;
            if (!enabled()) {
                s.view.setAlpha(1f);
                s.view.setTranslationY(0f);
                continue;
            }
            float rise = RISE_DP * s.view.getResources().getDisplayMetrics().density;
            s.view.setAlpha(0f);
            s.view.setTranslationY(rise);
            s.view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(scaled(s.delay))
                    .setDuration(scaled(s.duration))
                    .setInterpolator(EASE)
                    .start();
        }
    }

    /**
     * Baslik satirini kendi kirpma penceresinden asagidan yukari acar.
     *
     * <p>Satir once kendi yuksekligi kadar asagi itilir, sonra yerine
     * kayar. Ust ogenin {@code clipChildren} degeri true oldugu icin
     * satir pencerenin disinda gorunmez — harfler alttan "yukselerek"
     * ortaya cikar, sadece solup belirmez.
     *
     * <p>Yukseklik olculene kadar beklenir: olcum oncesi getHeight() sifir
     * doner ve satir hic gizlenmeden animasyon bosa gider.
     */
    static void revealLine(final View line, final long duration, final long delay,
                           final float endAlpha) {
        if (!enabled()) {
            line.setTranslationY(0f);
            line.setAlpha(endAlpha);
            return;
        }
        line.setAlpha(0f);
        line.post(new Runnable() {
            @Override public void run() {
                int h = line.getHeight();
                if (h <= 0) h = (int) (48 * line.getResources().getDisplayMetrics().density);
                line.setTranslationY(h);
                line.setAlpha(endAlpha);
                line.animate()
                        .translationY(0f)
                        .setStartDelay(scaled(delay))
                        .setDuration(scaled(duration))
                        .setInterpolator(EASE_LINE)
                        .start();
            }
        });
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
