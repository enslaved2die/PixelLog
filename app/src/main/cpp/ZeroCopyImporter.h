#pragma once

#include "include/PixelLogCommon.h"

#include <atomic>

class ZeroCopyImporter {
public:
    ZeroCopyImporter();
    ~ZeroCopyImporter();

    bool initialize(EGLDisplay display);
    void release();

    /**
     * Imports an AHardwareBuffer containing RAW_SENSOR / RAW16 data into an OpenGL ES texture
     * using EGLImage and Linux DMA-BUF or Android native client buffer extensions.
     */
    GLuint importHardwareBufferToTexture(AHardwareBuffer* hardwareBuffer, 
                                         int32_t width, 
                                         int32_t height, 
                                         int32_t stride,
                                         float blackLevel = 256.0f,
                                         float whiteLevel = 4095.0f);

    void destroyTexture(GLuint textureId);

    float getSceneExposureDelta() const { return mLastSceneDeltaEv.load(std::memory_order_relaxed); }

private:
    EGLDisplay mEglDisplay;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC mEglGetNativeClientBufferANDROID;
    PFNEGLCREATEIMAGEKHRPROC mEglCreateImageKHR;
    PFNEGLDESTROYIMAGEKHRPROC mEglDestroyImageKHR;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC mGlEGLImageTargetTexture2DOES;

    bool mHasDmaBufImport;
    bool mHasNativeBufferImport;

    GLuint mFallbackTexture;
    int32_t mFallbackWidth;
    int32_t mFallbackHeight;

    std::atomic<float> mLastSceneDeltaEv{0.0f};
};
