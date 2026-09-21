#include "LutManager.h"
#include <fstream>
#include <sstream>
#include <vector>

LutManager::LutManager() : mLutTextureId(0), mLutSize(0) {}

LutManager::~LutManager() {
    release();
}

void LutManager::release() {
    if (mLutTextureId != 0) {
        glDeleteTextures(1, &mLutTextureId);
        mLutTextureId = 0;
    }
    mLutSize = 0;
}

bool LutManager::loadCubeLutFromFile(const std::string& filePath) {
    std::ifstream file(filePath);
    if (!file.is_open()) {
        LOGE("LutManager: Failed to open LUT file: %s", filePath.c_str());
        return false;
    }

    std::stringstream buffer;
    buffer << file.rdbuf();
    return parseAndUpload(buffer.str());
}

bool LutManager::loadCubeLutFromMemory(const char* cubeData, size_t dataSize) {
    if (!cubeData || dataSize == 0) {
        LOGE("LutManager: Invalid memory data for LUT");
        return false;
    }
    std::string content(cubeData, dataSize);
    return parseAndUpload(content);
}

bool LutManager::parseAndUpload(const std::string& content) {
    release();

    std::istringstream stream(content);
    std::string line;

    int32_t lutSize = 0;
    std::vector<float> rgbData;

    while (std::getline(stream, line)) {
        // Skip empty lines or comments
        if (line.empty() || line[0] == '#') continue;

        // Parse LUT size
        if (line.rfind("LUT_3D_SIZE", 0) == 0) {
            std::istringstream ls(line.substr(11));
            ls >> lutSize;
            continue;
        }

        // Skip other metadata tokens
        if (line.rfind("TITLE", 0) == 0 ||
            line.rfind("DOMAIN_MIN", 0) == 0 ||
            line.rfind("DOMAIN_MAX", 0) == 0 ||
            line.rfind("LUT_1D_SIZE", 0) == 0) {
            continue;
        }

        // Parse float triplet: R G B
        std::istringstream ls(line);
        float r, g, b;
        if (ls >> r >> g >> b) {
            rgbData.push_back(r);
            rgbData.push_back(g);
            rgbData.push_back(b);
        }
    }

    if (lutSize <= 0 || (int32_t)rgbData.size() != lutSize * lutSize * lutSize * 3) {
        LOGE("LutManager: Malformed .cube LUT. Expected %d items, got %zu",
             lutSize * lutSize * lutSize * 3, rgbData.size());
        return false;
    }

    mLutSize = lutSize;

    // Generate and bind 3D texture
    glGenTextures(1, &mLutTextureId);
    glBindTexture(GL_TEXTURE_3D, mLutTextureId);

    // Trilinear filtering setup
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);

    // Upload FP16 / FP32 RGB data to 3D texture
    glTexImage3D(
        GL_TEXTURE_3D,
        0,
        GL_RGB16F,
        mLutSize,
        mLutSize,
        mLutSize,
        0,
        GL_RGB,
        GL_FLOAT,
        rgbData.data()
    );

    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        LOGE("LutManager: glTexImage3D failed with GL error: 0x%x", err);
        release();
        return false;
    }

    glBindTexture(GL_TEXTURE_3D, 0);
    LOGI("LutManager: Successfully initialized 3D LUT (Size: %dx%dx%d, TextureID: %u)",
         mLutSize, mLutSize, mLutSize, mLutTextureId);
    return true;
}
