package com.astraupscale.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Uygulama icindeki galeri.
 *
 * <p>Sistemin belge secicisine atlamak yerine cihazdaki son fotograflari
 * bir izgarada gosterir; kullanici uygulamadan cikmadan secer.
 *
 * <p><b>Gizlilik:</b> bu sinif fotograflari yalnizca ekranda gostermek icin
 * okur. Hicbir goruntu, kucuk resim ya da dosya yolu cihazdan disari
 * cikmaz — ne sunucuya, ne webhook'a, ne de baska bir uygulamaya. Disari
 * giden tek sey, kullanicinin kendi yazdigi geri bildirimlerdir.
 *
 * <p>Izin verilmezse sinif devre disi kalmaz: kullanici "Dosyalar" ile
 * sistem secicisini kullanmaya devam edebilir.
 */
final class GalleryPicker {

    /** Izgara sutun sayisi. */
    private static final int COLUMNS = 3;
    /** Bir seferde okunan en fazla fotograf; izgara sonsuza kadar buyumesin. */
    private static final int LIMIT = 300;
    /**
     * Video icin daha dusuk sinir: bir video kucuk resmi bir fotograftan
     * kat kat pahali cozulur (kap acilir, ilk kare bulunur, cozulur).
     */
    private static final int VIDEO_LIMIT = 120;
    /** Kucuk resmin hedef kenar uzunlugu (px); ekran yogunluguyla carpilir. */
    private static final int THUMB_DP = 120;

    interface OnPicked {
        void onPicked(Uri uri);
    }

    /** Kucuk resimleri arka planda cozen tek is parcacigi. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Activity activity;
    private final OnPicked callback;
    /** Izgara video mu listeliyor? Ayni sinif iki turu de gosterir. */
    private boolean videos;

    GalleryPicker(Activity activity, OnPicked callback) {
        this.activity = activity;
        this.callback = callback;
    }

    void setVideos(boolean videos) {
        this.videos = videos;
    }

    boolean isVideos() {
        return videos;
    }

    /** API duzeyine gore dogru fotograf izni. */
    static String permission() {
        return permission(false);
    }

    /**
     * API duzeyine ve ture gore dogru izin.
     *
     * <p>Android 13'ten itibaren fotograf ve video izinleri ayrildi;
     * oncesinde ikisi de tek bir depolama izninin altindaydi.
     */
    static String permission(boolean videos) {
        if (Build.VERSION.SDK_INT >= 33) {
            return videos ? Manifest.permission.READ_MEDIA_VIDEO
                    : Manifest.permission.READ_MEDIA_IMAGES;
        }
        return Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    boolean hasPermission() {
        return activity.checkSelfPermission(permission(videos))
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Izgarayi doldurur.
     *
     * @return okunan fotograf sayisi; 0 ise cihazda fotograf yok
     */
    int load(LinearLayout grid) {
        grid.removeAllViews();
        List<Uri> photos = videos ? queryVideos() : query();
        if (photos.isEmpty()) return 0;

        int density = (int) activity.getResources().getDisplayMetrics().density;
        int gap = 4 * density;
        int cell = (activity.getResources().getDisplayMetrics().widthPixels
                - 2 * 12 * density - (COLUMNS - 1) * gap) / COLUMNS;

        LinearLayout row = null;
        for (int i = 0; i < photos.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rp.topMargin = gap;
                grid.addView(row, rp);
            }
            row.addView(cellFor(photos.get(i), cell, i % COLUMNS == 0 ? 0 : gap));
        }
        return photos.size();
    }

    /** Cihazdaki fotograflari yeniden eskiye dogru listeler. */
    private List<Uri> query() {
        List<Uri> out = new ArrayList<>();
        String[] columns = {MediaStore.Images.Media._ID};
        Cursor c = null;
        try {
            c = activity.getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    columns, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC");
            if (c == null) return out;
            int idColumn = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            while (c.moveToNext() && out.size() < LIMIT) {
                out.add(ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idColumn)));
            }
        } catch (Exception e) {
            // Saglayici erisilemezse izgara bos kalir; "Dosyalar" yolu acik.
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** Cihazdaki videolari yeniden eskiye dogru listeler. */
    private List<Uri> queryVideos() {
        List<Uri> out = new ArrayList<>();
        String[] columns = {MediaStore.Video.Media._ID};
        Cursor c = null;
        try {
            c = activity.getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    columns, null, null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC");
            if (c == null) return out;
            int idColumn = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            while (c.moveToNext() && out.size() < VIDEO_LIMIT) {
                out.add(ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(idColumn)));
            }
        } catch (Exception e) {
            // Saglayici erisilemezse izgara bos kalir; "Dosyalar" yolu acik.
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    private View cellFor(final Uri uri, int size, int startMargin) {
        final ImageView view = new ImageView(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginStart(startMargin);
        view.setLayoutParams(lp);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setBackgroundResource(R.drawable.gallery_cell);
        view.setClipToOutline(true);
        view.setContentDescription(activity.getString(R.string.gallery_title));
        view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { callback.onPicked(uri); }
        });

        final int target = THUMB_DP * (int) activity.getResources().getDisplayMetrics().density;
        final boolean isVideo = videos;
        io.execute(new Runnable() {
            @Override public void run() {
                final Bitmap bmp = isVideo ? decodeVideo(uri, target) : decode(uri, target);
                if (bmp == null) return;
                ui.post(new Runnable() {
                    @Override public void run() { view.setImageBitmap(bmp); }
                });
            }
        });
        return view;
    }

    /**
     * Fotografi hedef boyuta yakin bir olcekte cozer.
     *
     * <p>Once yalnizca basligi okunur, sonra iki kuvveti bir orneklem
     * araligi secilir: tam boyutlu bir fotografi bellege almak izgarada
     * onlarca kez tekrarlanamaz.
     */
    private Bitmap decode(Uri uri, int target) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = activity.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            try {
                BitmapFactory.decodeStream(in, null, bounds);
            } finally {
                in.close();
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / (sample * 2) >= target) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            InputStream in2 = activity.getContentResolver().openInputStream(uri);
            if (in2 == null) return null;
            try {
                return BitmapFactory.decodeStream(in2, null, opts);
            } finally {
                in2.close();
            }
        } catch (Exception e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * Videonun kucuk resmi.
     *
     * <p>Android 10'dan itibaren saglayici hazir bir kucuk resim verebilir;
     * oncesinde MediaStore'un kendi onbellegi kullanilir. Ikisi de
     * basarisiz olursa hucre bos kalir — bir videoyu tam olarak cozup ilk
     * karesini almak, izgarada yuz kez tekrarlanabilecek bir is degil.
     */
    private Bitmap decodeVideo(Uri uri, int target) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return activity.getContentResolver().loadThumbnail(
                        uri, new android.util.Size(target, target), null);
            }
            long id = android.content.ContentUris.parseId(uri);
            return MediaStore.Video.Thumbnails.getThumbnail(activity.getContentResolver(),
                    id, MediaStore.Video.Thumbnails.MINI_KIND, null);
        } catch (Exception e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    void shutdown() {
        io.shutdownNow();
    }
}
