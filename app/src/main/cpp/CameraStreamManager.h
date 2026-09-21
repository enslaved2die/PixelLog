#pragma once

#include "include/PixelLogCommon.h"
#include "GpuPipeline.h"
#include <media/NdkImageReader.h>
#include <android/native_window.h>
#include <atomic>
#include <thread>
#include <queue>
#include <map>
#include <mutex>
#include <condition_variable>

class CameraStreamManager {
public:
    CameraStreamManager();
    ~CameraStreamManager();

    bool initialize(int32_t width, int32_t height, BayerPattern pattern);
    void release();

    ANativeWindow* getCameraSurface();

    void setEncoderSurface(ANativeWindow* window);
    void setDisplaySurface(ANativeWindow* window);
    bool loadDisplayLut(const char* data, size_t size);
    void setLutEnabled(bool enabled);
    void setLogCurveType(int32_t type);
    void setExposureGain(float gain);

    void updateFrameMetadata(const SensorFrameMetadata& metadata);

    // Callbacks from NDK AImageReader
    void onImageAvailable(AImageReader* reader);

private:
    int32_t mWidth;
    int32_t mHeight;
    BayerPattern mBayerPattern;
    std::atomic<bool> mRunning;

    AImageReader* mImageReader;
    ANativeWindow* mNativeWindow;

    GpuPipeline mGpuPipeline;

    std::mutex mMetadataMutex;
    SensorFrameMetadata mCurrentMetadata;
    std::map<int64_t, SensorFrameMetadata> mPendingMetadata;
    uint64_t mMatchedFrames;
    uint64_t mUnmatchedFrames;
    uint64_t mDroppedFrames;

    // Worker thread for asynchronous image processing
    std::thread mProcessingThread;
    std::mutex mQueueMutex;
    std::condition_variable mQueueCv;

    struct FrameItem {
        AImage* image;
        int acquireFenceFd;
        SensorFrameMetadata metadata;
    };
    std::queue<FrameItem> mFrameQueue;

    void processingLoop();
    static void onImageAvailableCallback(void* context, AImageReader* reader);
};
