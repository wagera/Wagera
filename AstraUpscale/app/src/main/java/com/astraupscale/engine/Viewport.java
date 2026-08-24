package com.astraupscale.engine;

/**
 * Bir goruntu uzerindeki bakis penceresi: konum ve yakinlik.
 *
 * <p>Android'e bagli hicbir sey icermez; bu bilerek boyledir. Yakinlastirma
 * ve kaydirma matematigi gozle denetlenmesi zor, yanlis yapilmasi kolay bir
 * istir: bir isaret hatasi goruntuyu parmagin altindan kaydirir, bir sinir
 * hatasi goruntunun disini gosterir. Saf bir sinif oldugu icin masaustunde
 * dogrudan sinanabilir — {@code tools/desktop/ViewportTest.java}.
 *
 * <p>Olcu birimi goruntunun kendi pikselidir. {@link #zoom} bir goruntu
 * pikselinin kac ekran pikseli ettigini soyler: 1.0 birebir, 2.0 iki kat
 * buyutulmus demektir.
 */
public final class Viewport {

    /** Goruntunun olculeri (piksel). */
    private final int imageWidth, imageHeight;
    /** Gorunum olculeri (piksel). */
    private int viewWidth, viewHeight;

    /** Pencerenin sol ust kosesi, goruntu piksel uzayinda. */
    private float x, y;
    /** Bir goruntu pikselinin kac ekran pikseli ettigi. */
    private float zoom = 1f;
    /** Goruntunun tamamini sigdiran yakinlik; alt sinir budur. */
    private float fitZoom = 1f;
    /** Ust sinir. */
    private final float maxZoom;

    public Viewport(int imageWidth, int imageHeight, float maxZoom) {
        if (imageWidth <= 0 || imageHeight <= 0) {
            throw new IllegalArgumentException("goruntu olculeri pozitif olmali");
        }
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.maxZoom = maxZoom;
    }

    /** Gorunum boyutu degistiginde cagrilir; pencereyi ortalar. */
    public void setViewSize(int width, int height) {
        this.viewWidth = width;
        this.viewHeight = height;
        if (width <= 0 || height <= 0) return;
        fitZoom = Math.min(width / (float) imageWidth, height / (float) imageHeight);
        zoom = fitZoom;
        x = (imageWidth - width / zoom) / 2f;
        y = (imageHeight - height / zoom) / 2f;
        clamp();
    }

    public float x() { return x; }
    public float y() { return y; }
    public float zoom() { return zoom; }
    public float fitZoom() { return fitZoom; }

    /** Sigdirma yakinligina gore kac kat; baslikta gosterilir. */
    public float factor() { return fitZoom <= 0 ? 1f : zoom / fitZoom; }

    public boolean isOneToOne() { return Math.abs(zoom - 1f) < 0.01f; }

    /** Pencereyi ekran pikseli cinsinden kaydirir. */
    public void panBy(float screenDx, float screenDy) {
        x += screenDx / zoom;
        y += screenDy / zoom;
        clamp();
    }

    /**
     * Verilen EKRAN noktasini sabit tutarak yakinligi degistirir.
     *
     * <p>Sikistirma hareketinde parmaklarin arasindaki nokta yerinde
     * kalmalidir; yoksa goruntu parmagin altindan kacar. Bunun icin once o
     * noktanin altindaki goruntu pikseli bulunur, yakinlik degistirilir ve
     * pencere o piksel yine ayni ekran noktasina denk gelecek sekilde
     * kaydirilir.
     *
     * @return yakinlik gercekten degisti mi
     */
    public boolean zoomTo(float target, float focusScreenX, float focusScreenY) {
        float next = Math.max(fitZoom, Math.min(maxZoom, target));
        if (Math.abs(next - zoom) < 1e-6f) return false;

        float imageX = x + focusScreenX / zoom;
        float imageY = y + focusScreenY / zoom;
        zoom = next;
        x = imageX - focusScreenX / zoom;
        y = imageY - focusScreenY / zoom;
        clamp();
        return true;
    }

    public boolean zoomBy(float factor, float focusScreenX, float focusScreenY) {
        return zoomTo(zoom * factor, focusScreenX, focusScreenY);
    }

    /**
     * Pencereyi goruntunun disina tasmayacak sekilde sinirlar.
     *
     * <p>Goruntu gorunume tumden sigiyorsa ortalanir; sigmiyorsa kenara
     * dayanir. Ikisini ayirmamak, kucuk bir goruntunun kosede takili
     * kalmasina yol acar.
     */
    private void clamp() {
        if (viewWidth <= 0 || viewHeight <= 0) return;
        float visibleW = viewWidth / zoom;
        float visibleH = viewHeight / zoom;
        x = visibleW >= imageWidth
                ? (imageWidth - visibleW) / 2f
                : Math.max(0, Math.min(imageWidth - visibleW, x));
        y = visibleH >= imageHeight
                ? (imageHeight - visibleH) / 2f
                : Math.max(0, Math.min(imageHeight - visibleH, y));
    }

    /**
     * Okunmasi gereken bolge: {left, top, right, bottom}, goruntu sinirlari
     * icine kirpilmis.
     */
    public int[] visibleRegion() {
        int left = (int) Math.max(0, Math.floor(x));
        int top = (int) Math.max(0, Math.floor(y));
        int right = (int) Math.min(imageWidth, Math.ceil(x + viewWidth / zoom));
        int bottom = (int) Math.min(imageHeight, Math.ceil(y + viewHeight / zoom));
        return new int[]{left, top, Math.max(left, right), Math.max(top, bottom)};
    }

    /**
     * Bolgeyi okurken kullanilacak ornekleme araligi.
     *
     * <p>Bir goruntu pikseli ekranda bir pikselden kucuk gorunecekse, o
     * oranda seyrek okunur: gigapiksellik bir cikisi tam cozunurlukte
     * bellege almak ne gerekli ne mumkundur.
     */
    public int sampleSize() {
        int sample = 1;
        while (zoom * 2 <= 1f / sample && sample < 32) sample *= 2;
        return sample;
    }
}
