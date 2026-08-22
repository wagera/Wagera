package com.opeyscaler.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;

import com.opeyscaler.engine.ImageWriter;
import com.opeyscaler.engine.JpegWriter;
import com.opeyscaler.engine.PixelSource;
import com.opeyscaler.engine.PngWriter;
import com.opeyscaler.engine.Progress;
import com.opeyscaler.engine.Upscaler;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Buyutme islemini on plan servisi olarak yurutur: kullanici baska uygulamaya
 * gecse de 16K'lik bir is yarida kesilmez.
 */
public final class UpscaleService extends Service {

    public static final String ACTION_START = "com.opeyscaler.app.START";
    public static final String ACTION_CANCEL = "com.opeyscaler.app.CANCEL";
    private static final String CHANNEL_ID = "upscale";
    private static final int NOTIFICATION_ID = 41;

    /** Kaynak fotografta izin verilen azami piksel sayisi (uzerinde alt orneklenir). */
    private static final long MAX_SOURCE_PIXELS = 60L * 1000 * 1000;

    /** Sinir agi modelleri icin kaynak siniri: uzerinde islem suresi kabul edilemez olur. */
    private static final long MAX_NEURAL_SOURCE_PIXELS = 12L * 1000 * 1000;

    /** Model dosemesi (kaynak piksel). Bellek ve hiz arasinda denge. */
    private static final int TILE_SIZE = 128;

