package com.astraupscale.engine;

import java.io.IOException;

/**
 * Buyutulmus satirlari dogrudan bir video kodlayicisinin YUV 4:2:0 giris
 * tamponuna yazar.
 *
 * <p>Bu sinif sayesinde 8K bir kare hicbir zaman butun halinde bellege
 * alinmaz: {@link Upscaler} satiri uretir, bu yazici onu ayni anda parlaklik
 * ve renk duzlemlerine dagitir. Bellekte tutulan tek fazla sey, renk
 * ortalamasi icin gereken bir onceki satirdir.
 */
public final class YuvWriter implements ImageWriter {

    private final Yuv.Plane y, u, v;
    private final Yuv.Table table;
    private final int frameWidth, frameHeight;

    private byte[] previous;
    private int row;

    public YuvWriter(Yuv.Plane y, Yuv.Plane u, Yuv.Plane v, int frameWidth, int frameHeight,
                     Yuv.Table table) {
        this.y = y;
        this.u = u;
        this.v = v;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.table = table;
    }

    @Override public void start(int width, int height) throws IOException {
        if (width != frameWidth || height != frameHeight) {
            throw new IOException("Kare boyutu kodlayiciyla uyusmuyor: "
                    + width + "x" + height + " != " + frameWidth + "x" + frameHeight);
        }
        previous = new byte[width * 3];
        row = 0;
    }

    @Override public void writeRow(byte[] rgb) {
        Yuv.luminanceRow(rgb, frameWidth, y, row, table);
        if ((row & 1) == 1) {
            Yuv.chromaRow(previous, rgb, frameWidth, u, v, row >> 1, table);
        } else {
            // Ust satirin kendisi saklanir: cagiran tamponu bir sonraki
            // satirda yeniden kullanir, elimizde kopyasi olmali.
            System.arraycopy(rgb, 0, previous, 0, frameWidth * 3);
        }
        row++;
    }

    @Override public void finish() {
        // Tek sayida satir varsa son satirin rengi yalniz kendisinden gelir.
        if ((frameHeight & 1) == 1 && row == frameHeight) {
            Yuv.chromaRow(previous, previous, frameWidth, u, v, (frameHeight - 1) >> 1, table);
        }
    }

    @Override public String extension() { return "yuv"; }

    @Override public String mimeType() { return "video/raw"; }
}
