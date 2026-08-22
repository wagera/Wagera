package com.opeyscaler.engine;

/** Bellekteki ARGB dizisini {@link PixelSource} olarak sunar. */
public final class ArrayPixelSource implements PixelSource {
    private final int[] pixels;
    private final int width;
    private final int height;

    public ArrayPixelSource(int[] pixels, int width, int height) {
        this.pixels = pixels;
        this.width = width;
        this.height = height;
    }

    @Override public int width() { return width; }

    @Override public int height() { return height; }

    @Override public void readRow(int y, int[] out) {
        System.arraycopy(pixels, y * width, out, 0, width);
    }
}
