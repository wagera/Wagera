package com.astraupscale.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.astraupscale.engine.Viewport;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Oncesi/sonrasi karsilastirma gorunumu.
 *
 * <p>Bir buyutme uygulamasinin butun degeri, ciplak gozle sonradan
 * bakildiginda goze carpan detaydadir. Sonucu kucuk bir kucuk resim olarak
 * gostermek, o degeri gorunmez birakir. Burada iki goruntu ayni bakis
 * penceresini paylasir: bolme cizgisinin solunda kaynak, saginda sonuc.
 * Yakinlastirma ve kaydirma ikisine birden uygulanir, yani her an ayni
 * bolge karsilastirilir.
 *
 * <h3>Iki goruntu, iki farkli yontem</h3>
 * Sonuc gigapiksel olabilir; bellege sigmaz. Bu yuzden yalnizca ekranda
 * gorunen bolge {@link BitmapRegionDecoder} ile okunur ve yakinlik
 * degistikce yeniden okunur.
 *
 * <p>Kaynak ise en fazla birkac megapikseldir ve bir kez cozulup bellekte
 * tutulur. Kaynak tarafi zaten buyutulmus gosterilecegi icin bolge okumaya
 * gerek yoktur: "oncesi" demek, orijinali ayni olcude buyutulmus haliyle
 * gormek demektir.
 *
 * <h3>Hizalama</h3>
 * Motor cikisi EXIF donusunu uygulayarak yazar
 * ({@code BitmapPixelSource(bitmap, orientation)}), yani sonuc dosyasi
 * dogru yondedir. Kaynak burada ayni donusle cozulur; aksi halde EXIF
 * tasiyan bir fotografta iki taraf birbirine gore 90 derece kayardi.
 */
final class CompareView extends View {

    /** Bolme cizgisinin kalinligi ve tutamagin yaricapi (dp). */
    private static final float DIVIDER_DP = 1.5f;
    private static final float HANDLE_DP = 17f;
    /** En fazla yakinlastirma: sonucun 1 pikseli ekranin bu kadar pikseli. */
    private static final float MAX_ZOOM = 6f;

    private final float density;

    /** Sonuc: yalnizca gorunen bolge okunur. */
    private BitmapRegionDecoder resultDecoder;
    private int resultWidth, resultHeight;

    /** Kaynak: bir kez cozulur, EXIF donusu uygulanmis halde. */
    private Bitmap sourceBitmap;

    /**
     * Bakis penceresi, SONUC piksel uzayinda.
     *
     * <p>Matematik ayri bir sinifta: yakinlastirma ve kaydirma gozle
     * denetlenemeyecek kadar hataya acik, bu ortamda uygulama da
     * calistirilamiyor. {@link Viewport} Android'e bagli olmadigi icin
     * masaustunde dogrudan sinaniyor (tools/desktop/ViewportTest.java);
     * boylece burada ikinci bir kopya tutulmuyor.
     *
     * <p>Kaynak tarafi bu ayni pencereyi kendi olcegine bolerek kullanir,
     * yani iki taraf tanim geregi hizali kalir.
     */
    private Viewport viewport;

    /** Bolme cizgisinin yatay konumu, 0..1. */
    private float split = 0.5f;
    private boolean draggingHandle;

    private Bitmap resultTile;
    private Rect resultTileRect;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Ucusan istegin siralamasi: gec donen eski bir karo yenisini ezmesin. */
    private int requestSerial;

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    private Runnable onZoomChanged;

    CompareView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;

