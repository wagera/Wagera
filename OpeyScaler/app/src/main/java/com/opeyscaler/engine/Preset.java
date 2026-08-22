package com.opeyscaler.engine;

/** Hedef cozunurluk on ayarlari. Olcut, goruntunun uzun kenaridir. */
public enum Preset {
    R2K("2K", 2560),
    R3K("3K", 3072),
    R4K("4K", 3840),
    R5K("5K", 5120),
    R6K("6K", 6144),
    R8K("8K", 7680),
    R10K("10K", 10240),
    R12K("12K", 12288),
    R16K("16K", 15360),
    R32K("32K", 30720),
    R64K("64K", 61440),
    R128K("128K", 122880),
    R256K("256K", 245760),
    R512K("512K", 491520);

    /** JPEG basligi boyutlari 16 bit tutar; bunun uzerinde yalnizca PNG yazilabilir. */
    public static final int JPEG_MAX_EDGE = 65535;

    /** Bu on ayarda gurultu giderme varsayilan olarak acilir. */
    public static final int DENOISE_FROM_EDGE = 61440;

    public final String label;
    public final int longEdge;

    Preset(String label, int longEdge) {
        this.label = label;
        this.longEdge = longEdge;
    }

    /** En/boy oranini koruyarak hedef boyutu hesaplar: {@code {genislik, yukseklik}}. */
    public int[] targetSize(int srcW, int srcH) {
        double ratio = longEdge / (double) Math.max(srcW, srcH);
        int w = (int) Math.round(srcW * ratio);
        int h = (int) Math.round(srcH * ratio);
        return new int[]{Math.max(1, w), Math.max(1, h)};
    }

    /** Hedef boyut JPEG'e sigiyor mu? Sigmiyorsa PNG zorunludur. */
    public boolean fitsJpeg(int srcW, int srcH) {
        int[] t = targetSize(srcW, srcH);
        return t[0] <= JPEG_MAX_EDGE && t[1] <= JPEG_MAX_EDGE;
    }

    /** Bu cozunurlukte kaynaktaki gurultu once temizlenmeli mi? */
    public boolean needsDenoise() {
        return longEdge >= DENOISE_FROM_EDGE;
    }

    public long megapixels(int srcW, int srcH) {
        int[] t = targetSize(srcW, srcH);
        return (long) t[0] * t[1];
    }

    public static Preset byLabel(String label) {
        for (Preset p : values()) {
            if (p.label.equals(label)) return p;
        }
        return R4K;
    }
}
