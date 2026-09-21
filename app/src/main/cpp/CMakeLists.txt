cmake_minimum_required(VERSION 3.22.1)

project("pixellog")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(
    include
    ${CMAKE_CURRENT_SOURCE_DIR}
)

add_library(
    pixellog
    SHARED
    pixellog_jni.cpp
    CameraStreamManager.cpp
    GpuPipeline.cpp
    LutManager.cpp
    ZeroCopyImporter.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(camera2ndk-lib camera2ndk)
find_library(mediandk-lib mediandk)
find_library(egl-lib EGL)
find_library(glesv3-lib GLESv3)

target_link_libraries(
    pixellog
    ${log-lib}
    ${android-lib}
    ${camera2ndk-lib}
    ${mediandk-lib}
    ${egl-lib}
    ${glesv3-lib}
)
