// Java <-> ncnn koprusu.
#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "sr_engine.h"
#include "cpu.h"

#define LOG_TAG "OpeyScalerNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

using opeyscaler::SrEngine;
using opeyscaler::ModelSpec;

namespace {

std::string toStd(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    env->ReleaseStringUTFChars(s, c);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_opeyscaler_app_NativeSr_nativeGpuAvailable(JNIEnv*, jclass) {
    return opeyscaler::gpuAvailable() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_opeyscaler_app_NativeSr_nativeCpuCount(JNIEnv*, jclass) {
    int n = ncnn::get_big_cpu_count();
    return n > 0 ? n : ncnn::get_cpu_count();
}

JNIEXPORT jlong JNICALL
Java_com_opeyscaler_app_NativeSr_nativeCreate(JNIEnv* env, jclass, jobject assetManager,
                                              jstring paramAsset, jstring binAsset,
                                              jint scale, jint prepadding,
                                              jstring inBlob, jstring outBlob,
                                              jobjectArray gapBlobs, jint align, jint fixedInput,
                                              jboolean useGpu, jint threads, jint tile) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (mgr == nullptr) return 0;

    ModelSpec spec;
    spec.scale = scale;
    spec.prepadding = prepadding;
    spec.inputBlob = toStd(env, inBlob);
    spec.outputBlob = toStd(env, outBlob);
    spec.align = align;
    spec.fixedInput = fixedInput;
    if (gapBlobs != nullptr) {
        const jsize n = env->GetArrayLength(gapBlobs);
        for (jsize i = 0; i < n; i++) {
            jstring s = (jstring) env->GetObjectArrayElement(gapBlobs, i);
            spec.gapBlobs.push_back(toStd(env, s));
            env->DeleteLocalRef(s);
        }
    }

    SrEngine* engine = new SrEngine();
    if (!engine->loadFromAssets(mgr, toStd(env, paramAsset), toStd(env, binAsset), spec,
                                useGpu == JNI_TRUE, threads)) {
        LOGI("model yuklenemedi: %s", engine->lastError().c_str());
        delete engine;
        return 0;
    }
    engine->setTileSize(tile);
    LOGI("model hazir: scale=%d prepad=%d tile=%d gpu=%d", scale, prepadding, tile,
         (int) engine->usingGpu());
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jboolean JNICALL
Java_com_opeyscaler_app_NativeSr_nativeUsingGpu(JNIEnv*, jclass, jlong handle) {
    SrEngine* e = reinterpret_cast<SrEngine*>(handle);
    return (e && e->usingGpu()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_opeyscaler_app_NativeSr_nativeSetSource(JNIEnv* env, jclass, jlong handle,
                                                 jbyteArray rgb, jint width, jint height) {
    SrEngine* e = reinterpret_cast<SrEngine*>(handle);
    if (!e) return;
    jbyte* data = env->GetByteArrayElements(rgb, nullptr);
    e->setSource(reinterpret_cast<const unsigned char*>(data), width, height);
    env->ReleaseByteArrayElements(rgb, data, JNI_ABORT);
}

JNIEXPORT jboolean JNICALL
Java_com_opeyscaler_app_NativeSr_nativeProcessBand(JNIEnv* env, jclass, jlong handle,
                                                   jint srcY0, jint srcRows, jbyteArray out) {
    SrEngine* e = reinterpret_cast<SrEngine*>(handle);
    if (!e) return JNI_FALSE;
    jbyte* dst = env->GetByteArrayElements(out, nullptr);
    bool ok = e->processBand(srcY0, srcRows, reinterpret_cast<unsigned char*>(dst));
    env->ReleaseByteArrayElements(out, dst, ok ? 0 : JNI_ABORT);
    if (!ok) LOGI("bant hatasi: %s", e->lastError().c_str());
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_opeyscaler_app_NativeSr_nativeSetSourceFile(JNIEnv* env, jclass, jlong handle,
                                                     jstring path, jint width, jint height) {
    SrEngine* e = reinterpret_cast<SrEngine*>(handle);
    if (!e) return JNI_FALSE;
    return e->setSourceFile(toStd(env, path), width, height) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_opeyscaler_app_NativeSr_nativePrepareStage(JNIEnv*, jclass, jlong handle, jint stage) {
    SrEngine* e = reinterpret_cast<SrEngine*>(handle);
    if (!e) return JNI_FALSE;
    bool ok = e->prepareStage(stage);
    if (!ok) LOGI("hazirlik hatasi: %s", e->lastError().c_str());
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_opeyscaler_app_NativeSr_nativeTileSize(JNIEnv*, jclass, jlong handle) {
    SrEngine* e = reinterpret_cast<SrEngine*>(handle);
    return e ? e->tileSize() : 0;
}

JNIEXPORT jstring JNICALL
Java_com_opeyscaler_app_NativeSr_nativeLastError(JNIEnv* env, jclass, jlong handle) {
    SrEngine* e = reinterpret_cast<SrEngine*>(handle);
    return env->NewStringUTF(e ? e->lastError().c_str() : "");
}

JNIEXPORT void JNICALL
Java_com_opeyscaler_app_NativeSr_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    SrEngine* e = reinterpret_cast<SrEngine*>(handle);
    delete e;
}

}  // extern "C"
