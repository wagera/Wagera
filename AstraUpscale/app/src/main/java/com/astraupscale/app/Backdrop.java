package com.astraupscale.app;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.util.Random;

/**
 * Sayfanin arkasindaki sinematik zemin.
 *
 * <p>Duz bir renk yerine kosegen bir taban gecisi, iki yumusak isik
 * patlamasi ve bir kenar karartmasi. Referans tasarimdaki tam kenardan
 * kenara videonun karsiligi budur.
 *
 * <h3>Neden goruntu dosyasi degil</h3>
 * Once bu zemin bir PNG olarak uretilmisti. Gecislerin 8 bit panelde bant
 * yapmamasi icin uzerine film greni ekleniyordu; PNG bu grenle 293 KB
 * tutuyordu. WebP'ye cevrilince dosya 6 KB'ye dustu ama <b>gren yok
 * oldu</b>: olculen yerel gurultu 2.2'den 0.48'e indi ve 800 piksellik
 * dikey eksende yalnizca 33 benzersiz parlaklik seviyesi kaldi — yani
 * onlemeye calisilan bant geri geldi.
 *
 * <p>Bu yuzden zemin calisma aninda ciziliyor: APK'ya hicbir dosya
 * girmiyor, her ekran oranina tam oturuyor, esnetme bozulmasi olmuyor ve
 * gren gercekten piksel basina uretildigi icin bant kirilıyor.
 */
final class Backdrop extends Drawable {

    /** Gren karosunun kenari. Kucuk tutulur; ekranda tekrarlanir. */
    private static final int GRAIN_TILE = 128;
    /** Grenin gorunurlugu. Gozle secilmez, bandi kirmaya yeter. */
    private static final int GRAIN_ALPHA = 10;

    private final int baseTop, baseBottom;
    private final int glowCool, glowWarm;
    private final float vignette;
    /** Gren rengi: koyu zeminde beyaz benek, acik zeminde siyah. */
    private final int grainColor;

    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grain = new Paint();

    Backdrop(int baseTop, int baseBottom, int glowCool, int glowWarm, float vignette,
             int grainColor) {
        this.baseTop = baseTop;
        this.baseBottom = baseBottom;
        this.glowCool = glowCool;
        this.glowWarm = glowWarm;
        this.vignette = vignette;
        this.grainColor = grainColor;
        // Dither, gecislerin 8 bit'e yuvarlanirken halka birakmasini azaltir.
        base.setDither(true);
        glow.setDither(true);
        edge.setDither(true);
        // ALPHA_8 karo yalnizca ortuculuk tasir; rengi boya belirler.
        grain.setShader(new BitmapShader(grainTile(), Shader.TileMode.REPEAT,
                Shader.TileMode.REPEAT));
        grain.setColor(grainColor);
        grain.setAlpha(GRAIN_ALPHA);
    }

    /** Temanin koyu surumu. */
    static Backdrop dark() {
        return new Backdrop(
                Color.rgb(13, 14, 17), Color.rgb(4, 4, 5),
                Color.rgb(46, 56, 78), Color.rgb(44, 36, 32),
                0.30f, Color.WHITE);
    }

    /** Temanin acik surumu: ayni kompozisyon, ters yonde. */
    static Backdrop light() {
        return new Backdrop(
                Color.rgb(255, 255, 255), Color.rgb(234, 236, 242),
                Color.rgb(222, 228, 240), Color.rgb(236, 231, 226),
                0.06f, Color.BLACK);
    }

    /** Rastgele ama her acilista ayni gren karosu. */
    private static Bitmap grainTile() {
        Bitmap bmp = Bitmap.createBitmap(GRAIN_TILE, GRAIN_TILE, Bitmap.Config.ALPHA_8);
        Random rnd = new Random(20260823L);
        byte[] pixels = new byte[GRAIN_TILE * GRAIN_TILE];
        rnd.nextBytes(pixels);
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pixels));
        return bmp;
    }

    @Override protected void onBoundsChange(Rect b) {
        super.onBoundsChange(b);
        if (b.width() <= 0 || b.height() <= 0) return;
        float w = b.width(), h = b.height();

        // Taban: kosegen boyunca acilir (dikeyde agirlikli)
        base.setShader(new LinearGradient(w * 0.22f, 0f, w * 0.78f, h,
                baseTop, baseBottom, Shader.TileMode.CLAMP));

        // Kenar karartmasi: merkezde seffaf, koselerde koyu
        edge.setShader(new RadialGradient(w * 0.5f, h * 0.5f,
                Math.max(w, h) * 0.78f,
                new int[]{0x00000000, 0x00000000, (int) (vignette * 255) << 24},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        float w = b.width(), h = b.height();

        c.drawRect(b, base);

        // Ust soldaki genis soguk patlama
        glow.setShader(new RadialGradient(w * 0.18f, h * 0.10f, w * 1.05f,
                new int[]{(glowCool & 0x00FFFFFF) | 0x66000000, 0x00000000},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(b, glow);

        // Alt sagdaki dar sicak patlama
        glow.setShader(new RadialGradient(w * 0.92f, h * 0.74f, w * 0.78f,
                new int[]{(glowWarm & 0x00FFFFFF) | 0x4D000000, 0x00000000},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(b, glow);

        c.drawRect(b, edge);
        c.drawRect(b, grain);
    }

    @Override public void setAlpha(int alpha) { }

    @Override public void setColorFilter(ColorFilter filter) { }

    @Override public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
