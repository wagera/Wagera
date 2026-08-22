// OpeyScaler - ncnn tabanli sinir agi super-cozunurluk cekirdegi.
// Ayni dosya hem Android (JNI) hem masaustu dogrulama kosucusu tarafindan kullanilir.
#ifndef OPEYSCALER_SR_ENGINE_H
#define OPEYSCALER_SR_ENGINE_H

#include <string>
#include <vector>

namespace ncnn { class Net; }

#ifdef __ANDROID__
struct AAssetManager;
#endif

namespace opeyscaler {

struct ModelSpec {
    int scale = 4;              // modelin sabit buyutme orani
    int prepadding = 10;        // dosemeler icin baglam payi (kaynak piksel)
    std::string inputBlob = "data";
    std::string outputBlob = "output";
    /** Ara katmanlarda fp16 kullan (mobilde hiz/bellek icin varsayilan). */
    bool fp16 = true;
};

class SrEngine {
public:
    SrEngine();
    ~SrEngine();

    /** Dosya yolundan model yukler (masaustu / cihaz depolamasi). */
    bool loadFromFiles(const std::string& paramPath, const std::string& binPath,
                       const ModelSpec& spec, bool useGpu, int threads);

#ifdef __ANDROID__
    /** APK icindeki assets klasorunden model yukler. */
    bool loadFromAssets(AAssetManager* mgr, const std::string& paramAsset, const std::string& binAsset,
                        const ModelSpec& spec, bool useGpu, int threads);
#endif

    /** Kaynak goruntuyu (RGB, satir adimi w*3) motora verir; kopyalanir. */
    void setSource(const unsigned char* rgb, int width, int height);

    /**
     * Kaynagin [srcY0, srcY0+srcRows) satir bandini buyutur.
     * Cikis tamponu (width*scale) x (srcRows*scale) x 3 bayt olmalidir.
     * Doseme sinirlarinda gorunur ek yeri olusmamasi icin baglam pikselleri
     * tum kaynak goruntuden alinir.
     */
    bool processBand(int srcY0, int srcRows, unsigned char* dst);

    int scale() const { return spec_.scale; }
    int sourceWidth() const { return srcW_; }
    int sourceHeight() const { return srcH_; }
    void setTileSize(int tile) { tile_ = tile > 0 ? tile : 1; }
    int tileSize() const { return tile_; }
    bool usingGpu() const { return gpu_; }
    const std::string& lastError() const { return error_; }

private:
    bool finishInit(const ModelSpec& spec, bool useGpu, int threads);
    bool runTile(int x0, int tileW, int srcY0, int srcRows, unsigned char* dst, int dstStride);

    ncnn::Net* net_ = nullptr;
    ModelSpec spec_;
    std::vector<unsigned char> src_;
    int srcW_ = 0, srcH_ = 0;
    int tile_ = 128;
    bool gpu_ = false;
    bool ready_ = false;
    std::string error_;
};

/** Vulkan ornegini olusturur/yok eder (surec basina bir kez). */
bool gpuAvailable();
void releaseGpu();

}  // namespace opeyscaler

#endif
