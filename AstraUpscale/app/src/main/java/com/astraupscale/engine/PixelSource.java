package com.astraupscale.engine;

/**
 * Kaynak goruntuye satir satir erisim. Boylece 16K gibi devasa cikislarda bile
 * tum goruntuyu bellekte tutmak zorunda kalmayiz.
 */
public interface PixelSource {
    int width();

    int height();

    /**
     * Verilen satiri 0xAARRGGBB formatinda {@code out} dizisine yazar.
     * {@code out.length >= width()} olmalidir.
     */
    void readRow(int y, int[] out);
}
