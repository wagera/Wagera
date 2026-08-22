package com.opeyscaler.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Surum denetimi.
 *
 * Uygulama her acilista internet varsa depodaki {@code surum.json} dosyasina
 * bakar. Daha yeni bir surum goruldugu anda bu bilgi kalici olarak
 * kaydedilir ve uygulama, kullanici guncellemeyi yukleyene kadar acilmaz —
 * internet sonradan kesilse bile.
 *
 * Internet yoksa ve daha once yeni bir surum gorulmemisse uygulama uyariyla
 * birlikte normal calisir.
 */
public final class UpdateChecker {

    /** Depodaki surum bilgisi; dal degisirse burasi guncellenmeli. */
    private static final String VERSION_URL =
            "https://raw.githubusercontent.com/wagera/Wagera/main/OpeyScaler/surum.json";

    private static final String PREFS = "opeyscaler";
    private static final String KEY_PENDING_CODE = "pending_version_code";
    private static final String KEY_PENDING_NAME = "pending_version_name";
    private static final String KEY_PENDING_URL = "pending_url";
    private static final String KEY_PENDING_NOTES = "pending_notes";
    private static final String KEY_LAST_CHECK = "last_check_millis";
    private static final String KEY_LAST_SEEN = "last_seen_code";

    public static final class Result {
        /** Yuklenmesi gereken surum; yoksa 0. */
        public int versionCode;
        public String versionName = "";
        public String downloadUrl = "";
        public String notes = "";
        /** Bu acilista denetim yapilabildi mi? */
        public boolean checked;
        /** En son basarili denetimin uzerinden gecen sure (ms); hic yapilmadiysa -1. */
        public long lastCheckAgeMillis = -1;

        public boolean blocking() {
            return versionCode > 0;
        }
    }

    private UpdateChecker() { }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean online(Context ctx) {
        ConnectivityManager cm =
                (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= 23) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    /** Daha once gorulmus ve hala yuklenmemis bir surum var mi? */
    public static Result pending(Context ctx) {
        SharedPreferences p = prefs(ctx);
        Result r = new Result();
        int code = p.getInt(KEY_PENDING_CODE, 0);
        if (code > BuildInfo.versionCode(ctx)) {
            r.versionCode = code;
            r.versionName = p.getString(KEY_PENDING_NAME, "");
            r.downloadUrl = p.getString(KEY_PENDING_URL, "");
            r.notes = p.getString(KEY_PENDING_NOTES, "");
        } else if (code > 0) {
            // Kullanici guncellemeyi yuklemis; kayit temizlenir.
            p.edit().remove(KEY_PENDING_CODE).remove(KEY_PENDING_NAME)
                    .remove(KEY_PENDING_URL).remove(KEY_PENDING_NOTES).apply();
        }
        long last = p.getLong(KEY_LAST_CHECK, 0);
        r.lastCheckAgeMillis = last == 0 ? -1 : System.currentTimeMillis() - last;
        return r;
    }

    /**
     * Aga baglanip surumu denetler. Arka planda cagrilmalidir.
     * Sonuc kalici olarak kaydedilir.
     */
    public static Result check(Context ctx) {
        Result r = pending(ctx);
        if (!online(ctx)) return r;

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(VERSION_URL).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) return r;

            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && bos.size() < (1 << 20)) bos.write(buf, 0, n);
            in.close();

            JSONObject json = new JSONObject(bos.toString("UTF-8"));
            int remote = json.optInt("versionCode", 0);
            SharedPreferences.Editor e = prefs(ctx).edit();
            e.putLong(KEY_LAST_CHECK, System.currentTimeMillis());
            e.putInt(KEY_LAST_SEEN, remote);

            r.checked = true;
            r.lastCheckAgeMillis = 0;
            if (remote > BuildInfo.versionCode(ctx)) {
                r.versionCode = remote;
                r.versionName = json.optString("versionName", "");
                r.downloadUrl = json.optString("apkUrl", "");
                r.notes = json.optString("notes", "");
                e.putInt(KEY_PENDING_CODE, remote)
                        .putString(KEY_PENDING_NAME, r.versionName)
                        .putString(KEY_PENDING_URL, r.downloadUrl)
                        .putString(KEY_PENDING_NOTES, r.notes);
            }
            e.apply();
        } catch (Throwable ignored) {
            // Ag hatasi: elimizdeki bilgiyle devam edilir.
        } finally {
            if (conn != null) conn.disconnect();
        }
        return r;
    }
}
