# PixelLog Hardware & Device Memory Profile

## Target Physical Device
* **Device Model:** Google Pixel 11 Pro
* **Product Codename:** `grizzly`
* **Device Serial:** `66091FDKX001ZS`
* **SoC:** Google Tensor G6 (ARMv9.2-A, Mali / DXT GPU)
* **RAM:** 12 GB LPDDR5X
* **OS:** Android 17 (API 36/37)
* **USB Vendor ID:** `0x18d1` (Google, 6353)
* **Target Architecture:** `arm64-v8a`

## Camera & Sensor Specifications
* **Primary Wide Sensor ID:** Camera ID `"0"` (`LENS_FACING_BACK` with `REQUEST_AVAILABLE_CAPABILITIES_RAW`)
* **Color Filter Array (CFA):** RGGB (`SENSOR_INFO_COLOR_FILTER_ARRANGEMENT = 0`)
* **Open-Gate Aspect Ratio:** 4:3 Open Gate (`4080x3060` / `4080x3072` / `3840x2880`)
* **Dynamic Black Level:** ~`256.0` (`CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL`)
* **White Level Ceiling:** ~`4095.0` (12-bit ADC) or `65535.0` (MSB shifted)
* **Dual Conversion Gain (DCG):**
  - LCG Mode: ISO 50 - 399
  - HCG Mode: ISO 400 - 3200+
* **Shutter Timing:** 180° shutter rule at 30 fps = $16,666,666\text{ ns}$ (`SENSOR_EXPOSURE_TIME`)
* **Frame Cadence:** Fixed $30.000\text{ fps}$ (`SENSOR_FRAME_DURATION = 33,333,333 ns`)

## Viewfinder & Zero-Copy Graphics Architecture
* **Viewfinder Surface:** Android `SurfaceView` with `setZOrderMediaOverlay(true)` (never covered by opaque layout background)
* **Shader Scale Normalization:**
  ```glsl
  if (wl > 1.0 && raw <= 1.0) {
      raw = raw * 65535.0;
  }
  return clamp((raw - bl) / max(wl - bl, 1e-6), 0.0, 1.0);
  ```
* **Viewfinder Viewport:** Dynamically queried from `eglQuerySurface(EGL_WIDTH, EGL_HEIGHT)`
* **Transfer Curve:** Branchless Pixel-Log ($\operatorname{asinh}$) with middle grey mapped to $0.4000$ (code 410)
* **Fallback Pathway:** `AHardwareBuffer_lock` with `glTexImage2D(GL_LUMINANCE, GL_UNSIGNED_SHORT)` if `RAW16` `eglCreateImageKHR` is rejected by gralloc

## Android Deployment
* **Package Name:** `com.pixellog`
* **Main Activity:** `com.pixellog.ui.CameraActivity`
* **Required Permissions:**
  - `android.permission.CAMERA`
  - `android.permission.RECORD_AUDIO`
* **APK Location:** `app/build/outputs/apk/debug/app-debug.apk`
