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
    R16K("16K", 15360);

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
