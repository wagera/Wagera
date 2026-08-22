package com.astraupscale.engine;

/**
 * sRGB <-> dogrusal isik donusum tablolari.
 * Olcekleme dogrusal isikta yapilirsa parlaklik ve kenar gecisleri dogru korunur.
 */
public final class Color {
    private Color() { }

    /** 8 bit sRGB -> dogrusal (0..1). */
    public static final float[] TO_LINEAR = new float[256];

    private static final int INV_SIZE = 16384;
    /** dogrusal (0..1) -> 8 bit sRGB. */
    private static final byte[] TO_SRGB = new byte[INV_SIZE];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
        for (int i = 0; i < INV_SIZE; i++) {
            double lin = i / (double) (INV_SIZE - 1);
            double s = lin <= 0.0031308 ? lin * 12.92 : 1.055 * Math.pow(lin, 1 / 2.4) - 0.055;
            int v = (int) Math.round(s * 255.0);
            TO_SRGB[i] = (byte) (v < 0 ? 0 : Math.min(v, 255));
        }
    }

    public static int linearToSrgb(float lin) {
        int idx = (int) (lin * (INV_SIZE - 1) + 0.5f);
        if (idx < 0) idx = 0;
        else if (idx >= INV_SIZE) idx = INV_SIZE - 1;
        return TO_SRGB[idx] & 0xFF;
    }
}
