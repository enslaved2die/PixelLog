#include "ZeroCopyImporter.h"
#include <cstring>
#include <unistd.h>

#ifndef EGL_IMAGE_PRESERVED_KHR
#define EGL_IMAGE_PRESERVED_KHR 0x30D2
#endif

#ifndef EGL_NATIVE_BUFFER_ANDROID
#define EGL_NATIVE_BUFFER_ANDROID 0x3140
#endif

#ifndef DRM_FORMAT_R16
#define DRM_FORMAT_R16 0x36315252 // 'R16 '
#endif

ZeroCopyImporter::ZeroCopyImporter()
    : mEglDisplay(EGL_NO_DISPLAY),
      mEglGetNativeClientBufferANDROID(nullptr),
      mEglCreateImageKHR(nullptr),
      mEglDestroyImageKHR(nullptr),
      mGlEGLImageTargetTexture2DOES(nullptr),
      mHasDmaBufImport(false),
      mHasNativeBufferImport(false),
      mFallbackTexture(0),
      mFallbackWidth(0),
      mFallbackHeight(0) {}

ZeroCopyImporter::~ZeroCopyImporter() {
    release();
}

bool ZeroCopyImporter::initialize(EGLDisplay display) {
    mEglDisplay = display;
    if (mEglDisplay == EGL_NO_DISPLAY) {
        LOGE("ZeroCopyImporter::initialize called with EGL_NO_DISPLAY");
        return false;
    }

    const char* eglExtensions = eglQueryString(mEglDisplay, EGL_EXTENSIONS);
    if (eglExtensions) {
        mHasNativeBufferImport = strstr(eglExtensions, "EGL_ANDROID_get_native_client_buffer") &&
                                 strstr(eglExtensions, "EGL_ANDROID_image_native_buffer");
        mHasDmaBufImport = strstr(eglExtensions, "EGL_EXT_image_dma_buf_import") != nullptr;
    }

    mEglGetNativeClientBufferANDROID = (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)
        eglGetProcAddress("eglGetNativeClientBufferANDROID");
    mEglCreateImageKHR = (PFNEGLCREATEIMAGEKHRPROC)
        eglGetProcAddress("eglCreateImageKHR");
    mEglDestroyImageKHR = (PFNEGLDESTROYIMAGEKHRPROC)
        eglGetProcAddress("eglDestroyImageKHR");
    mGlEGLImageTargetTexture2DOES = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)
        eglGetProcAddress("glEGLImageTargetTexture2DOES");

    LOGI("ZeroCopyImporter initialized. NativeClientBuffer: %d, DmaBuf: %d",
         mHasNativeBufferImport, mHasDmaBufImport);

    return (mEglCreateImageKHR != nullptr && mGlEGLImageTargetTexture2DOES != nullptr);
}

void ZeroCopyImporter::release() {
    if (mFallbackTexture != 0) {
        glDeleteTextures(1, &mFallbackTexture);
        mFallbackTexture = 0;
    }
    mFallbackWidth = 0;
    mFallbackHeight = 0;

    mEglDisplay = EGL_NO_DISPLAY;
    mEglGetNativeClientBufferANDROID = nullptr;
    mEglCreateImageKHR = nullptr;
    mEglDestroyImageKHR = nullptr;
    mGlEGLImageTargetTexture2DOES = nullptr;
}

