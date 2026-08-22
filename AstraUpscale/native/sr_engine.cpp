#include "sr_engine.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include <fcntl.h>
#include <unistd.h>

#include "net.h"
#include "cpu.h"
#include "mat.h"

#ifdef __ANDROID__
#include <android/asset_manager.h>
#endif

namespace astraupscale {

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
    if (srcFd_ >= 0) close(srcFd_);
    delete net_;
}

void SrEngine::setTileSize(int tile) {
    if (spec_.fixedInput > 0) {
        // Sabit sekilli modellerde doseme boyutu girdiden turetilir.
        tile_ = std::max(1, spec_.fixedInput - 2 * spec_.prepadding);
    } else {
        tile_ = tile > 0 ? tile : 1;
    }
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
    gaps_.assign(spec_.gapBlobs.size(), ncnn::Mat());
    setTileSize(tile_);
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
    if (srcFd_ >= 0) {
        close(srcFd_);
        srcFd_ = -1;
    }
    srcW_ = width;
    srcH_ = height;
    src_.assign(rgb, rgb + (size_t) width * height * 3);
}

bool SrEngine::setSourceFile(const std::string& path, int width, int height) {
    if (srcFd_ >= 0) close(srcFd_);
    srcFd_ = open(path.c_str(), O_RDONLY);
    if (srcFd_ < 0) {
        error_ = "ara dosya acilamadi: " + path;
        return false;
    }
    src_.clear();
    src_.shrink_to_fit();
    srcPath_ = path;
    srcW_ = width;
    srcH_ = height;
    return true;
}

bool SrEngine::readRegion(int x0, int y0, int w, int h, unsigned char* dst) const {
    const size_t rowBytes = (size_t) w * 3;
    if (srcFd_ >= 0) {
        for (int y = 0; y < h; y++) {
            off_t off = ((off_t) (y0 + y) * srcW_ + x0) * 3;
            size_t got = 0;
            while (got < rowBytes) {
                ssize_t n = pread(srcFd_, dst + (size_t) y * rowBytes + got, rowBytes - got,
                                  off + (off_t) got);
                if (n <= 0) {
                    error_ = "ara dosya okunamadi";
                    return false;
                }
                got += (size_t) n;
            }
        }
        return true;
    }
    for (int y = 0; y < h; y++) {
        const unsigned char* s = src_.data() + (((size_t) (y0 + y) * srcW_) + x0) * 3;
        memcpy(dst + (size_t) y * rowBytes, s, rowBytes);
    }
    return true;
}

