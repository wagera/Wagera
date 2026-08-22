package com.astraupscale.app;

/**
 * Kullanicinin sectigi "telefonu zorlama" seviyesi. Is parcacigi sayisi,
 * doseme boyutu ve dosemeler arasi soluklanma bu seviyeden turetilir.
 *
 * Kalite hicbir seviyede degismez; degisen yalnizca isin ne kadar hizli
 * yapildigi ve cihazin ne kadar isinip pil harcadigidir.
 */
public enum LoadLevel {

    GENTLE(R.string.load_gentle, R.string.load_gentle_desc),
    BALANCED(R.string.load_balanced, R.string.load_balanced_desc),
    FULL(R.string.load_full, R.string.load_full_desc);

    public final int labelRes;
    public final int descriptionRes;

    LoadLevel(int labelRes, int descriptionRes) {
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
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
