package com.astraupscale.engine;

/**
 * Duz bir RGB bayt tamponunu {@link PixelSource} olarak sunar.
 *
 * <p>Video hattinda her kare once bu bicime cozulur. Ayni tamponun
 * kareden kareye yeniden kullanilabilmesi icin veri kopyalanmaz; bu yuzden
 * bir kareyi isleyip bitirmeden tamponun uzerine yazilmamalidir.
 *
 * <p>Neden {@code int[]} degil de {@code byte[]}: 8K bir kare {@code int[]}
 * olarak 132 megapiksel x 4 bayt = 530 MB tutar, {@code byte[]} olarak
 * 398 MB. Kaynak kareler daha kucuk olsa da fark her karede odenir.
 */
public final class RgbPixelSource implements PixelSource {

    private final byte[] rgb;
    private final int width, height;

    public RgbPixelSource(byte[] rgb, int width, int height) {
        this.rgb = rgb;
        this.width = width;
        this.height = height;
    }

    public byte[] buffer() { return rgb; }

    @Override public int width() { return width; }

    @Override public int height() { return height; }

    @Override public void readRow(int y, int[] out) {
        int i = y * width * 3;
        for (int x = 0; x < width; x++) {
            out[x] = 0xFF000000
                    | ((rgb[i] & 0xFF) << 16)
                    | ((rgb[i + 1] & 0xFF) << 8)
                    | (rgb[i + 2] & 0xFF);
            i += 3;
        }
    }
}
