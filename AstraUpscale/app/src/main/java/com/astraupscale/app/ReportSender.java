package com.astraupscale.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;

/**
 * Kuyruktaki kayitlari Discord'a gonderir.
 *
 * Uc katmanli dayaniklilik:
 *  1. Uygulama acikken gonderim hemen denenir; basarisiz olursa 10 saniyede
 *     bir yeniden denenir.
 *  2. Uygulama kapaliyken is, ag kosuluna bagli bir {@link JobService} ile
 *     yeniden planlanir; boylece internet gelince kendiliginden calisir.
 *  3. Kuyruk diskte durdugu icin cihaz yeniden baslasa da kayitlar kaybolmaz.
 */
public final class ReportSender {

    /** Kullanici istekleri. */
    private static final String WEBHOOK_FEEDBACK =
            "https://discord.com/api/webhooks/1540745290980524082/"
                    + "BbBKWHiSX5AFhHkJSUKlbDgsfTnV2LfHfvC70AMidQhHuKH9x_5TYBV_w2HZqS3sydzf";

    /** Diger her sey: is sonuclari, hatalar, acilis kayitlari. */
    private static final String WEBHOOK_EVENTS =
            "https://discord.com/api/webhooks/1540745974354546758/"
                    + "p6NRugDKgLKEnPMzOeLAsw7Q8sLGSSxdhTyLLsHyTs3NgglJvVPOEeJCMIkexF3AwKQV";

    /** Basarisiz gonderimden sonra beklenecek sure. */
    public static final long RETRY_MILLIS = 10_000L;

    private static final int JOB_ID = 8801;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static volatile Thread worker;

    private ReportSender() { }

    /**
     * Gonderimi baslatir. Zaten calisiyorsa hicbir sey yapmaz.
     * Uygulama kapansa bile isin surmesi icin ayrica is planlayiciya kaydeder.
     */
    public static synchronized void kick(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        schedule(app);
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(new Runnable() {
            @Override public void run() {
                drain(app, Long.MAX_VALUE);
            }
        }, "astra-report");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Kuyrugu bosaltmaya calisir. Basarisiz olursa {@link #RETRY_MILLIS} kadar
     * bekleyip yeniden dener; {@code deadline} dolunca birakir.
     *
     * @return kuyruk tamamen bosaldiysa true
     */
    static boolean drain(Context ctx, long budgetMillis) {
        final long until = System.currentTimeMillis() + budgetMillis;
        while (true) {
            List<ReportQueue.Entry> entries = ReportQueue.all(ctx);
            if (entries.isEmpty()) return true;

            int sent = 0;
            boolean failed = false;
            for (ReportQueue.Entry e : entries) {
                if (post(e)) {
                    sent++;
                } else {
                    failed = true;
                    break;
                }
            }
            if (sent > 0) ReportQueue.removeFirst(ctx, sent);
            if (!failed) return true;

            if (System.currentTimeMillis() + RETRY_MILLIS > until) return false;
            try {
                Thread.sleep(RETRY_MILLIS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Tek kaydi gonderir; agi kullanamazsa false doner. */
    private static boolean post(ReportQueue.Entry entry) {
        HttpURLConnection conn = null;
        try {
            String url = Reporter.TARGET_FEEDBACK.equals(entry.target)
                    ? WEBHOOK_FEEDBACK : WEBHOOK_EVENTS;
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", "AstraUpscale");

            byte[] body = discordBody(entry).toString().getBytes(UTF8);
            conn.setFixedLengthStreamingMode(body.length);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            // 2xx basarili; 4xx'te kayit gecersizdir, tekrar denemek anlamsiz.
            if (code >= 200 && code < 300) return true;
            return code >= 400 && code < 500 && code != 429;
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Discord'un bekledigi govde: baslikli bir alan listesi. */
    private static JSONObject discordBody(ReportQueue.Entry entry) throws Exception {
        JSONObject p = entry.payload;
        boolean feedback = Reporter.TARGET_FEEDBACK.equals(entry.target);

        JSONObject embed = new JSONObject();
        embed.put("title", feedback
                ? "Kullanici istegi · " + p.optString("kind", "?")
                : "Olay · " + p.optString("event", "?"));
        embed.put("color", feedback ? 0xF5F6F8 : 0x3A3E45);

        StringBuilder desc = new StringBuilder();
        if (feedback) {
            desc.append(p.optString("message", "")).append("\n\n");
            String contact = p.optString("contact", "");
            if (contact.length() > 0) desc.append("**Iletisim:** ").append(contact).append('\n');
            desc.append("**Donanim:** ").append(p.optString("hardware", "?")).append('\n');
        } else {
            JSONObject d = p.optJSONObject("detail");
            if (d != null) {
                JSONArray keys = d.names();
                for (int i = 0; keys != null && i < keys.length(); i++) {
                    String k = keys.getString(i);
                    desc.append("**").append(k).append(":** ").append(d.get(k)).append('\n');
                }
            }
        }
        desc.append("**Uygulama:** ").append(p.optString("app", "?"))
                .append("  ·  ").append(p.optString("device", "?"))
                .append("  ·  ").append(p.optString("android", "?"))
                .append("  ·  ").append(p.optString("locale", "?"));
        embed.put("description", cut(desc.toString(), 4000));

        JSONObject body = new JSONObject();
        body.put("username", "AstraUpscale");
        body.put("embeds", new JSONArray().put(embed));
        // Gelen metinlerin kanalda kimseyi etiketlemesini engelle.
        body.put("allowed_mentions", new JSONObject().put("parse", new JSONArray()));
        return body;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ------------------------------------------------------------------ is planlayici

    /** Uygulama kapaliyken de gonderim yapilabilmesi icin isi planlar. */
    static void schedule(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, Job.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(RETRY_MILLIS, JobInfo.BACKOFF_POLICY_LINEAR)
                    .setPersisted(true)
                    .build();
            js.schedule(job);
        } catch (Throwable ignored) {
        }
    }

    /** Ag kosulu saglandiginda sistem tarafindan calistirilir. */
    public static final class Job extends JobService {

        private Thread thread;

        @Override public boolean onStartJob(final JobParameters params) {
            thread = new Thread(new Runnable() {
                @Override public void run() {
                    // En fazla iki dakika boyunca 10 saniyede bir denenir;
                    // sonrasinda is planlayiciya birakilir.
                    boolean done = drain(getApplicationContext(), 120_000L);
                    jobFinished(params, !done);
                }
            }, "astra-report-job");
            thread.start();
            return true;
        }

        @Override public boolean onStopJob(JobParameters params) {
            if (thread != null) thread.interrupt();
            return true;   // yarim kalan kuyruk icin yeniden planla
        }
    }

    /** Kuyrukta bekleyen kayit sayisi (arayuzde gosterilir). */
    public static String queueStatus(Context ctx) {
        int n = ReportQueue.size(ctx);
        if (n == 0) return ctx.getString(R.string.queue_status_empty);
        return ctx.getString(UpdateChecker.online(ctx)
                        ? R.string.queue_status_pending : R.string.queue_status_offline,
                n);
    }

    static String describe(long millis) {
        return String.format(Locale.US, "%.0f", millis / 1000.0);
    }
}
