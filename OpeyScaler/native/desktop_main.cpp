// Masaustu dogrulama: sinir agi cekirdegini gercek modellerle calistirir.
// Kullanim: sr_test <param> <bin> <scale> <prepadding> <inBlob> <outBlob> <girdi.ppm> <cikti.ppm> [tile]
#include "sr_engine.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <chrono>

static bool readPpm(const char* path, std::vector<unsigned char>& rgb, int& w, int& h) {
    FILE* f = fopen(path, "rb");
    if (!f) return false;
    char magic[3] = {0};
    int maxv = 0;
    if (fscanf(f, "%2s %d %d %d", magic, &w, &h, &maxv) != 4 || strcmp(magic, "P6") != 0) {
        fclose(f);
        return false;
    }
    fgetc(f);
    rgb.resize((size_t) w * h * 3);
    size_t got = fread(rgb.data(), 1, rgb.size(), f);
    fclose(f);
    return got == rgb.size();
}

static bool writePpm(const char* path, const unsigned char* rgb, int w, int h) {
    FILE* f = fopen(path, "wb");
    if (!f) return false;
    fprintf(f, "P6\n%d %d\n255\n", w, h);
    fwrite(rgb, 1, (size_t) w * h * 3, f);
    fclose(f);
    return true;
}

int main(int argc, char** argv) {
    if (argc < 9) {
        fprintf(stderr, "kullanim: %s <param> <bin> <scale> <prepad> <inBlob> <outBlob> <in.ppm> <out.ppm> [tile]\n", argv[0]);
        return 2;
    }
    opeyscaler::ModelSpec spec;
    spec.scale = atoi(argv[3]);
    spec.prepadding = atoi(argv[4]);
    spec.inputBlob = argv[5];
    spec.outputBlob = argv[6];
    const int tile = argc > 9 ? atoi(argv[9]) : 128;
    spec.fp16 = argc > 10 ? atoi(argv[10]) != 0 : true;

    std::vector<unsigned char> src;
    int w = 0, h = 0;
    if (!readPpm(argv[7], src, w, h)) {
        fprintf(stderr, "girdi okunamadi\n");
        return 1;
    }

    opeyscaler::SrEngine engine;
    if (!engine.loadFromFiles(argv[1], argv[2], spec, false, 0)) {
        fprintf(stderr, "model yuklenemedi: %s\n", engine.lastError().c_str());
        return 1;
    }
    engine.setTileSize(tile);
    engine.setSource(src.data(), w, h);

    const int scale = spec.scale;
    std::vector<unsigned char> out((size_t) w * scale * h * scale * 3);
    const int bandRows = tile;
    auto t0 = std::chrono::steady_clock::now();
    for (int y = 0; y < h; y += bandRows) {
        const int rows = std::min(bandRows, h - y);
        unsigned char* dst = out.data() + (size_t) y * scale * (w * scale * 3);
        if (!engine.processBand(y, rows, dst)) {
            fprintf(stderr, "bant hatasi: %s\n", engine.lastError().c_str());
            return 1;
        }
        fprintf(stderr, "  bant %d/%d\n", y / bandRows + 1, (h + bandRows - 1) / bandRows);
    }
    auto t1 = std::chrono::steady_clock::now();
    double sec = std::chrono::duration<double>(t1 - t0).count();
    fprintf(stderr, "%dx%d -> %dx%d, %.2f sn (doseme %d, gpu=%d)\n",
            w, h, w * scale, h * scale, sec, tile, (int) engine.usingGpu());

    if (!writePpm(argv[8], out.data(), w * scale, h * scale)) {
        fprintf(stderr, "cikti yazilamadi\n");
        return 1;
    }
    return 0;
}