        linePaint.setColor(Color.WHITE);
        linePaint.setStrokeWidth(DIVIDER_DP * density);
        handlePaint.setColor(Color.WHITE);
        handleStroke.setColor(0x66000000);
        handleStroke.setStyle(Paint.Style.STROKE);
        handleStroke.setStrokeWidth(density);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                if (viewport == null) return true;
                zoomTo(viewport.zoom() * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                              float dx, float dy) {
                if (viewport == null) return true;
                viewport.panBy(dx, dy);
                requestTile();
                invalidate();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                // Cift dokunus: tumunu goster ile 1:1 arasinda gidip gelir.
                // 1:1, sonucun bir pikselinin ekranin bir pikseli olmasidir —
                // buyutmenin gercekten ne yaptigi ancak orada gorunur.
                if (viewport == null) return true;
                boolean zoomedIn = viewport.zoom() > viewport.fitZoom() * 1.5f;
                zoomTo(zoomedIn ? viewport.fitZoom() : 1f, e.getX(), e.getY());
                return true;
            }
        });
    }

    void setOnZoomChanged(Runnable listener) {
        this.onZoomChanged = listener;
    }

    /**
     * Iki goruntuyu yukler.
     *
     * @return yuklenebildi mi; false ise cagiran taraf gorunumu acmamali
     */
    boolean load(Context ctx, Uri sourceUri, Uri resultUri, int orientation) {
        close();
        try {
            InputStream in = ctx.getContentResolver().openInputStream(resultUri);
            if (in == null) return false;
            try {
                resultDecoder = newDecoder(in);
            } finally {
                in.close();
            }
            resultWidth = resultDecoder.getWidth();
            resultHeight = resultDecoder.getHeight();

            sourceBitmap = decodeSource(ctx, sourceUri, orientation);
            if (sourceBitmap == null || resultWidth <= 0 || resultHeight <= 0) return false;

            viewport = new Viewport(resultWidth, resultHeight, MAX_ZOOM);
            if (getWidth() > 0) viewport.setViewSize(getWidth(), getHeight());
            return true;
        } catch (Throwable t) {
            close();
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static BitmapRegionDecoder newDecoder(InputStream in) throws Exception {
        // newInstance(InputStream, boolean) API 31'de kullanimdan kalkti;
        // yerine gelen tek argümanli surum 31'den once yok.
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            return BitmapRegionDecoder.newInstance(in);
        }
        return BitmapRegionDecoder.newInstance(in, false);
    }

    /**
     * Kaynagi bellege sigacak olcekte cozer ve EXIF donusunu uygular.
     *
     * <p>Kaynak tarafi her zaman buyutulmus gosterilir, yani orijinal
     * cozunurlugun tamamina gerek yoktur; 8 MP fazlasiyla yeter ve
     * telefonun bellegini zorlamaz.
     */
    private static Bitmap decodeSource(Context ctx, Uri uri, int orientation) {
        final int maxPixels = 8_000_000;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream probe = ctx.getContentResolver().openInputStream(uri);
            if (probe == null) return null;
            try {
                BitmapFactory.decodeStream(probe, null, bounds);
            } finally {
                probe.close();
            }
            int sample = 1;
            while ((long) (bounds.outWidth / sample) * (bounds.outHeight / sample) > maxPixels) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            Bitmap raw;
            try {
                raw = BitmapFactory.decodeStream(in, null, opts);
            } finally {
                in.close();
            }
            return raw == null ? null : MainActivity.orient(raw, orientation);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (viewport != null && w > 0) {
            viewport.setViewSize(w, h);
            requestTile();
        }
    }

    private void zoomTo(float target, float focusX, float focusY) {
        if (viewport == null || !viewport.zoomTo(target, focusX, focusY)) return;
        requestTile();
        invalidate();
        if (onZoomChanged != null) onZoomChanged.run();
    }

    /** Su anki yakinligin kac kat oldugu; baslikta gosterilir. */
    float zoomFactor() {
        return viewport == null ? 1f : viewport.factor();
    }

    boolean isOneToOne() {
        return viewport != null && viewport.isOneToOne();
    }

    void toggleOneToOne() {
        if (viewport == null) return;
        zoomTo(isOneToOne() ? viewport.fitZoom() : 1f, getWidth() / 2f, getHeight() / 2f);
    }

    // ---------------------------------------------------------------- cizim

    @Override protected void onDraw(Canvas canvas) {
        if (sourceBitmap == null || viewport == null || resultWidth <= 0) return;
        int w = getWidth(), h = getHeight();
        float splitX = split * w;
        final float viewX = viewport.x(), viewY = viewport.y(), zoom = viewport.zoom();

        // ── Sol: kaynak, sonucla ayni olcuye buyutulmus ──────────────
        canvas.save();
        canvas.clipRect(0, 0, splitX, h);
        // Kaynak, sonucun kapladigi dikdortgenin tamamina gerilir: "oncesi"
        // demek, orijinali sonucla ayni olcude buyutulmus gormek demektir.
        RectF dst = new RectF(-viewX * zoom, -viewY * zoom,
                (resultWidth - viewX) * zoom, (resultHeight - viewY) * zoom);
        canvas.drawBitmap(sourceBitmap, null, dst, bitmapPaint);
        canvas.restore();

        // ── Sag: sonuc, yalnizca okunmus bolge ───────────────────────
        canvas.save();
        canvas.clipRect(splitX, 0, w, h);
        if (resultTile != null && resultTileRect != null) {
            RectF tileDst = new RectF(
                    (resultTileRect.left - viewX) * zoom,
                    (resultTileRect.top - viewY) * zoom,
                    (resultTileRect.right - viewX) * zoom,
                    (resultTileRect.bottom - viewY) * zoom);
            canvas.drawBitmap(resultTile, null, tileDst, bitmapPaint);
        } else {
            // Karo henuz hazir degil: bosluk yerine kaynagi goster, boylece
            // kaydirirken siyah bir dikdortgen yerine bulanik bir onizleme olur
            canvas.drawBitmap(sourceBitmap, null, dst, bitmapPaint);
        }
        canvas.restore();

        // ── Bolme cizgisi ve tutamagi ────────────────────────────────
        canvas.drawLine(splitX, 0, splitX, h, linePaint);
        float r = HANDLE_DP * density;
        canvas.drawCircle(splitX, h / 2f, r, handlePaint);
        canvas.drawCircle(splitX, h / 2f, r, handleStroke);
        // Tutamagin icindeki iki ok.
        //
        // Oklar DISA bakar. Ice bakan oklar ("> <") kapatma ya da daraltma
        // anlamina gelir; buradaki tutamak surukleniyor, yani isaret ayrilma
        // yonunu gostermeli.
        float a = r * 0.42f;
        linePaint.setStrokeWidth(2f * density);
        int saved = linePaint.getColor();
        linePaint.setColor(0xFF101215);
        canvas.drawLine(splitX - a * 0.4f, h / 2f - a * 0.55f, splitX - a, h / 2f, linePaint);
        canvas.drawLine(splitX - a * 0.4f, h / 2f + a * 0.55f, splitX - a, h / 2f, linePaint);
        canvas.drawLine(splitX + a * 0.4f, h / 2f - a * 0.55f, splitX + a, h / 2f, linePaint);
        canvas.drawLine(splitX + a * 0.4f, h / 2f + a * 0.55f, splitX + a, h / 2f, linePaint);
        linePaint.setColor(saved);
        linePaint.setStrokeWidth(DIVIDER_DP * density);
    }

    // ---------------------------------------------------------------- dokunma

    @Override public boolean onTouchEvent(MotionEvent event) {
        float splitX = split * getWidth();
        float grab = HANDLE_DP * density * 1.6f;

        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && Math.abs(event.getX() - splitX) < grab) {
            draggingHandle = true;
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (draggingHandle) {
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                split = Math.max(0.04f, Math.min(0.96f, event.getX() / getWidth()));
                invalidate();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                draggingHandle = false;
            }
            return true;
        }

        boolean handled = scaleDetector.onTouchEvent(event);
        handled |= gestureDetector.onTouchEvent(event);
        return handled || super.onTouchEvent(event);
    }

    // ---------------------------------------------------------------- karo okuma

    /**
     * Gorunen bolgeyi arka planda okur.
     *
     * <p>Her istek bir sira numarasi tasir: kullanici hizli kaydirirken
     * onceki istek sonradan donebilir, o karo artik yanlis bolgeyi gosterir
     * ve atilir.
     */
    private void requestTile() {
        if (resultDecoder == null || viewport == null || getWidth() <= 0) return;

        final int serial = ++requestSerial;
        int[] r = viewport.visibleRegion();
        final Rect region = new Rect(r[0], r[1], r[2], r[3]);
        if (region.width() <= 0 || region.height() <= 0) return;
        final int sampleSize = viewport.sampleSize();

        io.execute(new Runnable() {
            @Override public void run() {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sampleSize;
                final Bitmap bmp;
                try {
                    bmp = resultDecoder.decodeRegion(region, opts);
                } catch (Throwable t) {
                    return;
                }
                if (bmp == null) return;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (serial != requestSerial) {      // gecikmis istek
                            bmp.recycle();
                            return;
                        }
                        if (resultTile != null) resultTile.recycle();
                        resultTile = bmp;
                        resultTileRect = region;
                        invalidate();
                    }
                });
            }
        });
    }

    void close() {
        requestSerial++;          // ucusan istekler artik gecersiz
        if (resultDecoder != null) {
            resultDecoder.recycle();
            resultDecoder = null;
        }
        if (resultTile != null) {
            resultTile.recycle();
            resultTile = null;
        }
        if (sourceBitmap != null) {
            sourceBitmap.recycle();
            sourceBitmap = null;
        }
        viewport = null;
    }

    /**
     * Gorunum tumden birakilirken cagrilir.
     *
     * <p>{@link #close()} yalnizca goruntuleri birakir; gorunum yeniden
     * yuklenebilir. Yurutucu ise burada kapanir, cunku kapandiktan sonra
     * yeni is kabul etmez.
     */
    void release() {
        close();
        io.shutdownNow();
    }
}
