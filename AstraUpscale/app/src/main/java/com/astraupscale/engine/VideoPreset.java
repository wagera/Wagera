package com.astraupscale.engine;

/**
 * Video icin hedef cozunurluk on ayarlari. Olcut, karenin uzun kenaridir.
 *
 * <p>Fotograf tarafindaki {@link Preset} on dort kademe tasir; video icin
 * dort kademe yeter ve dogrudur. Bir fotografi 512K'ya cikarmak tek bir
 * dosya uretir; ayni seyi saniyede 30 kez yapmak, bir telefonun bir gunde
 * bitiremeyecegi bir istir. Buradaki dort deger, kullanilan yayin
 * standartlarinin kendisidir: 2K (QHD), 4K (UHD), 8K (UHD-2) ve onun iki
 * kati olan 16K.
 *
 * <p>Kenarlar cift sayiya yuvarlanir: 4:2:0 renk alt orneklemesi kullanan
 * her video kodlayicisi bunu sart kosar. Kodlayicinin kendi hizalama
 * istegi (bazi yongalarda 16'nin kati) bundan sonra uygulanir.
 */
public enum VideoPreset {
    V2K("2K", 2560),
    V4K("4K", 3840),
    V8K("8K", 7680),
    V16K("16K", 15360);

    /**
     * Bu cozunurluktan itibaren kaynaktaki gurultu buyutmeden once
     * temizlenir: 8K'da bir gurultu pikseli ekranda dort kat yer kaplar ve
     * kodlayicinin bit butcesini de yer.
     */
    public static final int DENOISE_FROM_EDGE = 7680;

    public final String label;
    public final int longEdge;

    VideoPreset(String label, int longEdge) {
        this.label = label;
        this.longEdge = longEdge;
    }

    /** En/boy oranini koruyarak hedef kare boyutunu hesaplar (cift sayiya yuvarlanmis). */
    public int[] targetSize(int srcW, int srcH) {
        double ratio = longEdge / (double) Math.max(srcW, srcH);
        int w = even((int) Math.round(srcW * ratio));
        int h = even((int) Math.round(srcH * ratio));
        return new int[]{Math.max(2, w), Math.max(2, h)};
    }

    /** Kaynagi hic kucultmez: zaten hedeften buyukse buyutme carpani 1'dir. */
    public double factor(int srcW, int srcH) {
        return longEdge / (double) Math.max(1, Math.max(srcW, srcH));
    }

    public long pixels(int srcW, int srcH) {
        int[] t = targetSize(srcW, srcH);
        return (long) t[0] * t[1];
    }

    public boolean needsDenoise() {
        return longEdge >= DENOISE_FROM_EDGE;
    }

    private static int even(int v) {
        return v & ~1;
    }

    public static VideoPreset byLabel(String label) {
        for (VideoPreset p : values()) {
            if (p.label.equals(label)) return p;
        }
        return V4K;
    }
}
