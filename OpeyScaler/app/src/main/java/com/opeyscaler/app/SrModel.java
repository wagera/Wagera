package com.opeyscaler.app;

/**
 * Uygulamayla birlikte gelen super-cozunurluk modelleri.
 *
 * Tumu ncnn bicimindedir ve APK icinde tasinir; internet gerekmez.
 * {@code costPerPixel} degeri, kaynak piksel basina goreli islem maliyetidir
 * ve kalan sure tahmininde baslangic degeri olarak kullanilir.
 */
public enum SrModel {

    LANCZOS("Klasik (Lanczos)",
            "Yapay zeka yok; en hizli secenek. Her boyutta calisir.",
            null, null, 1, 0, null, null, 0.02f),

    ESRGAN_FAST("Real-ESRGAN Hizli",
            "animevideov3 - kucuk ve hizli model, dengeli sonuc.",
            "models/realesr-animevideov3-x4.param", "models/realesr-animevideov3-x4.bin",
            4, 10, "data", "output", 1f),

    ESRGAN_GENERAL("Real-ESRGAN Genel",
            "x4plus - fotograflarda en iyi detay, en yavas secenek.",
            "models/realesrgan-x4plus.param", "models/realesrgan-x4plus.bin",
            4, 10, "data", "output", 60f),

    ESRGAN_ANIME("Real-ESRGAN Anime",
            "x4plus-anime - cizim, anime ve illustrasyon icin.",
            "models/realesrgan-x4plus-anime.param", "models/realesrgan-x4plus-anime.bin",
            4, 10, "data", "output", 16f),

    BSRGAN("BSRGAN",
            "Bozulmus, gurultulu ve sikistirilmis fotograflarda guclu.",
            "models/bsrgan-x4.param", "models/bsrgan-x4.bin",
            4, 10, "data", "output", 60f),

    REALCUGAN("Real-CUGAN 2x",
            "Anime ve illustrasyonda temiz cizgiler, orta hiz.",
            "models/realcugan-up2x-no-denoise.param", "models/realcugan-up2x-no-denoise.bin",
            2, 18, "in0", "out0", 6f);

    public final String label;
    public final String description;
    public final String paramAsset;
    public final String binAsset;
    public final int scale;
    public final int prepadding;
    public final String inputBlob;
    public final String outputBlob;
    public final float costPerPixel;

    SrModel(String label, String description, String paramAsset, String binAsset,
            int scale, int prepadding, String inputBlob, String outputBlob, float costPerPixel) {
        this.label = label;
        this.description = description;
        this.paramAsset = paramAsset;
        this.binAsset = binAsset;
        this.scale = scale;
        this.prepadding = prepadding;
        this.inputBlob = inputBlob;
        this.outputBlob = outputBlob;
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
