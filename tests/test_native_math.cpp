#include "../app/src/main/cpp/include/LogParams.h"
#include <iostream>
#include <cmath>
#include <cassert>
#include <vector>

int main() {
    std::cout << "[C++ Native Math Verification: Phase 3 Curve]" << std::endl;

    // 1. Check anchors
    float y_zero = pixellog::forwardPixelLog(0.0f);
    float y_grey = pixellog::forwardPixelLog(0.18f);
    float y_clip = pixellog::forwardPixelLog(pixellog::LOG_XMAX);

    std::cout << "  Zero Light:     " << y_zero << " (Expected " << pixellog::LOG_YB << ")" << std::endl;
    std::cout << "  18% Mid Grey:   " << y_grey << " (Expected " << pixellog::LOG_YM << ")" << std::endl;
    std::cout << "  Sensor Clip:    " << y_clip << " (Expected 1.0000)" << std::endl;

    assert(std::abs(y_zero - pixellog::LOG_YB) < 1e-4f);
    assert(std::abs(y_grey - pixellog::LOG_YM) < 1e-4f);
    assert(std::abs(y_clip - 1.0f) < 1e-4f);

    // 2. Round-trip test
    const std::vector<float> test_values = {
        -0.05f, -0.01f, 0.0f, 0.005f, 0.01f, 0.05f, 0.18f, 0.5f, 1.0f, 4.0f, pixellog::LOG_XMAX
    };

    float max_err = 0.0f;
    for (float x : test_values) {
        float y = pixellog::forwardPixelLog(x);
        float x_rec = pixellog::inversePixelLog(y);
        float err = std::abs(x - x_rec);
        if (err > max_err) max_err = err;
        assert(err < 1e-5f);
    }

    std::cout << "  Max C++ FP32 Roundtrip Error: " << max_err << std::endl;
    std::cout << "  [SUCCESS] All C++ native math checks passed!" << std::endl;
    return 0;
}
