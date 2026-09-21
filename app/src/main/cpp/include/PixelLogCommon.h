#pragma once

#include "LogParams.h"
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl31.h>
#include <GLES2/gl2ext.h>
#include <cstdint>
#include <cstdlib>
#include <cmath>
#include <string>
#include <vector>

#define LOG_TAG "PixelLogNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef AIMAGE_FORMAT_RAW_SENSOR
#define AIMAGE_FORMAT_RAW_SENSOR 0x20 // AIMAGE_FORMAT_RAW16
#endif

// Default Open-Gate 4:3 capture resolution (Binned 12.5MP, matching Pixel 11 Pro HAL 4080x3064)
constexpr int32_t DEFAULT_OPEN_GATE_WIDTH = 4080;
constexpr int32_t DEFAULT_OPEN_GATE_HEIGHT = 3064;

// Mod-64 HEVC CTU aligned alternative
constexpr int32_t MOD64_OPEN_GATE_WIDTH = 3840;
constexpr int32_t MOD64_OPEN_GATE_HEIGHT = 2880;

// Maximum circular buffer slots
constexpr int32_t CIRCULAR_POOL_SIZE = 3;

// Bayer Pattern ordering matching CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
enum class BayerPattern : int32_t {
    RGGB = 0,
    GRBG = 1,
    GBRG = 2,
    BGGR = 3
};

struct DynamicBlackLevel {
    float r;
    float gr;
    float gb;
    float b;
};

struct SensorFrameMetadata {
    int64_t timestampNs;
    DynamicBlackLevel dynamicBlackLevel;
    float whiteLevel;
    float neutralColorPoint[3];    // [Rn, Gn, Bn] (SENSOR_NEUTRAL_COLOR_POINT)
    float compositeMatrix[9];      // 3x3 column-major Sensor -> Rec.2020 exposed
    float exposureGain;            // Exposure normalizer factor
    BayerPattern bayerPattern;
    int32_t shadingMapWidth;
    int32_t shadingMapHeight;
    std::vector<float> shadingMapData; // [R, Gr, Gb, B] per grid cell
    bool hasShadingMap;
};
