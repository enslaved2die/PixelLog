#pragma once

// AUTO-GENERATED from log_params.json by tools/solve_log.py.
// DO NOT HAND-EDIT.

#include <cmath>

namespace pixellog {

constexpr float LOG_YB        = 0.09000000f;
constexpr float LOG_YM        = 0.40000000f;
constexpr float LOG_K         = 5.50000000f;
constexpr float LOG_XMAX      = 8.14587012f;

constexpr float LOG_BETA      = 0.03215250f;
constexpr float LOG_GAMMA     = 0.11388271f;
constexpr float LOG_DELTA     = 0.65473586f;
constexpr float LOG_S         = 5.10996113f;
constexpr float LOG_INV_GAMMA = 8.78096412f;
constexpr float LOG_INV_S     = 0.19569620f;

inline float forwardPixelLog(float x) {
    if (x >= 0.0f) {
        return LOG_GAMMA * std::log2(x + LOG_BETA) + LOG_DELTA;
    } else {
        return LOG_YB + LOG_S * x;
    }
}

inline float inversePixelLog(float y) {
    if (y >= LOG_YB) {
        return std::exp2((y - LOG_DELTA) * LOG_INV_GAMMA) - LOG_BETA;
    } else {
        return (y - LOG_YB) * LOG_INV_S;
    }
}

} // namespace pixellog
