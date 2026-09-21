#pragma once

#include "include/PixelLogCommon.h"

class LutManager {
public:
    LutManager();
    ~LutManager();

    bool loadCubeLutFromMemory(const char* cubeData, size_t dataSize);
    bool loadCubeLutFromFile(const std::string& filePath);
    void release();

    GLuint getLutTextureId() const { return mLutTextureId; }
    int32_t getLutSize() const { return mLutSize; }
    bool isLoaded() const { return mLutTextureId != 0; }

private:
    GLuint mLutTextureId;
    int32_t mLutSize;

    bool parseAndUpload(const std::string& content);
};
