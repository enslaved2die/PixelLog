#include "CameraStreamManager.h"
#include <unistd.h>

CameraStreamManager::CameraStreamManager()
    : mWidth(DEFAULT_OPEN_GATE_WIDTH),
      mHeight(DEFAULT_OPEN_GATE_HEIGHT),
      mBayerPattern(BayerPattern::RGGB),
      mRunning(false),
      mImageReader(nullptr),
      mNativeWindow(nullptr) {
    memset(&mCurrentMetadata, 0, sizeof(mCurrentMetadata));
    mCurrentMetadata.whiteLevel = 4095.0f;
    mCurrentMetadata.dynamicBlackLevel = { 256.0f, 256.0f, 256.0f, 256.0f };
    // Identity matrix default
    mCurrentMetadata.colorTransformMatrix[0] = 1.0f;
    mCurrentMetadata.colorTransformMatrix[4] = 1.0f;
    mCurrentMetadata.colorTransformMatrix[8] = 1.0f;
}

CameraStreamManager::~CameraStreamManager() {
    release();
}

bool CameraStreamManager::initialize(int32_t width, int32_t height, BayerPattern pattern) {
    mWidth = width;
    mHeight = height;
    mBayerPattern = pattern;

    if (!mGpuPipeline.initialize(mWidth, mHeight)) {
        LOGE("CameraStreamManager: Failed to initialize GpuPipeline");
        return false;
    }

    // Allocate AImageReader with RAW_SENSOR and GPU_SAMPLED_IMAGE usage
    uint64_t usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN;
    media_status_t status = AImageReader_newWithUsage(
        mWidth,
        mHeight,
        AIMAGE_FORMAT_RAW_SENSOR,
        usage,
        CIRCULAR_POOL_SIZE + 1,
        &mImageReader
    );

    if (status != AMEDIA_OK || !mImageReader) {
        LOGW("CameraStreamManager: AImageReader_newWithUsage failed (status %d), falling back to standard allocator", status);
        status = AImageReader_new(
            mWidth,
            mHeight,
            AIMAGE_FORMAT_RAW_SENSOR,
            CIRCULAR_POOL_SIZE + 1,
            &mImageReader
        );
    }

    if (status != AMEDIA_OK || !mImageReader) {
        LOGE("CameraStreamManager: Could not allocate AImageReader (error: %d)", status);
        return false;
    }

    // Register Image Available Callback
    AImageReader_ImageListener listener = {
        .context = this,
        .onImageAvailable = CameraStreamManager::onImageAvailableCallback
    };
    AImageReader_setImageListener(mImageReader, &listener);

    // Retrieve NativeWindow surface for Camera2 HAL session
    status = AImageReader_getWindow(mImageReader, &mNativeWindow);
    if (status != AMEDIA_OK || !mNativeWindow) {
        LOGE("CameraStreamManager: Failed to get ANativeWindow from AImageReader");
        return false;
    }

    mRunning = true;
    mProcessingThread = std::thread(&CameraStreamManager::processingLoop, this);

    LOGI("CameraStreamManager: Initialized open-gate stream reader (%dx%d)", mWidth, mHeight);
    return true;
}

void CameraStreamManager::onImageAvailableCallback(void* context, AImageReader* reader) {
    if (context) {
        static_cast<CameraStreamManager*>(context)->onImageAvailable(reader);
    }
}

void CameraStreamManager::onImageAvailable(AImageReader* reader) {
    if (!mRunning) return;

    AImage* image = nullptr;
    int acquireFenceFd = -1;

    // Use asynchronous acquire if available (API 26+)
    media_status_t status = AImageReader_acquireNextImageAsync(reader, &image, &acquireFenceFd);
    if (status != AMEDIA_OK || !image) {
        return;
    }

    SensorFrameMetadata metaCopy;
    {
        std::lock_guard<std::mutex> lock(mMetadataMutex);
        metaCopy = mCurrentMetadata;
    }

    int64_t timestamp = 0;
    AImage_getTimestamp(image, &timestamp);
    metaCopy.timestampNs = timestamp;
    metaCopy.bayerPattern = mBayerPattern;

    {
        std::lock_guard<std::mutex> lock(mQueueMutex);
        // Drop oldest frame if queue grows beyond 2 to preserve real-time latency
        if (mFrameQueue.size() >= 2) {
            FrameItem dropped = mFrameQueue.front();
            mFrameQueue.pop();
            if (dropped.acquireFenceFd >= 0) close(dropped.acquireFenceFd);
            AImage_delete(dropped.image);
            LOGW("CameraStreamManager: Dropped late frame to prevent pipeline latency buildup");
        }
        mFrameQueue.push({ image, acquireFenceFd, metaCopy });
    }
    mQueueCv.notify_one();
}

