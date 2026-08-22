package com.opeyscaler.app;

import android.content.res.AssetManager;

/**
 * ncnn tabanli yerel super-cozunurluk motorunun Java sarmalayicisi.
 * Model APK icindeki assets klasorunden okunur; GPU (Vulkan) varsa kullanilir,
 * yoksa CPU'ya duser.
 */
public final class NativeSr {

    private static boolean loaded;
    private static boolean loadFailed;

    private long handle;
    private final int scale;

    private static native boolean nativeGpuAvailable();

    private static native int nativeCpuCount();

    private static native long nativeCreate(AssetManager assets, String paramAsset, String binAsset,
                                            int scale, int prepadding, String inBlob, String outBlob,
                                            boolean useGpu, int threads, int tile);

    private static native boolean nativeUsingGpu(long handle);

    private static native void nativeSetSource(long handle, byte[] rgb, int width, int height);

    private static native boolean nativeProcessBand(long handle, int srcY0, int srcRows, byte[] out);

    private static native String nativeLastError(long handle);

    private static native void nativeDestroy(long handle);

    /** Yerel kitaplik bu cihazda kullanilabiliyor mu? */
    public static synchronized boolean available() {
        if (!loaded && !loadFailed) {
            try {
                System.loadLibrary("opeyscaler");
                loaded = true;
            } catch (Throwable t) {
                loadFailed = true;
            }
        }
        return loaded;
    }

    public static boolean gpuAvailable() {
        if (!available()) return false;
        try {
            return nativeGpuAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    public static int cpuCount() {
        if (!available()) return Runtime.getRuntime().availableProcessors();
        try {
            return nativeCpuCount();
        } catch (Throwable t) {
            return Runtime.getRuntime().availableProcessors();
        }
    }

    private NativeSr(long handle, int scale) {
        this.handle = handle;
        this.scale = scale;
    }

    /** Modeli yukler; basarisiz olursa null doner. */
    public static NativeSr open(AssetManager assets, SrModel model, boolean useGpu, int tile) {
        if (!available() || !model.isNeural()) return null;
        long h = nativeCreate(assets, model.paramAsset, model.binAsset, model.scale, model.prepadding,
                model.inputBlob, model.outputBlob, useGpu, cpuCount(), tile);
        return h == 0 ? null : new NativeSr(h, model.scale);
    }

    public int scale() { return scale; }

    public boolean usingGpu() { return handle != 0 && nativeUsingGpu(handle); }

    public void setSource(byte[] rgb, int width, int height) {
        nativeSetSource(handle, rgb, width, height);
    }

    /** Kaynagin bir satir bandini buyutur; cikis (srcW*scale) x (rows*scale) x 3 bayttir. */
    public boolean processBand(int srcY0, int rows, byte[] out) {
        return nativeProcessBand(handle, srcY0, rows, out);
    }

    public String lastError() {
        return handle == 0 ? "" : nativeLastError(handle);
    }

    public void close() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
    }
}
