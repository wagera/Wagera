package com.astraupscale.app;

/**
 * Uygulamayla birlikte gelen super-cozunurluk modelleri.
 *
 * Tumu ncnn bicimindedir ve APK icinde tasinir; internet gerekmez.
 * {@code costPerPixel}, kaynak piksel basina goreli islem maliyetidir ve
 * kullaniciyi uyarmak icin kullanilir (Hizli model = 1 birim).
 */
public enum SrModel {

    LANCZOS("Klasik (Lanczos)", "AstraUpscale",
            R.string.model_lanczos_desc,
            null, null, 1, 0, null, null, null, 1, 0, 0.02f),

    ESRGAN_FAST("Real-ESRGAN Hizli", "Real-ESRGAN · BSD 3-Clause",
            R.string.model_fast_desc,
            "models/realesr-animevideov3-x4.param", "models/realesr-animevideov3-x4.bin",
            4, 10, "data", "output", null, 1, 0, 1f),

    ESRGAN_GENERAL("Real-ESRGAN x4plus", "Real-ESRGAN · BSD 3-Clause",
            R.string.model_x4plus_desc,
            "models/realesrgan-x4plus.param", "models/realesrgan-x4plus.bin",
            4, 10, "data", "output", null, 1, 0, 60f),

    ESRGAN_ANIME("Real-ESRGAN Anime 6B", "Real-ESRGAN · BSD 3-Clause",
            R.string.model_anime_desc,
            "models/realesrgan-x4plus-anime.param", "models/realesrgan-x4plus-anime.bin",
            4, 10, "data", "output", null, 1, 0, 16f),

    BSRGAN("BSRGAN", "BSRGAN · Apache 2.0",
            R.string.model_bsrgan_desc,
            "models/bsrgan-x4.param", "models/bsrgan-x4.bin",
            4, 10, "data", "output", null, 1, 0, 60f),

    SWINIR_S("SwinIR-S", "SwinIR · Apache 2.0",
            R.string.model_swinir_s_desc,
            "models/swinir-s-x4.param", "models/swinir-s-x4.bin",
            4, 16, "in0", "out0", null, 1, 128, 12f),

    SWINIR_M("SwinIR-M", "SwinIR · Apache 2.0",
            R.string.model_swinir_m_desc,
            "models/swinir-m-x4.param", "models/swinir-m-x4.bin",
            4, 16, "in0", "out0", null, 1, 128, 150f),

    CUGAN_2X("Real-CUGAN 2x", "Real-CUGAN · MIT",
            R.string.model_cugan2_desc,
            "models/realcugan-up2x-conservative.param", "models/realcugan-up2x-conservative.bin",
            2, 18, "in0", "out0", Se.GAPS, 2, 0, 8f),

    CUGAN_2X_DENOISE("Real-CUGAN 2x + gurultu temizleme", "Real-CUGAN · MIT",
            R.string.model_cugan2d_desc,
            "models/realcugan-up2x-denoise3x.param", "models/realcugan-up2x-denoise3x.bin",
            2, 18, "in0", "out0", Se.GAPS, 2, 0, 8f),

    CUGAN_3X("Real-CUGAN 3x", "Real-CUGAN · MIT",
            R.string.model_cugan3_desc,
            "models/realcugan-up3x-conservative.param", "models/realcugan-up3x-conservative.bin",
            3, 14, "in0", "out0", Se.GAPS, 4, 0, 18f),

    CUGAN_4X("Real-CUGAN 4x", "Real-CUGAN · MIT",
            R.string.model_cugan4_desc,
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
    /**
     * Modelin geldigi proje.
     *
     * <p>Iki ise yarar: kullanici hangi calismanin ciktisini kullandigini
     * gorur, ve paketlenen BSD/MIT/Apache bileşenlerinin atifi arayuzde
     * de gorunur — lisanslarin hepsi ikili dagitimda telif bildiriminin
     * yeniden uretilmesini zorunlu kılıyor.
     */
    public final String origin;
    /** Aciklama dil kaynagindan gelir. */
    public final int descriptionRes;
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

    SrModel(String label, String origin, int descriptionRes, String paramAsset, String binAsset,
            int scale, int prepadding, String inputBlob, String outputBlob,
            String[] gapBlobs, int align, int fixedInput, float costPerPixel) {
        this.label = label;
        this.origin = origin;
        this.descriptionRes = descriptionRes;
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
