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
Java_com_pixellog_nativebridge_PixelLogEngine_nativeUpdateFrameMetadata(
    JNIEnv* env,
    jobject /* this */,
    jlong handle,
    jlong timestampNs,
    jfloatArray blackLevelArr,
    jfloat whiteLevel,
    jfloatArray neutralPointArr,
    jfloatArray compositeMatrixArr,
    jfloat exposureGain,
    jfloatArray shadingMapArr,
    jint shadingWidth,
    jint shadingHeight) {
    auto* manager = reinterpret_cast<CameraStreamManager*>(handle);
    if (!manager) return;

    SensorFrameMetadata meta;
    meta.timestampNs = timestampNs;
    meta.whiteLevel = whiteLevel;
    meta.exposureGain = exposureGain > 0.0f ? exposureGain : 8.14587f;
    meta.hasShadingMap = false;

    if (blackLevelArr) {
        jfloat* bl = env->GetFloatArrayElements(blackLevelArr, nullptr);
        meta.dynamicBlackLevel = { bl[0], bl[1], bl[2], bl[3] };
        env->ReleaseFloatArrayElements(blackLevelArr, bl, JNI_ABORT);
    } else {
        meta.dynamicBlackLevel = { 256.0f, 256.0f, 256.0f, 256.0f };
    }

    if (neutralPointArr) {
        jfloat* np = env->GetFloatArrayElements(neutralPointArr, nullptr);
        meta.neutralColorPoint[0] = np[0];
        meta.neutralColorPoint[1] = np[1];
        meta.neutralColorPoint[2] = np[2];
        env->ReleaseFloatArrayElements(neutralPointArr, np, JNI_ABORT);
    } else {
        meta.neutralColorPoint[0] = 0.55f;
        meta.neutralColorPoint[1] = 1.0f;
        meta.neutralColorPoint[2] = 0.70f;
    }

    if (compositeMatrixArr) {
        jfloat* cm = env->GetFloatArrayElements(compositeMatrixArr, nullptr);
        for (int i = 0; i < 9; ++i) meta.compositeMatrix[i] = cm[i];
        env->ReleaseFloatArrayElements(compositeMatrixArr, cm, JNI_ABORT);
    } else {
        for (int i = 0; i < 9; ++i) meta.compositeMatrix[i] = (i % 4 == 0) ? 1.0f : 0.0f;
    }

    if (shadingMapArr && shadingWidth > 0 && shadingHeight > 0) {
        jsize len = env->GetArrayLength(shadingMapArr);
        if (len >= shadingWidth * shadingHeight * 4) {
            jfloat* sm = env->GetFloatArrayElements(shadingMapArr, nullptr);
            meta.shadingMapWidth = shadingWidth;
            meta.shadingMapHeight = shadingHeight;
            meta.shadingMapData.assign(sm, sm + len);
            meta.hasShadingMap = true;
            env->ReleaseFloatArrayElements(shadingMapArr, sm, JNI_ABORT);
        }
    }

    manager->updateFrameMetadata(meta);
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
    meta.timestampNs = 0;
    meta.whiteLevel = whiteLevel;
    meta.exposureGain = 8.14587f;
    meta.hasShadingMap = false;

    if (blackLevelArr) {
        jfloat* bl = env->GetFloatArrayElements(blackLevelArr, nullptr);
        meta.dynamicBlackLevel = { bl[0], bl[1], bl[2], bl[3] };
        env->ReleaseFloatArrayElements(blackLevelArr, bl, JNI_ABORT);
    } else {
        meta.dynamicBlackLevel = { 256.0f, 256.0f, 256.0f, 256.0f };
    }

    if (gainsArr) {
        jfloat* g = env->GetFloatArrayElements(gainsArr, nullptr);
        meta.neutralColorPoint[0] = 1.0f / (g[0] > 0.0f ? g[0] : 1.0f);
        meta.neutralColorPoint[1] = 1.0f;
        meta.neutralColorPoint[2] = 1.0f / (g[3] > 0.0f ? g[3] : 1.0f);
        env->ReleaseFloatArrayElements(gainsArr, g, JNI_ABORT);
    } else {
        meta.neutralColorPoint[0] = 0.55f;
        meta.neutralColorPoint[1] = 1.0f;
        meta.neutralColorPoint[2] = 0.70f;
    }

    if (colorMatrixArr) {
        jfloat* cm = env->GetFloatArrayElements(colorMatrixArr, nullptr);
        for (int i = 0; i < 9; ++i) meta.compositeMatrix[i] = cm[i];
        env->ReleaseFloatArrayElements(colorMatrixArr, cm, JNI_ABORT);
    } else {
        for (int i = 0; i < 9; ++i) meta.compositeMatrix[i] = (i % 4 == 0) ? 1.0f : 0.0f;
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
Java_com_pixellog_nativebridge_PixelLogEngine_nativeSetBakeLutToEncoder(
    JNIEnv* env,
    jobject /* this */,
    jlong handle,
    jboolean enabled) {
    auto* manager = reinterpret_cast<CameraStreamManager*>(handle);
    if (!manager) return;
    manager->setBakeLutToEncoder(enabled == JNI_TRUE);
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
