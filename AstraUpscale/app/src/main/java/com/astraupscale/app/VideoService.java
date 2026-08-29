package com.astraupscale.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;

import com.astraupscale.engine.Upscaler;

/**
 * Video buyutme isini on plan servisi olarak yurutur.
 *
 * <p>Fotograf isinden ayri bir servis olmasinin sebebi sure: bir fotograf
 * dakikalar surer, bir video saatler. Ikisinin ayni servisi paylasmasi,
 * kullanicinin bir fotografi buyuturken calisan videoyu durdurmasi anlamina
 * gelirdi. Ayri servisler ayni anda calisabilir.
 */
public final class VideoService extends Service {

    public static final String ACTION_START = "com.astraupscale.app.VIDEO_START";
    public static final String ACTION_CANCEL = "com.astraupscale.app.VIDEO_CANCEL";

    private static final String CHANNEL_PROGRESS = "upscale";
    private static final String CHANNEL_RESULT = "upscale_result";

    /** Fotograf isiyle catismamasi icin ayri kimlikler. */
    private static final int NOTIFICATION_ID = 51;
    private static final int NOTIFICATION_RESULT_ID = 52;

    private Thread worker;
    /** Bildirimi saniyede bir tazeleyen is parcacigi. */
    private Thread ticker;
    private android.os.PowerManager.WakeLock wakeLock;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            VideoJob job = VideoJob.current();
            if (job != null) job.cancel();
            return START_NOT_STICKY;
        }
        final VideoJob job = VideoJob.current();
        if (job == null || worker != null) return START_NOT_STICKY;

        createChannel();
        startForeground(NOTIFICATION_ID, buildProgress(job, 0));
        acquireWakeLock();

        worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    new VideoTranscoder(getApplicationContext(), job).run();
                } catch (Upscaler.CancelledException e) {
                    job.cancelled = true;
                } catch (Throwable t) {
                    if (!job.cancelled) job.error = describe(t);
                } finally {
                    job.finished = true;
                    Reporter.videoFinished(getApplicationContext(), job);
                    job.notifyChanged();
                    releaseWakeLock();
                    // Once tazeleyiciyi durdur: yoksa stopForeground'dan
                    // sonra bir kez daha bildirim gonderip ilerleme
                    // satirini ekranda asili birakabilir.
                    stopTicker();
                    stopForeground(true);
                    postOutcome(job);
                    stopSelf();
                }
            }
        }, "astraupscale-video");
        worker.start();

        // Bildirimi calisirken de tazele: is saatlerce surebilir ve
        // kullanicinin gordugu tek gosterge bu satirdir.
        ticker = new Thread(new Runnable() {
            @Override public void run() {
                while (!job.finished) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (job.finished) return;
                    try {
                        notifyManager().notify(NOTIFICATION_ID,
                                buildProgress(job, (int) (job.progress * 100)));
                    } catch (Throwable ignored) {
                        return;
                    }
                }
            }
        }, "astraupscale-video-notify");
        ticker.start();
        return START_NOT_STICKY;
    }

    private void stopTicker() {
        Thread t = ticker;
        ticker = null;
        if (t == null) return;
        t.interrupt();
        try {
            t.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String describe(Throwable t) {
        if (t instanceof OutOfMemoryError) return getString(R.string.v_error_memory);
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
    }

    private void acquireWakeLock() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "AstraUpscale:video");
            wakeLock.setReferenceCounted(false);
            // Video isi fotograftan cok daha uzun surebilir.
            wakeLock.acquire(12L * 60 * 60 * 1000);
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

    // ------------------------------------------------------------------ bildirim

    private NotificationManager notifyManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel progress = new NotificationChannel(CHANNEL_PROGRESS,
                getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW);
        progress.setShowBadge(false);
        progress.setSound(null, null);
        progress.enableVibration(false);
        NotificationChannel result = new NotificationChannel(CHANNEL_RESULT,
                getString(R.string.channel_result), NotificationManager.IMPORTANCE_DEFAULT);
        result.setShowBadge(true);
        notifyManager().createNotificationChannel(progress);
        notifyManager().createNotificationChannel(result);
    }

    private PendingIntent openApp() {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 10, open, pendingFlags(false));
    }

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

    private Notification buildProgress(VideoJob job, int percent) {
        Intent cancel = new Intent(this, VideoService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelPending = PendingIntent.getService(this, 11, cancel,
                pendingFlags(false));

        String text = job.stage;
        if (job.remainingMillis > 0) {
            text = text + "   " + remaining(job.remainingMillis);
        }
        return builder(CHANNEL_PROGRESS)
                .setContentTitle(getString(R.string.v_notif_running_title, job.preset.label))
                .setContentText(percent > 0
                        ? getString(R.string.notif_running_text, percent, text) : text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp())
                .setProgress(100, percent, percent <= 0)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_action_cancel),
                        getString(R.string.cancel), cancelPending).build())
                .build();
    }

    private static String remaining(long millis) {
        long seconds = millis / 1000;
        if (seconds < 90) return "~" + seconds + " sn";
        long minutes = (seconds + 30) / 60;
        if (minutes < 90) return "~" + minutes + " dk";
        return "~" + ((minutes + 30) / 60) + " sa";
    }

    private void postOutcome(VideoJob job) {
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

        String detail = job.frameSequence
                ? getString(R.string.v_notif_done_sequence, job.framesDone,
                        job.outWidth, job.outHeight, formatBytes(job.outputBytes))
                : getString(R.string.v_notif_done_text, job.outWidth, job.outHeight,
                        job.framesDone, formatBytes(job.outputBytes),
                        job.elapsedMillis / 60000.0);

        Notification.Builder b = builder(CHANNEL_RESULT)
                .setContentTitle(getString(R.string.v_notif_done_title))
                .setContentText(detail)
                .setStyle(new Notification.BigTextStyle().bigText(detail))
                .setAutoCancel(true);

        if (job.outputUri != null && !job.frameSequence) {
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(job.outputUri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            b.setContentIntent(PendingIntent.getActivity(this, 12, view, pendingFlags(false)));

            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("video/mp4")
                    .putExtra(Intent.EXTRA_STREAM, job.outputUri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            PendingIntent sharePending = PendingIntent.getActivity(this, 13,
                    Intent.createChooser(share, getString(R.string.share)), pendingFlags(true));
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

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, VideoService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }
}
