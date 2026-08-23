package com.astraupscale.app;

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
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;

import com.astraupscale.engine.Denoiser;
import com.astraupscale.engine.ImageWriter;
import com.astraupscale.engine.JpegWriter;
import com.astraupscale.engine.PixelSource;
import com.astraupscale.engine.PngWriter;
import com.astraupscale.engine.Progress;
import com.astraupscale.engine.ThreadPool;
import com.astraupscale.engine.Upscaler;

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

    public static final String ACTION_START = "com.astraupscale.app.START";
    public static final String ACTION_CANCEL = "com.astraupscale.app.CANCEL";
    /**
     * Iki ayri kanal.
     *
     * <p>Devam eden is sessiz olmali: uzun suren bir islemin her yuzde
     * degisiminde ses cikarmasi rahatsiz edicidir, o yuzden IMPORTANCE_LOW.
     * Sonuc ise kullanicinin bekledigi haberdir ve uygulama arkaplandayken
     * gelir; onun kendi kanali var ve varsayilan onemde. Ikisi ayni kanalda
     * olsaydi kullanici birini susturmak icin digerini de susturmak
     * zorunda kalirdi.
     */
    private static final String CHANNEL_PROGRESS = "upscale";
    private static final String CHANNEL_RESULT = "upscale_result";

    /** Devam eden is bildirimi; sabit kimlik, yerinde guncellenir. */
    private static final int NOTIFICATION_ID = 41;
    /** Sonuc bildirimi; ilerleme bildiriminin yerini almaz, yanina gelir. */
    private static final int NOTIFICATION_RESULT_ID = 42;

    /** Kaynak fotografta izin verilen azami piksel sayisi (uzerinde alt orneklenir). */
    private static final long MAX_SOURCE_PIXELS = 60L * 1000 * 1000;

    /** Sinir agi modelleri icin kaynak siniri: uzerinde islem suresi kabul edilemez olur. */
    private static final long MAX_NEURAL_SOURCE_PIXELS = 12L * 1000 * 1000;

    /** Model dosemesi (kaynak piksel). Bellek ve hiz arasinda denge. */
    private static final int TILE_SIZE = 128;

    private Thread worker;
    private android.os.PowerManager.WakeLock wakeLock;

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
        startForeground(NOTIFICATION_ID, buildProgress(job, 0));
        acquireWakeLock();

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
                    // Rapor servisten gonderilir; uygulama kapali olsa da kaydedilir.
                    Reporter.jobFinished(getApplicationContext(), job);
                    job.notifyChanged();
                    releaseWakeLock();
                    stopForeground(true);
                    // Sonucu stopForeground'dan SONRA bildir: once bildirsek
                    // stopForeground(true) onu da kaldirirdi.
                    postOutcome(job);
                    stopSelf();
                }
            }
        }, "astraupscale-job");
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

    /**
     * Ekran kapandiginda islemcinin uyumamasi icin kismi uyanik kilit.
     * Ekrani acik tutmaz; yalnizca isin arka planda surmesini saglar.
     */
    private void acquireWakeLock() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "AstraUpscale:upscale");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(6L * 60 * 60 * 1000);   // en fazla 6 saat
        } catch (Throwable ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        } finally {
            wakeLock = null;
        }
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

        /*
         * Cok yuksek buyutmelerde kaynaktaki gurultu de buyutulur; once
         * kenarlari koruyan bir filtreyle temizlenir, sonra buyutulur.
         */
        ThreadPool denoisePool = null;
        if (job.denoise) {
            job.update(0f, "Gurultu temizleniyor");
            job.usedDenoise = true;
            denoisePool = new ThreadPool(job.threads);
            source = new Denoiser(source, 0.6f, denoisePool);
        }

        NativeSr sr = null;
        NativeSr sr2 = null;
        File intermediate = null;
        final Phase phase = new Phase(job);
        if (neural) {
            job.update(0f, "Model yukleniyor");
            sr = NativeSr.open(getAssets(), job.model, true, job.tileSize);
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
                    intermediate = new File(getCacheDir(), "astraupscale_stage1.rgb");
                    writeRawRgb(source, intermediate, job, phase);
                    sr.close();
                    sr = null;

                    sr2 = NativeSr.open(getAssets(), job.model, true, job.tileSize);
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
        String name = "AstraUpscale_" + job.preset.label + "_" + stamp + "." + ext;
        String mime = job.jpeg ? "image/jpeg" : "image/png";

        Output output = openOutput(name, mime);
        boolean ok = false;
        try {
            Upscaler.Options opt = new Upscaler.Options();
            opt.targetWidth = job.targetWidth;
            opt.targetHeight = job.targetHeight;
            opt.sharpen = job.sharpen;
            opt.buildPreview = true;
            opt.threads = job.threads;

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
                        job.stage = stage;
                        notifyManager().notify(NOTIFICATION_ID,
                                buildProgress(job, (int) (fraction * 100)));
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
            if (denoisePool != null) denoisePool.shutdown();
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
                // Sakin seviyede cihazin soguyabilmesi icin kisa mola
                if (job.breatherMillis > 0) {
                    try {
                        Thread.sleep(job.breatherMillis);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
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
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AstraUpscale");
            cv.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("Galeriye kayit acilamadi");
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new IOException("Cikis dosyasi acilamadi");
            out.uri = uri;
            out.stream = os;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "AstraUpscale");
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel progress = new NotificationChannel(CHANNEL_PROGRESS,
                getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW);
        progress.setDescription(getString(R.string.channel_progress_desc));
        progress.setShowBadge(false);
        progress.setSound(null, null);
        progress.enableVibration(false);

        NotificationChannel result = new NotificationChannel(CHANNEL_RESULT,
                getString(R.string.channel_result), NotificationManager.IMPORTANCE_DEFAULT);
        result.setDescription(getString(R.string.channel_result_desc));
        result.setShowBadge(true);

        notifyManager().createNotificationChannel(progress);
        notifyManager().createNotificationChannel(result);
    }

    /** Uygulamayi acan niyet. */
    private PendingIntent openApp() {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, open, pendingFlags(false));
    }

    /**
     * PendingIntent bayraklari.
     *
     * <p>Android 12'den itibaren her PendingIntent acikca degismez ya da
     * degisebilir isaretlenmek zorunda; isaretlenmezse uygulama coker.
     * Paylasim niyeti disari veri tasidigi icin MUTABLE olmali, digerleri
     * IMMUTABLE.
     */
    private int pendingFlags(boolean mutable) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= mutable ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private Notification.Builder builder(String channel) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, channel)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_astra);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            b.setColor(getColor(R.color.notification_accent));
        }
        return b;
    }

    /**
     * Isin bittigini bildirir: basari, hata ya da iptal.
     *
     * <p>Iptalde hicbir sey bildirilmez — kullanici zaten kendisi iptal
     * etti, ona bunu haber vermek gurultudur.
     */
    private void postOutcome(UpscaleJob job) {
        if (job.cancelled) return;

        if (job.error != null) {
            Notification n = builder(CHANNEL_RESULT)
                    .setContentTitle(getString(R.string.notif_failed_title))
                    .setContentText(job.error)
                    .setStyle(new Notification.BigTextStyle().bigText(job.error))
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .build();
            notifyManager().notify(NOTIFICATION_RESULT_ID, n);
            return;
        }

        String detail = getString(R.string.notif_done_text,
                job.outWidth, job.outHeight,
                job.outWidth * (long) job.outHeight / 1e6,
                formatBytes(job.outputBytes),
                job.elapsedMillis / 1000.0);

        Notification.Builder b = builder(CHANNEL_RESULT)
                .setContentTitle(getString(R.string.notif_done_title))
                .setContentText(detail)
                .setStyle(new Notification.BigTextStyle().bigText(detail))
                .setAutoCancel(true);

        if (job.outputUri != null) {
            String mime = job.jpeg ? "image/jpeg" : "image/png";
            // Dokununca sonucu goster
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(job.outputUri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            b.setContentIntent(PendingIntent.getActivity(this, 1, view, pendingFlags(false)));

            // Paylas eylemi: disari veri tasidigi icin degisebilir olmali
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, job.outputUri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            PendingIntent sharePending = PendingIntent.getActivity(this, 2,
                    Intent.createChooser(share, getString(R.string.share)),
                    pendingFlags(true));
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_action_share),
                    getString(R.string.share), sharePending).build());
        } else {
            b.setContentIntent(openApp());
        }
        notifyManager().notify(NOTIFICATION_RESULT_ID, b.build());
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1073741824L) {
            return String.format(java.util.Locale.getDefault(), "%.1f GB", bytes / 1073741824.0);
        }
        return String.format(java.util.Locale.getDefault(), "%.0f MB", bytes / 1048576.0);
    }

    /**
     * Devam eden isin bildirimi.
     *
     * <p>Basligi hedef cozunurlugu soyler ("8K'ya buyutuluyor"), yani
     * kullanici bildirim golgesine bakinca hangi isin surdugunu bilir.
     * Yuzde ayri bir alt metinde durur; setOnlyAlertOnce sayesinde her
     * guncellemede yeniden ses cikarmaz.
     */
    private Notification buildProgress(UpscaleJob job, int percent) {
        Intent cancel = new Intent(this, UpscaleService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelPending = PendingIntent.getService(this, 3, cancel,
                pendingFlags(false));

        String title = job.preset != null
                ? getString(R.string.notif_running_title, job.preset.label)
                : getString(R.string.notification_title);

        return builder(CHANNEL_PROGRESS)
                .setContentTitle(title)
                .setContentText(percent > 0
                        ? getString(R.string.notif_running_text, percent, job.stage)
                        : job.stage)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp())
                .setProgress(100, percent, percent <= 0)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_action_cancel),
                        getString(R.string.cancel), cancelPending).build())
                .build();
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