    private Thread worker;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            UpscaleJob job = UpscaleJob.current();
            if (job != null) job.cancel();
            return START_NOT_STICKY;
        }
        final UpscaleJob job = UpscaleJob.current();
        if (job == null || worker != null) return START_NOT_STICKY;

        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification(0, job.stage));

        worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    process(job);
                } catch (Upscaler.CancelledException e) {
                    job.cancelled = true;
                } catch (Throwable t) {
                    // Iptal, bant isleyicisinden de gelebilir; bunu hata olarak gosterme.
                    if (!job.cancelled) job.error = describe(t);
                } finally {
                    job.finished = true;
                    job.notifyChanged();
                    stopForeground(true);
                    stopSelf();
                }
            }
        }, "opeyscaler-job");
        worker.start();
        return START_NOT_STICKY;
    }

    /** Olculen hizdan kalan sureyi tahmin eder. */
    private static String remaining(long startedAt, float fraction) {
        if (fraction < 0.02f || fraction >= 1f) return "";
        long elapsed = System.currentTimeMillis() - startedAt;
        long left = (long) (elapsed * (1 - fraction) / fraction);
        if (left < 5000) return "";
        long seconds = left / 1000;
        if (seconds < 90) return "   ~" + seconds + " sn";
        return "   ~" + ((seconds + 30) / 60) + " dk";
    }

    private static String describe(Throwable t) {
        if (t instanceof OutOfMemoryError) {
            return "Bellek yetmedi. Daha dusuk bir cozunurluk deneyin.";
        }
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
    }

    // ------------------------------------------------------------------ is akisi

    private void process(final UpscaleJob job) throws IOException {
        job.update(0f, "Fotograf okunuyor");

        final boolean neural = job.model.isNeural() && NativeSr.available();
        Bitmap bitmap = decodeSource(job.sourceUri,
                neural ? MAX_NEURAL_SOURCE_PIXELS : MAX_SOURCE_PIXELS);
        if (bitmap == null) throw new IOException("Fotograf cozulemedi");
        int orientation = readOrientation(job.sourceUri);
        PixelSource source = new BitmapPixelSource(bitmap, orientation);

        NativeSr sr = null;
        NativeSr sr2 = null;
        File intermediate = null;
        final Phase phase = new Phase(job);
        if (neural) {
            job.update(0f, "Model yukleniyor");
            sr = NativeSr.open(getAssets(), job.model, true, TILE_SIZE);
            if (sr != null) {
                job.usedGpu = sr.usingGpu();
                job.usedModel = job.model;
                job.usedStages = 1;
                job.update(0f, "Kaynak hazirlaniyor");
                final int srcW = source.width();
                final int srcH = source.height();
                sr.setSource(toRgbBytes(source), srcW, srcH);
                bitmap.recycle();
                bitmap = null;
                prepareGaps(sr, job, 1);
                source = neuralSource(sr, srcW, srcH, job, phase, 1);

                if (job.stages > 1) {
                    /*
                     * Ikinci asama: birinci asamanin ciktisi bellege sigmayacagi
                     * icin ham RGB olarak onbellek dosyasina akitilir, ikinci
                     * model bu dosyayi kaynak olarak okur.
                     */
                    final int midW = srcW * sr.scale();
                    final int midH = srcH * sr.scale();
                    final long needed = (long) midW * midH * 3L;
                    ensureDiskSpace(needed);
                    phase.begin(0f, 0.5f);
                    intermediate = new File(getCacheDir(), "opeyscaler_stage1.rgb");
                    writeRawRgb(source, intermediate, job, phase);
                    sr.close();
                    sr = null;

                    sr2 = NativeSr.open(getAssets(), job.model, true, TILE_SIZE);
                    if (sr2 == null || !sr2.setSourceFile(intermediate.getAbsolutePath(), midW, midH)) {
                        throw new IOException("Ikinci asama baslatilamadi");
                    }
                    job.usedStages = 2;
                    phase.begin(0.5f, 0.5f);
                    prepareGaps(sr2, job, 2);
                    source = neuralSource(sr2, midW, midH, job, phase, 2);
                }
            } else {
                // Model yuklenemedi: klasik hatta dusulur, kullaniciya bildirilir.
                job.usedModel = SrModel.LANCZOS;
                job.stage = "Model yuklenemedi, klasik yontem kullaniliyor";
            }
        } else {
            job.usedModel = SrModel.LANCZOS;
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String ext = job.jpeg ? "jpg" : "png";
        String name = "OpeyScaler_" + job.preset.label + "_" + stamp + "." + ext;
        String mime = job.jpeg ? "image/jpeg" : "image/png";

        Output output = openOutput(name, mime);
        boolean ok = false;
        try {
            Upscaler.Options opt = new Upscaler.Options();
            opt.targetWidth = job.targetWidth;
            opt.targetHeight = job.targetHeight;
            opt.sharpen = job.sharpen;
            opt.buildPreview = true;

            OutputStream os = new BufferedOutputStream(output.stream, 1 << 20);
            ImageWriter writer = job.jpeg ? new JpegWriter(os, job.quality) : new PngWriter(os, 4);

            final long startedAt = System.currentTimeMillis();
            Upscaler.Result result = Upscaler.run(source, opt, writer, new Progress() {
                private long lastNotify;

                @Override public void onProgress(float fraction, String stage) {
                    phase.report(fraction, stage + remaining(startedAt, fraction));
                    long now = System.currentTimeMillis();
                    if (now - lastNotify > 700) {
                        lastNotify = now;
                        notifyManager().notify(NOTIFICATION_ID,
                                buildNotification((int) (fraction * 100), stage));
                    }
                }

                @Override public boolean isCancelled() {
                    return job.cancelled;
                }
            });
            os.flush();
            os.close();
            ok = true;

            job.outWidth = result.width;
            job.outHeight = result.height;
            job.elapsedMillis = result.elapsedMillis;
            job.preview = result.preview;
            job.previewWidth = result.previewWidth;
            job.previewHeight = result.previewHeight;
            job.outputName = name;
            job.outputUri = output.publish(this);
            job.outputBytes = output.size(this);
            job.update(1f, "Tamamlandi");
        } finally {
            if (bitmap != null) bitmap.recycle();
            if (sr != null) sr.close();
            if (sr2 != null) sr2.close();
            if (intermediate != null) {
                //noinspection ResultOfMethodCallIgnored
                intermediate.delete();
            }
            if (!ok) output.discard(this);
        }
    }

    /**
     * Ilerleme cubugunu asamalara boler: cok asamali buyutmede birinci asama
     * cubugun ilk yarisini, ikinci asama ve olcekleme kalanini kullanir.
     */
    private static final class Phase {
        private final UpscaleJob job;
        private float base;
        private float span = 1f;

        Phase(UpscaleJob job) { this.job = job; }

        void begin(float base, float span) {
            this.base = base;
            this.span = span;
        }

        void report(float fraction, String stage) {
            job.update(base + span * Math.max(0f, Math.min(1f, fraction)), stage);
        }
    }

    /** SE modellerinde kuresel havuzlama degerlerini hesaplar. */
    private void prepareGaps(NativeSr sr, UpscaleJob job, int stageNo) throws IOException {
        for (int i = 0; i < sr.gapStages(); i++) {
            if (job.cancelled) throw new Upscaler.CancelledException();
            job.stage = "Model hazirlaniyor  " + (i + 1) + "/" + sr.gapStages();
            job.notifyChanged();
            if (!sr.prepareStage(i)) throw new IOException(sr.lastError());
        }
    }

    private NeuralPixelSource neuralSource(final NativeSr sr, int w, int h, final UpscaleJob job,
                                           final Phase phase, final int stageNo) throws IOException {
        final String prefix = job.stages > 1 ? (stageNo + ". asama  ") : "Model calisiyor  ";
        return new NeuralPixelSource(sr, w, h, sr.tileSize(), new NeuralPixelSource.BandListener() {
            @Override public void onBandDone(int done, int total) {
                job.stage = prefix + done + "/" + total;
            }

            @Override public boolean isCancelled() {
                return job.cancelled;
            }
        });
    }

    /** Ikinci asamanin ara goruntusu icin yeterli gecici alan var mi? */
    private void ensureDiskSpace(long needed) throws IOException {
        android.os.StatFs stat = new android.os.StatFs(getCacheDir().getAbsolutePath());
        long free = stat.getAvailableBytes();
        if (free < needed + (256L << 20)) {
            throw new IOException(String.format(java.util.Locale.US,
                    "Iki gecis icin %.1f GB gecici alan gerekiyor, %.1f GB bos yer var. "
                            + "Tek gecis secin veya yer acin.",
                    needed / 1073741824.0, free / 1073741824.0));
        }
    }

    /** Bir kaynagi ham RGB olarak dosyaya akitir (ikinci asamanin girdisi). */
    private void writeRawRgb(PixelSource src, File file, UpscaleJob job, Phase phase)
            throws IOException {
        final int w = src.width(), h = src.height();
        final int[] row = new int[w];
        final byte[] out = new byte[w * 3];
        OutputStream os = new BufferedOutputStream(new FileOutputStream(file), 1 << 20);
        try {
            for (int y = 0; y < h; y++) {
                if (job.cancelled) throw new Upscaler.CancelledException();
                src.readRow(y, row);
                if ((y & 63) == 0) phase.report(y / (float) h, job.stage);
                int i = 0;
                for (int x = 0; x < w; x++) {
                    int p = row[x];
                    out[i++] = (byte) (p >> 16);
                    out[i++] = (byte) (p >> 8);
                    out[i++] = (byte) p;
                }
                os.write(out);
            }
        } finally {
            os.close();
        }
    }

    /** Yonlendirilmis kaynagi modele verilecek duz RGB tamponuna cevirir. */
    private static byte[] toRgbBytes(PixelSource src) throws IOException {
        final int w = src.width(), h = src.height();
        final byte[] rgb;
        try {
            rgb = new byte[w * h * 3];
        } catch (OutOfMemoryError e) {
            throw new IOException("Fotograf model icin fazla buyuk");
        }
        final int[] row = new int[w];
        int i = 0;
        for (int y = 0; y < h; y++) {
            src.readRow(y, row);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                rgb[i++] = (byte) (p >> 16);
                rgb[i++] = (byte) (p >> 8);
                rgb[i++] = (byte) p;
            }
        }
        return rgb;
    }

    /** Cok buyuk fotograflari bellek sinirinda tutmak icin gerekirse alt orneklenir. */
    private Bitmap decodeSource(Uri uri, long maxPixels) throws IOException {
        ContentResolver cr = getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = cr.openInputStream(uri);
        try {
            BitmapFactory.decodeStream(in, null, bounds);
        } finally {
            close(in);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Fotograf cozulemedi");

        int sample = 1;
        while ((long) (bounds.outWidth / sample) * (bounds.outHeight / sample) > maxPixels) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        InputStream in2 = cr.openInputStream(uri);
        try {
            return BitmapFactory.decodeStream(in2, null, opts);
        } finally {
            close(in2);
        }
    }

    private int readOrientation(Uri uri) {
        InputStream in = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) return ExifInterface.ORIENTATION_NORMAL;
            ExifInterface exif = new ExifInterface(in);
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Throwable t) {
            return ExifInterface.ORIENTATION_NORMAL;
        } finally {
            close(in);
        }
    }

    private static void close(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ cikis dosyasi

    /** MediaStore (Android 10+) veya klasik dosya yoluyla cikis olusturur. */
    private static final class Output {
        OutputStream stream;
        Uri uri;         // MediaStore kaydi
        File file;       // eski surumlerde dogrudan dosya

        Uri publish(Context ctx) {
            if (uri != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                    ctx.getContentResolver().update(uri, cv, null, null);
                }
                return uri;
            }
            if (file != null) {
                MediaScannerConnection.scanFile(ctx, new String[]{file.getAbsolutePath()}, null, null);
                return Uri.fromFile(file);
            }
            return null;
        }

        long size(Context ctx) {
            if (file != null) return file.length();
            if (uri != null) {
                try {
                    android.database.Cursor c = ctx.getContentResolver().query(
                            uri, new String[]{MediaStore.Images.Media.SIZE}, null, null, null);
                    if (c != null) {
                        try {
                            if (c.moveToFirst()) return c.getLong(0);
                        } finally {
                            c.close();
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            return 0;
        }

        void discard(Context ctx) {
            try {
                if (stream != null) stream.close();
            } catch (IOException ignored) {
            }
            if (uri != null) {
                try {
                    ctx.getContentResolver().delete(uri, null, null);
                } catch (Throwable ignored) {
                }
            } else if (file != null) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private Output openOutput(String name, String mime) throws IOException {
        Output out = new Output();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, mime);
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OpeyScaler");
            cv.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("Galeriye kayit acilamadi");
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new IOException("Cikis dosyasi acilamadi");
            out.uri = uri;
            out.stream = os;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "OpeyScaler");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File file = new File(dir, name);
            out.file = file;
            out.stream = new FileOutputStream(file);
        }
        return out;
    }

    // ------------------------------------------------------------------ bildirim

    private NotificationManager notifyManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            notifyManager().createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(int percent, String stage) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle(getString(R.string.notification_title))
                .setContentText(stage)
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setOngoing(true)
                .setContentIntent(pi)
                .setProgress(100, percent, false);
        return b.build();
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, UpscaleService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }
}
