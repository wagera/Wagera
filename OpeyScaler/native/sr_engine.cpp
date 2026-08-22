#include "sr_engine.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "net.h"
#include "cpu.h"
#include "mat.h"

#ifdef __ANDROID__
#include <android/asset_manager.h>
#endif

namespace opeyscaler {

static bool g_gpuChecked = false;
static bool g_gpuOk = false;

bool gpuAvailable() {
#if NCNN_VULKAN
    if (!g_gpuChecked) {
        g_gpuChecked = true;
        g_gpuOk = ncnn::create_gpu_instance() == 0 && ncnn::get_gpu_count() > 0;
    }
    return g_gpuOk;
#else
    return false;
#endif
}

void releaseGpu() {
#if NCNN_VULKAN
    if (g_gpuOk) {
        ncnn::destroy_gpu_instance();
        g_gpuOk = false;
        g_gpuChecked = false;
    }
#endif
}

SrEngine::SrEngine() : net_(new ncnn::Net()) { }

SrEngine::~SrEngine() {
    delete net_;
}

bool SrEngine::finishInit(const ModelSpec& spec, bool useGpu, int threads) {
    spec_ = spec;
    gpu_ = false;
#if NCNN_VULKAN
    if (useGpu && gpuAvailable()) {
        net_->opt.use_vulkan_compute = true;
        net_->set_vulkan_device(0);
        gpu_ = true;
    }
#else
    (void) useGpu;
#endif
    net_->opt.num_threads = threads > 0 ? threads : ncnn::get_big_cpu_count();
    net_->opt.use_fp16_packed = spec.fp16;
    net_->opt.use_fp16_storage = spec.fp16;
    net_->opt.use_fp16_arithmetic = false;  // dogruluk icin aritmetik fp32
    net_->opt.use_int8_inference = false;
    return true;
}

bool SrEngine::loadFromFiles(const std::string& paramPath, const std::string& binPath,
                             const ModelSpec& spec, bool useGpu, int threads) {
    finishInit(spec, useGpu, threads);
    if (net_->load_param(paramPath.c_str()) != 0) {
        error_ = "param yuklenemedi: " + paramPath;
        return false;
    }
    if (net_->load_model(binPath.c_str()) != 0) {
        error_ = "model yuklenemedi: " + binPath;
        return false;
    }
    ready_ = true;
    return true;
}

#ifdef __ANDROID__
bool SrEngine::loadFromAssets(AAssetManager* mgr, const std::string& paramAsset,
                              const std::string& binAsset, const ModelSpec& spec,
                              bool useGpu, int threads) {
    finishInit(spec, useGpu, threads);
    if (net_->load_param(mgr, paramAsset.c_str()) != 0) {
        error_ = "param yuklenemedi: " + paramAsset;
        return false;
    }
    if (net_->load_model(mgr, binAsset.c_str()) != 0) {
        error_ = "model yuklenemedi: " + binAsset;
        return false;
    }
    ready_ = true;
    return true;
}
#endif

void SrEngine::setSource(const unsigned char* rgb, int width, int height) {
    srcW_ = width;
    srcH_ = height;
    src_.assign(rgb, rgb + (size_t) width * height * 3);
}

bool SrEngine::processBand(int srcY0, int srcRows, unsigned char* dst) {
    if (!ready_ || src_.empty()) {
        error_ = "motor hazir degil";
        return false;
    }
    if (srcY0 < 0 || srcRows <= 0 || srcY0 + srcRows > srcH_) {
        error_ = "gecersiz bant";
        return false;
    }
    const int dstStride = srcW_ * spec_.scale * 3;
    for (int x0 = 0; x0 < srcW_; x0 += tile_) {
        const int tileW = std::min(tile_, srcW_ - x0);
        if (!runTile(x0, tileW, srcY0, srcRows, dst, dstStride)) return false;
    }
    return true;
}

bool SrEngine::runTile(int x0, int tileW, int srcY0, int srcRows,
                       unsigned char* dst, int dstStride) {
    const int scale = spec_.scale;
    const int pre = spec_.prepadding;

    // Gercek goruntuden alinabilen baglam
    const int sx0 = std::max(0, x0 - pre);
    const int sy0 = std::max(0, srcY0 - pre);
    const int sx1 = std::min(srcW_, x0 + tileW + pre);
    const int sy1 = std::min(srcH_, srcY0 + srcRows + pre);

    ncnn::Mat in = ncnn::Mat::from_pixels_roi(src_.data(), ncnn::Mat::PIXEL_RGB,
                                              srcW_, srcH_, sx0, sy0, sx1 - sx0, sy1 - sy0);

    // Goruntu kenarlarinda eksik kalan baglami kenar tekrariyla tamamla
    const int padLeft = pre - (x0 - sx0);
    const int padTop = pre - (srcY0 - sy0);
    const int padRight = pre - (sx1 - (x0 + tileW));
    const int padBottom = pre - (sy1 - (srcY0 + srcRows));
    if (padLeft > 0 || padTop > 0 || padRight > 0 || padBottom > 0) {
        ncnn::Mat padded;
        ncnn::copy_make_border(in, padded, padTop, padBottom, padLeft, padRight,
                               ncnn::BORDER_REPLICATE, 0.f, net_->opt);
        in = padded;
    }

    const float normIn[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
    in.substract_mean_normalize(0, normIn);

    ncnn::Mat out;
    {
        ncnn::Extractor ex = net_->create_extractor();
        ex.set_light_mode(true);
        if (ex.input(spec_.inputBlob.c_str(), in) != 0) {
            error_ = "giris katmani bulunamadi: " + spec_.inputBlob;
            return false;
        }
        if (ex.extract(spec_.outputBlob.c_str(), out) != 0) {
            error_ = "cikis katmani bulunamadi: " + spec_.outputBlob;
            return false;
        }
    }
    if (out.empty()) {
        error_ = "model bos cikti uretti";
        return false;
    }

    /*
     * Kirpma payini olculen cikis boyutundan turetiyoruz. Real-ESRGAN'da bu
     * dogrudan prepadding*scale'dir; Real-CUGAN gibi ic kirpma iceren modellerde
     * ise daha kucuk cikar. Olcerek turetmek her iki aileyi de dogru calistirir.
     */
    const int borderX = (out.w - tileW * scale) / 2;
    const int borderY = (out.h - srcRows * scale) / 2;
    if (borderX < 0 || borderY < 0) {
        error_ = "cikis dosemesi beklenenden kucuk";
        return false;
    }

    const float* planeR = out.channel(0);
    const float* planeG = out.channel(1);
    const float* planeB = out.channel(2);
    const int outRows = srcRows * scale;
    const int outCols = tileW * scale;
    const int dstX = x0 * scale;

    for (int y = 0; y < outRows; y++) {
        const int sy = y + borderY;
        const float* r = planeR + (size_t) sy * out.w + borderX;
        const float* g = planeG + (size_t) sy * out.w + borderX;
        const float* b = planeB + (size_t) sy * out.w + borderX;
        unsigned char* d = dst + (size_t) y * dstStride + (size_t) dstX * 3;
        for (int x = 0; x < outCols; x++) {
            float rv = r[x] * 255.f + 0.5f;
            float gv = g[x] * 255.f + 0.5f;
            float bv = b[x] * 255.f + 0.5f;
            d[0] = (unsigned char) (rv < 0 ? 0 : (rv > 255 ? 255 : rv));
            d[1] = (unsigned char) (gv < 0 ? 0 : (gv > 255 ? 255 : gv));
            d[2] = (unsigned char) (bv < 0 ? 0 : (bv > 255 ? 255 : bv));
            d += 3;
        }
    }
    return true;
}

}  // namespace opeyscaler
