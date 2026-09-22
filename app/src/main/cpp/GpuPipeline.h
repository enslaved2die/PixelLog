#pragma once

#include "include/PixelLogCommon.h"
#include "ZeroCopyImporter.h"
#include "LutManager.h"
#include <android/native_window.h>
#include <mutex>
#include <atomic>
#include <vector>

class GpuPipeline {
public:
    GpuPipeline();
    ~GpuPipeline();

    bool initialize(int32_t width, int32_t height);
    void release();

    // Thread context management
    bool bindContextToCurrentThread();
    bool unbindContextFromCurrentThread();

    // Dual-surface management
    bool setEncoderSurface(ANativeWindow* encoderWindow);
    bool setDisplaySurface(ANativeWindow* displayWindow);
    void releaseEncoderSurface();
    void releaseDisplaySurface();

    // 3D LUT loading & curve controls
    bool loadDisplayLut(const char* cubeData, size_t dataSize);
    void setLutEnabled(bool enabled);
    void setBakeLutToEncoder(bool enabled) { mBakeLutToEncoder.store(enabled); }
    void setLogCurveType(int32_t type);
    void setExposureGain(float gain);
    bool isLutEnabled() const;
    bool isBakeLutToEncoder() const { return mBakeLutToEncoder.load(); }
    int32_t getLogCurveType() const;
    float getExposureGain() const;

    // Frame processing pipeline
    void processFrame(AHardwareBuffer* rawBuffer,
                      int32_t stride,
                      const SensorFrameMetadata& metadata,
                      int acquireFenceFd,
                      int* outReleaseFenceFd);

    bool isInitialized() const { return mInitialized; }

private:
    int32_t mWidth;
    int32_t mHeight;
    bool mInitialized;
    std::mutex mPipelineMutex;

    // Pipeline controls
    std::atomic<bool> mIsLutEnabled;
    std::atomic<bool> mBakeLutToEncoder{false};
    std::atomic<int32_t> mLogCurveType;
    std::atomic<float> mExposureGain;

    // EGL Context & Display
    EGLDisplay mEglDisplay;
    EGLConfig mEglConfig;
    EGLContext mEglContext;

    EGLSurface mEglEncoderSurface;
    EGLSurface mEglDisplaySurface;
    EGLSurface mEglPbufferSurface;

    // Pending LUT state for thread-safe upload & release
    std::mutex mLutMutex;
    std::vector<char> mPendingLutData;
    bool mHasPendingLut;
    bool mPendingLutClear;

    // Offscreen FBO (10-bit intermediate Log texture)
    GLuint mOffscreenFbo;
    GLuint mOffscreenTexture;

    // Lens Shading Map Texture (Bilinear RGBA16F)
    GLuint mLensShadingTexture;
    int32_t mLensShadingWidth;
    int32_t mLensShadingHeight;

    // Shader programs
    GLuint mDebayerProgram;
    GLuint mLutProgram;
    GLuint mPassthroughProgram;

    // Quad geometry VBOs
    GLuint mQuadVbo;
    GLuint mDisplayQuadVbo;

    // Subsystems
    ZeroCopyImporter mImporter;
    LutManager mLutManager;

    // Extension functions
    PFNEGLCREATESYNCKHRPROC mEglCreateSyncKHR;
    PFNEGLDESTROYSYNCKHRPROC mEglDestroySyncKHR;
    PFNEGLWAITSYNCKHRPROC mEglWaitSyncKHR;
    PFNEGLDUPNATIVEFENCEFDANDROIDPROC mEglDupNativeFenceFDANDROID;
    PFNEGLPRESENTATIONTIMEANDROIDPROC mEglPresentationTimeANDROID;

    // Internal initialization helpers
    bool initEGL();
    bool initShaders();
    bool initFBO();
    GLuint compileShader(GLenum type, const char* source);
    GLuint createProgram(const char* vertSource, const char* fragSource);
};
