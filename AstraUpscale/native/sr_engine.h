// AstraUpscale - ncnn tabanli sinir agi super-cozunurluk cekirdegi.
// Ayni dosya hem Android (JNI) hem masaustu dogrulama kosucusu tarafindan kullanilir.
#ifndef ASTRAUPSCALE_SR_ENGINE_H
#define ASTRAUPSCALE_SR_ENGINE_H

#include <string>
#include <vector>

namespace ncnn {
class Net;
class Mat;
}

#ifdef __ANDROID__
struct AAssetManager;
#endif

namespace astraupscale {

struct ModelSpec {
    int scale = 4;              // modelin sabit buyutme orani
    int prepadding = 10;        // dosemeler icin baglam payi (kaynak piksel)
    std::string inputBlob = "data";
    std::string outputBlob = "output";

    /**
     * Real-CUGAN gibi SE (squeeze-excitation) blogu iceren modellerde,
     * tum goruntu uzerinden ortalanmasi gereken kuresel havuzlama blob'lari.
     * Bos ise model tek gecisle calisir.
     */
    std::vector<std::string> gapBlobs;

    /** Doseme boyutunun katina yuvarlanmasi gereken deger (Real-CUGAN icin 2 veya 4). */
    int align = 1;

    /**
     * Sifirdan buyukse modele her zaman tam olarak bu kenar uzunlugunda kare
     * girdi verilir (SwinIR gibi sabit sekilli izlenmis modeller icin).
     */
    int fixedInput = 0;

    /** Ara katmanlarda fp16 kullan (mobilde hiz/bellek icin varsayilan). */
    bool fp16 = true;
};

class SrEngine {
public:
    SrEngine();
    ~SrEngine();

    bool loadFromFiles(const std::string& paramPath, const std::string& binPath,
                       const ModelSpec& spec, bool useGpu, int threads);

#ifdef __ANDROID__
    bool loadFromAssets(AAssetManager* mgr, const std::string& paramAsset, const std::string& binAsset,
                        const ModelSpec& spec, bool useGpu, int threads);
#endif

    /** Kaynak goruntuyu (RGB, satir adimi w*3) bellege kopyalar. */
    void setSource(const unsigned char* rgb, int width, int height);

    /**
     * Kaynagi ham RGB dosyasindan okur (cok asamali buyutmede ara goruntu
     * bellege sigmayabilir). Dosya duz w*h*3 bayt olmalidir.
     */
    bool setSourceFile(const std::string& path, int width, int height);

    /**
     * SE modellerinde kuresel havuzlama degerlerini tum goruntu uzerinden
     * hesaplar. {@code stage} 0..gapStages()-1 arasindadir ve sirayla
     * cagrilmalidir. SE olmayan modellerde gerek yoktur.
     */
    bool prepareStage(int stage);

    int gapStages() const { return (int) spec_.gapBlobs.size(); }

    /** Kaynagin [srcY0, srcY0+srcRows) bandini buyutur. */
    bool processBand(int srcY0, int srcRows, unsigned char* dst);

    int scale() const { return spec_.scale; }
    void setTileSize(int tile);
    int tileSize() const { return tile_; }
    bool usingGpu() const { return gpu_; }
    const std::string& lastError() const { return error_; }

private:
    bool finishInit(const ModelSpec& spec, bool useGpu, int threads);
    /** Bir dosemeyi hazirlar: baglam + hizalama dolgusu uygulanmis girdi. */
    bool buildTileInput(int x0, int tileW, int srcY0, int srcRows, ncnn::Mat& out,
                        int& padLeft, int& padTop);
    bool runTile(int x0, int tileW, int srcY0, int srcRows, unsigned char* dst, int dstStride);
    /** Kaynaktan bir bolge okur (bellek ya da dosya). */
    bool readRegion(int x0, int y0, int w, int h, unsigned char* dst) const;

    ncnn::Net* net_ = nullptr;
    ModelSpec spec_;
    std::vector<unsigned char> src_;
    std::string srcPath_;
    int srcFd_ = -1;
    int srcW_ = 0, srcH_ = 0;
    int tile_ = 128;
    bool gpu_ = false;
    bool ready_ = false;
    std::vector<ncnn::Mat> gaps_;
    mutable std::string error_;
};

bool gpuAvailable();
void releaseGpu();

}  // namespace astraupscale

#endif
