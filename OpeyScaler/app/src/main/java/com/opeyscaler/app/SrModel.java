package com.opeyscaler.app;

/**
 * Uygulamayla birlikte gelen super-cozunurluk modelleri.
 *
 * Tumu ncnn bicimindedir ve APK icinde tasinir; internet gerekmez.
 * {@code costPerPixel}, kaynak piksel basina goreli islem maliyetidir ve
 * kullaniciyi uyarmak icin kullanilir (Hizli model = 1 birim).
 */
public enum SrModel {

    LANCZOS("Klasik (Lanczos)",
            "Yapay zeka yok; aninda sonuc. Her boyutta calisir.",
            null, null, 1, 0, null, null, null, 1, 0, 0.02f),

    ESRGAN_FAST("Real-ESRGAN Hizli",
            "animevideov3 - kucuk ve hizli model, dengeli sonuc.",
            "models/realesr-animevideov3-x4.param", "models/realesr-animevideov3-x4.bin",
            4, 10, "data", "output", null, 1, 0, 1f),

    ESRGAN_GENERAL("Real-ESRGAN x4plus",
            "Genel amacli, fotograflarda en iyi detay. Yavas.",
            "models/realesrgan-x4plus.param", "models/realesrgan-x4plus.bin",
            4, 10, "data", "output", null, 1, 0, 60f),

    ESRGAN_ANIME("Real-ESRGAN Anime 6B",
            "x4plus-anime-6B - anime, cizim ve illustrasyon icin.",
            "models/realesrgan-x4plus-anime.param", "models/realesrgan-x4plus-anime.bin",
            4, 10, "data", "output", null, 1, 0, 16f),

    BSRGAN("BSRGAN",
            "Bozulmus, gurultulu ve sikistirilmis fotograflarda guclu. Yavas.",
            "models/bsrgan-x4.param", "models/bsrgan-x4.bin",
            4, 10, "data", "output", null, 1, 0, 60f),

    SWINIR_S("SwinIR-S",
            "Hafif transformer; temiz ve dogal detay, orta hiz.",
            "models/swinir-s-x4.param", "models/swinir-s-x4.bin",
            4, 16, "in0", "out0", null, 1, 128, 12f),

    SWINIR_M("SwinIR-M",
            "Buyuk transformer; en gercekci sonuc, en yavas secenek.",
            "models/swinir-m-x4.param", "models/swinir-m-x4.bin",
            4, 16, "in0", "out0", null, 1, 128, 150f),

    CUGAN_2X("Real-CUGAN 2x",
            "Anime ve cizimde temiz cizgiler, olcusunde iyilestirme.",
            "models/realcugan-up2x-conservative.param", "models/realcugan-up2x-conservative.bin",
            2, 18, "in0", "out0", Se.GAPS, 2, 0, 8f),

    CUGAN_2X_DENOISE("Real-CUGAN 2x + gurultu temizleme",
            "Gurultulu veya asiri sikistirilmis cizimler icin.",
            "models/realcugan-up2x-denoise3x.param", "models/realcugan-up2x-denoise3x.bin",
            2, 18, "in0", "out0", Se.GAPS, 2, 0, 8f),

    CUGAN_3X("Real-CUGAN 3x",
            "Tek gecipte 3 kat; cizim ve illustrasyon.",
            "models/realcugan-up3x-conservative.param", "models/realcugan-up3x-conservative.bin",
            3, 14, "in0", "out0", Se.GAPS, 4, 0, 18f),

    CUGAN_4X("Real-CUGAN 4x",
            "Tek gecipte 4 kat; cizim ve illustrasyon.",
            "models/realcugan-up4x-conservative.param", "models/realcugan-up4x-conservative.bin",
            4, 19, "in0", "out0", Se.GAPS, 2, 0, 24f);

    /**
     * Real-CUGAN'in SE bloklarindaki kuresel havuzlama blob'lari.
     * Enum sabitleri, enum'un kendi statik alanlarindan once ilklendigi icin
     * bu deger ic sinifta tutulur.
     */
    private static final class Se {
        static final String[] GAPS = {"gap0", "gap1", "gap2", "gap3"};
    }

    public final String label;
    public final String description;
    public final String paramAsset;
    public final String binAsset;
    public final int scale;
    public final int prepadding;
    public final String inputBlob;
    public final String outputBlob;
    /** Bos degilse model SE'lidir ve tum goruntu uzerinden hazirlik gerektirir. */
    public final String[] gapBlobs;
    /** Doseme boyutunun katina yuvarlanmasi gereken deger. */
    public final int align;
    /** Sifirdan buyukse modele her zaman bu kenar uzunlugunda kare girdi verilir. */
    public final int fixedInput;
    public final float costPerPixel;

    SrModel(String label, String description, String paramAsset, String binAsset,
            int scale, int prepadding, String inputBlob, String outputBlob,
            String[] gapBlobs, int align, int fixedInput, float costPerPixel) {
        this.label = label;
        this.description = description;
        this.paramAsset = paramAsset;
        this.binAsset = binAsset;
        this.scale = scale;
        this.prepadding = prepadding;
        this.inputBlob = inputBlob;
        this.outputBlob = outputBlob;
        this.gapBlobs = gapBlobs == null ? new String[0] : gapBlobs;
        this.align = align;
        this.fixedInput = fixedInput;
        this.costPerPixel = costPerPixel;
    }

    public boolean isNeural() {
        return paramAsset != null;
    }

    /**
     * Model dosyalari bu APK'ya konmus mu? Ince surumlerde bazi modeller
     * disarida birakilabilir; o zaman listede gosterilmezler.
     */
    public boolean isBundled(android.content.res.AssetManager assets) {
        if (!isNeural()) return true;
        try {
            assets.open(paramAsset).close();
            assets.open(binAsset).close();
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }
}