void CameraStreamManager::processingLoop() {
    LOGI("CameraStreamManager: Processing loop thread started (thread_id: %lu)", pthread_self());
    if (!mGpuPipeline.bindContextToCurrentThread()) {
        LOGE("CameraStreamManager: Failed to bind EGL context to processing thread! Aborting loop.");
        return;
    }

    uint64_t frameCount = 0;
    while (mRunning) {
        FrameItem item;
        {
            std::unique_lock<std::mutex> lock(mQueueMutex);
            mQueueCv.wait(lock, [this]() { return !mFrameQueue.empty() || !mRunning; });
            if (!mRunning && mFrameQueue.empty()) break;
            item = mFrameQueue.front();
            mFrameQueue.pop();
        }

        AHardwareBuffer* hb = nullptr;
        AImage_getHardwareBuffer(item.image, &hb);

        if (hb) {
            int releaseFenceFd = -1;
            int32_t stride = mWidth;

            // Process frame through GPU Debayer -> Pixel-Log -> Dual Surface
            mGpuPipeline.processFrame(hb, stride, item.metadata, item.acquireFenceFd, &releaseFenceFd);

            frameCount++;
            if (frameCount % 60 == 0) {
                LOGI("CameraStreamManager: Processed %llu frames through GPU debayer & display pipeline", (unsigned long long)frameCount);
            }

            // Asynchronous release returning buffer to Camera2 circular pool
            AImage_deleteAsync(item.image, releaseFenceFd);
        } else {
            if (item.acquireFenceFd >= 0) close(item.acquireFenceFd);
            AImage_delete(item.image);
        }
    }

    mGpuPipeline.unbindContextFromCurrentThread();
    LOGI("CameraStreamManager: Processing loop thread exited cleanly");
}

ANativeWindow* CameraStreamManager::getCameraSurface() {
    return mNativeWindow;
}

void CameraStreamManager::setEncoderSurface(ANativeWindow* window) {
    mGpuPipeline.setEncoderSurface(window);
}

void CameraStreamManager::setDisplaySurface(ANativeWindow* window) {
    mGpuPipeline.setDisplaySurface(window);
}

bool CameraStreamManager::loadDisplayLut(const char* data, size_t size) {
    return mGpuPipeline.loadDisplayLut(data, size);
}

void CameraStreamManager::setLutEnabled(bool enabled) {
    mGpuPipeline.setLutEnabled(enabled);
}

void CameraStreamManager::setLogCurveType(int32_t type) {
    mGpuPipeline.setLogCurveType(type);
}

void CameraStreamManager::setExposureGain(float gain) {
    mGpuPipeline.setExposureGain(gain);
}

void CameraStreamManager::updateFrameMetadata(const SensorFrameMetadata& metadata) {
    std::lock_guard<std::mutex> lock(mMetadataMutex);
    mCurrentMetadata = metadata;
}

void CameraStreamManager::release() {
    mRunning = false;
    mQueueCv.notify_all();

    if (mProcessingThread.joinable()) {
        mProcessingThread.join();
    }

    {
        std::lock_guard<std::mutex> lock(mQueueMutex);
        while (!mFrameQueue.empty()) {
            FrameItem item = mFrameQueue.front();
            mFrameQueue.pop();
            if (item.acquireFenceFd >= 0) close(item.acquireFenceFd);
            AImage_delete(item.image);
        }
    }

    mGpuPipeline.release();

    if (mImageReader) {
        AImageReader_delete(mImageReader);
        mImageReader = nullptr;
    }
    mNativeWindow = nullptr;

    LOGI("CameraStreamManager: Successfully released");
}