GLuint ZeroCopyImporter::importHardwareBufferToTexture(AHardwareBuffer* hardwareBuffer,
                                                       int32_t width,
                                                       int32_t height,
                                                       int32_t stride) {
    if (!hardwareBuffer) {
        LOGE("ZeroCopyImporter::importHardwareBufferToTexture: invalid arguments");
        return 0;
    }

    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(hardwareBuffer, &desc);

    // CRITICAL TENSOR G6 / POWERVR FIX:
    // Calling eglCreateImageKHR with EGL_NATIVE_BUFFER_ANDROID on an AHARDWAREBUFFER_FORMAT_RAW16 (0x20)
    // causes a fatal SIGSEGV crash inside /vendor/lib64/libcustomer_gralloc_ddk_api.so (gralloc_native_handle_bpp).
    // For RAW16 (format 0x20), bypass EGLImage completely and use direct CPU memory mapping with fast GL texture upload.
    if (desc.format == 0x20) {
        void* virtualAddress = nullptr;
        int32_t lockResult = AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &virtualAddress);
        if (lockResult != 0 || !virtualAddress) {
            LOGE("AHardwareBuffer_lock failed with status %d", lockResult);
            return 0;
        }

        const uint16_t* p16 = static_cast<const uint16_t*>(virtualAddress);
        static uint64_t sampleCount = 0;
        if (sampleCount++ % 60 == 0) {
            uint32_t rowStride = desc.stride > 0 ? desc.stride : (uint32_t)width;
            uint32_t centerIdx = rowStride * (height / 2) + (width / 2);
            LOGI("ZeroCopyImporter: Raw Bayer values [0]=%u, [center]=%u, stride=%u, format=0x%x",
                 p16[0], p16[centerIdx], desc.stride, desc.format);
        }

        if (mFallbackTexture == 0 || mFallbackWidth != width || mFallbackHeight != height) {
            if (mFallbackTexture != 0) glDeleteTextures(1, &mFallbackTexture);
            glGenTextures(1, &mFallbackTexture);
            glBindTexture(GL_TEXTURE_2D, mFallbackTexture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

            // Allocate immutable/mutable 16-bit unsigned integer storage
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R16UI, width, height, 0, GL_RED_INTEGER, GL_UNSIGNED_SHORT, nullptr);
            mFallbackWidth = width;
            mFallbackHeight = height;
            LOGI("ZeroCopyImporter: Allocated GL_R16UI raw texture %dx%d (stride=%u)", width, height, desc.stride);
        } else {
            glBindTexture(GL_TEXTURE_2D, mFallbackTexture);
        }

        glPixelStorei(GL_UNPACK_ALIGNMENT, 2);
        if (desc.stride > 0 && desc.stride != (uint32_t)width) {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, desc.stride);
        }

        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RED_INTEGER, GL_UNSIGNED_SHORT, virtualAddress);

        GLenum err = glGetError();
        if (err != GL_NO_ERROR) {
            LOGE("ZeroCopyImporter: glTexSubImage2D failed with GL error: 0x%x", err);
        }

        if (desc.stride > 0 && desc.stride != (uint32_t)width) {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        }
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);

        AHardwareBuffer_unlock(hardwareBuffer, nullptr);
        glBindTexture(GL_TEXTURE_2D, 0);
        return mFallbackTexture;
    }

    if (!mEglCreateImageKHR || !mGlEGLImageTargetTexture2DOES) {
        LOGE("ZeroCopyImporter::importHardwareBufferToTexture: missing EGL extensions");
        return 0;
    }

    EGLClientBuffer clientBuffer = nullptr;
    if (mEglGetNativeClientBufferANDROID) {
        clientBuffer = mEglGetNativeClientBufferANDROID(hardwareBuffer);
    }

    if (!clientBuffer) {
        LOGE("Failed to get EGLClientBuffer from AHardwareBuffer");
        return 0;
    }

    EGLint attrs[] = {
        EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
        EGL_NONE
    };

    EGLImageKHR eglImage = mEglCreateImageKHR(
        mEglDisplay,
        EGL_NO_CONTEXT,
        EGL_NATIVE_BUFFER_ANDROID,
        clientBuffer,
        attrs
    );

    if (eglImage == EGL_NO_IMAGE_KHR) {
        EGLint error = eglGetError();
        LOGW("eglCreateImageKHR failed with error 0x%x for EGL_NATIVE_BUFFER_ANDROID", error);
        return 0;
    }

    GLuint textureId = 0;
    glGenTextures(1, &textureId);
    glBindTexture(GL_TEXTURE_2D, textureId);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    mGlEGLImageTargetTexture2DOES(GL_TEXTURE_2D, (GLeglImageOES)eglImage);
    GLenum glErr = glGetError();
    if (glErr != GL_NO_ERROR) {
        LOGE("glEGLImageTargetTexture2DOES failed with GL error: 0x%x", glErr);
        glDeleteTextures(1, &textureId);
        textureId = 0;
    }

    // Release the EGLImage wrapper once bound to GL texture object
    mEglDestroyImageKHR(mEglDisplay, eglImage);

    glBindTexture(GL_TEXTURE_2D, 0);
    return textureId;
}

void ZeroCopyImporter::destroyTexture(GLuint textureId) {
    if (textureId != 0 && textureId != mFallbackTexture) {
        glDeleteTextures(1, &textureId);
    }
}
