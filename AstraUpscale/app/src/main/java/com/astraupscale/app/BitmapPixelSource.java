package com.astraupscale.app;

import android.graphics.Bitmap;

import com.astraupscale.engine.PixelSource;

/**
 * Bitmap'i EXIF yonlendirmesini uygulayarak satir satir sunar.
 *
 * DonduruIen bir kopya olusturmak yerine okuma sirasinda koordinat donusumu
 * yapilir; boylece buyuk fotograflarda ikinci bir Bitmap kopyasi icin bellek
 * harcanmaz.
 */
public final class BitmapPixelSource implements PixelSource {

    private final Bitmap bitmap;
    private final boolean swapXY, flipX, flipY;
    private final int bmpW, bmpH;
    private final int width, height;
    private final int[] scratch;

    /** @param exifOrientation android.media.ExifInterface.ORIENTATION_* degeri */
    public BitmapPixelSource(Bitmap bitmap, int exifOrientation) {
        this.bitmap = bitmap;
        this.bmpW = bitmap.getWidth();
        this.bmpH = bitmap.getHeight();
        switch (exifOrientation) {
            case 2:  swapXY = false; flipX = true;  flipY = false; break; // yatay ayna
            case 3:  swapXY = false; flipX = true;  flipY = true;  break; // 180
            case 4:  swapXY = false; flipX = false; flipY = true;  break; // dikey ayna
            case 5:  swapXY = true;  flipX = false; flipY = false; break; // transpoze
            case 6:  swapXY = true;  flipX = true;  flipY = false; break; // 90 saat yonu
            case 7:  swapXY = true;  flipX = true;  flipY = true;  break; // ters transpoze
            case 8:  swapXY = true;  flipX = false; flipY = true;  break; // 270
            default: swapXY = false; flipX = false; flipY = false; break;
        }
        this.width = swapXY ? bmpH : bmpW;
        this.height = swapXY ? bmpW : bmpH;
        this.scratch = new int[Math.max(bmpW, bmpH)];
    }

    @Override public int width() { return width; }

    @Override public int height() { return height; }

    @Override public void readRow(int y, int[] out) {
        int y1 = flipY ? height - 1 - y : y;
        if (!swapXY) {
            bitmap.getPixels(scratch, 0, bmpW, 0, y1, bmpW, 1);
            if (flipX) {
                for (int x = 0; x < width; x++) out[x] = scratch[width - 1 - x];
            } else {
                System.arraycopy(scratch, 0, out, 0, width);
            }
        } else {
            // Yonlendirilmis satir, bitmap'te bir sutuna karsilik gelir.
            bitmap.getPixels(scratch, 0, 1, y1, 0, 1, bmpH);
            if (flipX) {
                for (int x = 0; x < width; x++) out[x] = scratch[width - 1 - x];
            } else {
                System.arraycopy(scratch, 0, out, 0, width);
            }
        }
    }
}
