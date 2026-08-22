package com.opeyscaler.app;

/**
 * Kullanicinin sectigi "telefonu zorlama" seviyesi. Is parcacigi sayisi,
 * doseme boyutu ve dosemeler arasi soluklanma bu seviyeden turetilir.
 *
 * Kalite hicbir seviyede degismez; degisen yalnizca isin ne kadar hizli
 * yapildigi ve cihazin ne kadar isinip pil harcadigidir.
 */
public enum LoadLevel {

    GENTLE("Sakin", "Yarim guc. Telefon serin kalir, pil az harcanir; islem uzar."),
    BALANCED("Dengeli", "Buyuk cekirdekler. Gunluk kullanim icin onerilen denge."),
    FULL("Tam guc", "Tum cekirdekler. En hizlisi; telefon isinir ve pil hizli biter.");

    public final String label;
    public final String description;

    LoadLevel(String label, String description) {
        this.label = label;
        this.description = description;
    }

    /** Bu seviyede kullanilacak is parcacigi sayisi. */
    public int threads(DeviceProfile d) {
        switch (this) {
            case GENTLE: return Math.max(1, d.cores / 3);
            case FULL: return Math.max(1, d.cores);
            default: return Math.max(1, Math.min(d.bigCores, Math.max(2, d.cores - 2)));
        }
    }

    /**
     * Model dosemesinin kenar uzunlugu. Kucuk doseme daha az bellek ve daha
     * kisa kesintisiz mesgul sure demektir; sonuc ayni kalir.
     */
    public int tileSize(DeviceProfile d) {
        int base = d.heapLimitBytes >= (512L << 20) ? 160 : 128;
        switch (this) {
            case GENTLE: return 96;
            case FULL: return base;
            default: return Math.min(base, 128);
        }
    }

    /** Her dosemeden sonra cihazin soluklanmasi icin beklenecek sure. */
    public long breatherMillis() {
        return this == GENTLE ? 25 : 0;
    }
}
