package com.astraupscale.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Galeriye yazilan bir cikis dosyasi.
 *
 * <p>Android 10'dan itibaren uygulamalar ortak depolamaya dogrudan
 * yazamaz; kayit once {@link MediaStore} uzerinde acilir, doldurulur, sonra
 * "bekliyor" isareti kaldirilarak yayimlanir. Daha eski surumlerde klasik
 * dosya yolu kullanilir. Iki yolu da tek bir yerde toplamak, video ve kare
 * dizisi ciktilarinin ayni kurallari paylasmasini saglar.
 *
 * <p>Video icin akis degil <b>dosya tanimlayici</b> gerekir: MediaMuxer
 * kabin basligini yazarken dosyada geri gider, yani ileri dogru akan bir
 * cikis akisi ona yetmez.
 */
final class MediaOutput {

    /** MediaStore kaydi (Android 10+ ve cogu 8/9 cihaz). */
    Uri uri;
    /** Klasik dogrudan dosya (Android 7). */
    File file;

    private boolean pending;

    private MediaOutput() { }

    /** Movies/AstraUpscale altinda bir video kaydi acar. */
    static MediaOutput createVideo(Context ctx, String name, String mime) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Video.Media.MIME_TYPE, mime);
            cv.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/AstraUpscale");
            cv.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = ctx.getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("Galeriye video kaydi acilamadi");
            MediaOutput out = new MediaOutput();
            out.uri = uri;
            out.pending = true;
            return out;
        }
        return legacy(new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), "AstraUpscale"), name);
    }

    /**
     * Pictures/AstraUpscale/{@code folder} altinda bir goruntu kaydi acar.
     *
     * @param folder kare dizisinin klasoru; bos ise dogrudan AstraUpscale
     */
    static MediaOutput createImage(Context ctx, String folder, String name, String mime)
            throws IOException {
        String relative = Environment.DIRECTORY_PICTURES + "/AstraUpscale"
                + (folder == null || folder.isEmpty() ? "" : "/" + folder);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, mime);
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, relative);
            cv.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = ctx.getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("Galeriye kayit acilamadi");
            MediaOutput out = new MediaOutput();
            out.uri = uri;
            out.pending = true;
            return out;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "AstraUpscale"
                + (folder == null || folder.isEmpty() ? "" : "/" + folder));
        return legacy(dir, name);
    }

    private static MediaOutput legacy(File dir, String name) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        MediaOutput out = new MediaOutput();
        out.file = new File(dir, name);
        return out;
    }

    OutputStream openStream(Context ctx) throws IOException {
        if (file != null) return new FileOutputStream(file);
        OutputStream os = ctx.getContentResolver().openOutputStream(uri);
        if (os == null) throw new IOException("Cikis dosyasi acilamadi");
        return os;
    }

    /**
     * Kabin basligini geri donup yazabilmesi icin okuma/yazma tanimlayici.
     * Yalnizca MediaStore kayitlarinda gerekir; klasik yolda dosya adi
     * dogrudan MediaMuxer'a verilir.
     */
    ParcelFileDescriptor openDescriptor(Context ctx) throws IOException {
        ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "rw");
        if (pfd == null) throw new IOException("Cikis dosyasi acilamadi");
        return pfd;
    }

    Uri publish(Context ctx) {
        if (uri != null) {
            if (pending) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                try {
                    ctx.getContentResolver().update(uri, cv, null, null);
                } catch (Throwable ignored) {
                }
                pending = false;
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
        if (uri == null) return 0;
        ContentResolver cr = ctx.getContentResolver();
        android.database.Cursor c = null;
        try {
            c = cr.query(uri, new String[]{MediaStore.MediaColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    void discard(Context ctx) {
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
