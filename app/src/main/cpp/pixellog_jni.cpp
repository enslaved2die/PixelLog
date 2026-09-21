#include "CameraStreamManager.h"
#include <jni.h>
#include <android/native_window_jni.h>

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_pixellog_nativebridge_PixelLogEngine_nativeCreate(
    JNIEnv* env,
    jobject /* this */,
    jint width,
    jint height,
    jint bayerPattern) {
    auto* manager = new CameraStreamManager();
    if (!manager->initialize(width, height, static_cast<BayerPattern>(bayerPattern))) {
        delete manager;
        return 0;
    }
    return reinterpret_cast<jlong>(manager);
}

JNIEXPORT void JNICALL
Java_com_pixellog_nativebridge_PixelLogEngine_nativeDestroy(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    auto* manager = reinterpret_cast<CameraStreamManager*>(handle);
    if (manager) {
        manager->release();
        delete manager;
    }
}

JNIEXPORT jobject JNICALL
Java_com_pixellog_nativebridge_PixelLogEngine_nativeGetCameraSurface(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    auto* manager = reinterpret_cast<CameraStreamManager*>(handle);
    if (!manager) return nullptr;

    ANativeWindow* window = manager->getCameraSurface();
    if (!window) return nullptr;

    return ANativeWindow_toSurface(env, window);
}

JNIEXPORT void JNICALL
Java_com_pixellog_nativebridge_PixelLogEngine_nativeSetEncoderSurface(
    JNIEnv* env,
    jobject /* this */,
    jlong handle,
    jobject surface) {
    auto* manager = reinterpret_cast<CameraStreamManager*>(handle);
    if (!manager) return;

    ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    manager->setEncoderSurface(window);
    if (window) ANativeWindow_release(window);
}

JNIEXPORT void JNICALL
Java_com_pixellog_nativebridge_PixelLogEngine_nativeSetDisplaySurface(
    JNIEnv* env,
    jobject /* this */,
    jlong handle,
    jobject surface) {
    auto* manager = reinterpret_cast<CameraStreamManager*>(handle);
    if (!manager) return;

    ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    manager->setDisplaySurface(window);
    if (window) ANativeWindow_release(window);
}

JNIEXPORT jboolean JNICALL
Java_com_pixellog_nativebridge_PixelLogEngine_nativeLoadDisplayLut(
    JNIEnv* env,
    jobject /* this */,
    jlong handle,
    jbyteArray lutBytes) {
    auto* manager = reinterpret_cast<CameraStreamManager*>(handle);
    if (!manager || !lutBytes) return JNI_FALSE;

    jsize len = env->GetArrayLength(lutBytes);
    jbyte* bytes = env->GetByteArrayElements(lutBytes, nullptr);
    bool ok = manager->loadDisplayLut(reinterpret_cast<const char*>(bytes), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(lutBytes, bytes, JNI_ABORT);

    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_pixellog_nativebridge_PixelLogEngine_nativeUpdateMetadata(
    JNIEnv* env,
    jobject /* this */,
    jlong handle,
    jfloatArray blackLevelArr,
    jfloat whiteLevel,
    jfloatArray gainsArr,
    jfloatArray colorMatrixArr) {
    auto* manager = reinterpret_cast<CameraStreamManager*>(handle);
    if (!manager) return;

    SensorFrameMetadata meta;
    memset(&meta, 0, sizeof(meta));
    meta.whiteLevel = whiteLevel;

    if (blackLevelArr) {
        jfloat* bl = env->GetFloatArrayElements(blackLevelArr, nullptr);
        meta.dynamicBlackLevel = { bl[0], bl[1], bl[2], bl[3] };
        env->ReleaseFloatArrayElements(blackLevelArr, bl, JNI_ABORT);
    } else {
        meta.dynamicBlackLevel = { 256.0f, 256.0f, 256.0f, 256.0f };
    }

    if (gainsArr) {
        jfloat* g = env->GetFloatArrayElements(gainsArr, nullptr);
        meta.colorCorrectionGains[0] = g[0];
        meta.colorCorrectionGains[1] = g[1];
        meta.colorCorrectionGains[2] = g[2];
        meta.colorCorrectionGains[3] = g[3];
        env->ReleaseFloatArrayElements(gainsArr, g, JNI_ABORT);
    }

    if (colorMatrixArr) {
        jfloat* cm = env->GetFloatArrayElements(colorMatrixArr, nullptr);
        for (int i = 0; i < 9; ++i) meta.colorTransformMatrix[i] = cm[i];
        env->ReleaseFloatArrayElements(colorMatrixArr, cm, JNI_ABORT);
    } else {
        meta.colorTransformMatrix[0] = 1.0f;
        meta.colorTransformMatrix[4] = 1.0f;
        meta.colorTransformMatrix[8] = 1.0f;
    }

    manager->updateFrameMetadata(meta);
}

JNIEXPORT void JNICALL
Java_com_pixellog_nativebridge_PixelLogEngine_nativeSetLutEnabled(
    JNIEnv* env,
    jobject /* this */,
    jlong handle,
    jboolean enabled) {
    auto* manager = reinterpret_cast<CameraStreamManager*>(handle);
    if (!manager) return;
    manager->setLutEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_pixellog_nativebridge_PixelLogEngine_nativeSetLogCurveType(
    JNIEnv* env,
    jobject /* this */,
    jlong handle,
    jint type) {
    auto* manager = reinterpret_cast<CameraStreamManager*>(handle);
    if (!manager) return;
    manager->setLogCurveType(type);
}

JNIEXPORT void JNICALL
Java_com_pixellog_nativebridge_PixelLogEngine_nativeSetExposureGain(
    JNIEnv* env,
    jobject /* this */,
    jlong handle,
    jfloat gain) {
    auto* manager = reinterpret_cast<CameraStreamManager*>(handle);
    if (!manager) return;
    manager->setExposureGain(gain);
}

} // extern "C"