bool SrEngine::buildTileInput(int x0, int tileW, int srcY0, int srcRows, ncnn::Mat& out,
                              int& padLeft, int& padTop) {
    const int pre = spec_.prepadding;

    // Gercek goruntuden alinabilen baglam
    const int sx0 = std::max(0, x0 - pre);
    const int sy0 = std::max(0, srcY0 - pre);
    const int sx1 = std::min(srcW_, x0 + tileW + pre);
    const int sy1 = std::min(srcH_, srcY0 + srcRows + pre);
    const int rw = sx1 - sx0, rh = sy1 - sy0;

    std::vector<unsigned char> region((size_t) rw * rh * 3);
    if (!readRegion(sx0, sy0, rw, rh, region.data())) return false;

    ncnn::Mat in = ncnn::Mat::from_pixels(region.data(), ncnn::Mat::PIXEL_RGB, rw, rh);

    padLeft = pre - (x0 - sx0);
    padTop = pre - (srcY0 - sy0);
    int padRight = pre - (sx1 - (x0 + tileW));
    int padBottom = pre - (sy1 - (srcY0 + srcRows));

    if (spec_.fixedInput > 0) {
        // Model her zaman ayni boyutta girdi ister: eksigi sag/alttan tamamla.
        padRight = spec_.fixedInput - (rw + padLeft);
        padBottom = spec_.fixedInput - (rh + padTop);
        if (padRight < 0 || padBottom < 0) {
            error_ = "doseme sabit girdi boyutundan buyuk";
            return false;
        }
    } else if (spec_.align > 1) {
        // Real-CUGAN: doseme icerigi align katina yuvarlanmali.
        const int alignedW = (tileW + spec_.align - 1) / spec_.align * spec_.align;
        const int alignedH = (srcRows + spec_.align - 1) / spec_.align * spec_.align;
        padRight += alignedW - tileW;
        padBottom += alignedH - srcRows;
    }

    if (padLeft > 0 || padTop > 0 || padRight > 0 || padBottom > 0) {
        ncnn::Mat padded;
        ncnn::copy_make_border(in, padded, padTop, padBottom, padLeft, padRight,
                               ncnn::BORDER_REPLICATE, 0.f, net_->opt);
        in = padded;
    }

    const float normIn[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
    in.substract_mean_normalize(0, normIn);
    out = in;
    return true;
}

bool SrEngine::prepareStage(int stage) {
    if (!ready_) {
        error_ = "motor hazir degil";
        return false;
    }
    if (stage < 0 || stage >= (int) spec_.gapBlobs.size()) return true;

    // Tek dosemeye sigan goruntude kuresel deger zaten dogru hesaplanir.
    if (srcW_ <= tile_ && srcH_ <= tile_) {
        gaps_[stage] = ncnn::Mat();
        return true;
    }

    ncnn::Mat sum;
    int tiles = 0;
    for (int y0 = 0; y0 < srcH_; y0 += tile_) {
        const int rows = std::min(tile_, srcH_ - y0);
        for (int x0 = 0; x0 < srcW_; x0 += tile_) {
            const int tileW = std::min(tile_, srcW_ - x0);
            ncnn::Mat in;
            int padLeft = 0, padTop = 0;
            if (!buildTileInput(x0, tileW, y0, rows, in, padLeft, padTop)) return false;

            ncnn::Extractor ex = net_->create_extractor();
            ex.set_light_mode(true);
            ex.input(spec_.inputBlob.c_str(), in);
            for (int g = 0; g < stage; g++) {
                if (!gaps_[g].empty()) ex.input(spec_.gapBlobs[g].c_str(), gaps_[g]);
            }
            ncnn::Mat feat;
            if (ex.extract(spec_.gapBlobs[stage].c_str(), feat) != 0 || feat.empty()) {
                error_ = "kuresel havuzlama okunamadi: " + spec_.gapBlobs[stage];
                return false;
            }
            if (sum.empty()) {
                sum.create_like(feat);
                sum.fill(0.f);
            }
            const int len = (int) feat.total();
            for (int i = 0; i < len; i++) sum[i] += feat[i];
            tiles++;
        }
    }
    if (tiles > 0 && !sum.empty()) {
        const int len = (int) sum.total();
        for (int i = 0; i < len; i++) sum[i] /= tiles;
    }
    gaps_[stage] = sum;
    return true;
}

bool SrEngine::processBand(int srcY0, int srcRows, unsigned char* dst) {
    if (!ready_ || (src_.empty() && srcFd_ < 0)) {
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

    ncnn::Mat in;
    int padLeft = 0, padTop = 0;
    if (!buildTileInput(x0, tileW, srcY0, srcRows, in, padLeft, padTop)) return false;
    const int inW = in.w, inH = in.h;

    ncnn::Mat out;
    {
        ncnn::Extractor ex = net_->create_extractor();
        ex.set_light_mode(true);
        if (ex.input(spec_.inputBlob.c_str(), in) != 0) {
            error_ = "giris katmani bulunamadi: " + spec_.inputBlob;
            return false;
        }
        for (size_t g = 0; g < gaps_.size(); g++) {
            if (!gaps_[g].empty()) ex.input(spec_.gapBlobs[g].c_str(), gaps_[g]);
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
     * Modelin ic kirpma payini olculen cikistan turetiyoruz: Real-ESRGAN'da bu
     * sifir, Real-CUGAN'da ise sifirdan buyuktur. Dolgu sag/altta hizalama
     * nedeniyle asimetrik olabildigi icin kirpma noktasi sol/ust dolgudan
     * hesaplanir.
     */
    /*
     * Dosemenin solundaki/ustundeki toplam baglam her zaman tam olarak
     * prepadding kadardir (bir kismi gercek komsu piksel, kalani kenar
     * tekrari). Modelin kendi ic kirpmasi ise olculen cikis boyutundan
     * turetilir: Real-ESRGAN'da sifir, Real-CUGAN'da prepadding*scale kadar.
     */
    const int cropX = (inW * scale - out.w) / 2;
    const int cropY = (inH * scale - out.h) / 2;
    const int borderX = spec_.prepadding * scale - cropX;
    const int borderY = spec_.prepadding * scale - cropY;
    const int outRows = srcRows * scale;
    const int outCols = tileW * scale;
    if (borderX < 0 || borderY < 0
            || borderX + outCols > out.w || borderY + outRows > out.h) {
        char buf[256];
        snprintf(buf, sizeof(buf),
                 "cikis dosemesi beklenenden kucuk (in %dx%d -> out %dx%d, pad %d/%d, "
                 "crop %d/%d, border %d/%d, gereken %dx%d)",
                 inW, inH, out.w, out.h, padLeft, padTop, cropX, cropY,
                 borderX, borderY, outCols, outRows);
        error_ = buf;
        return false;
    }

    const float* planeR = out.channel(0);
    const float* planeG = out.channel(1);
    const float* planeB = out.channel(2);
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

}  // namespace astraupscale
