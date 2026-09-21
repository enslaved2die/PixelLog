# Google Pixel 11 Pro: Dual Conversion Gain (DCG) & RAW Stream Characterization

## 1. Executive Summary & Hardware Profile
* **Target Device:** Google Pixel 11 Pro (`grizzly`)
* **SoC:** Google Tensor G6 (Samsung 2nm GAA / ARMv9.2-A, Mali / DXT GPU)
* **Primary Sensor:** 50 MP Quad-Bayer Wide (1/1.31" optical format, 1.2 µm pixel pitch, 2.4 µm quad-binned)
* **Default Open-Gate Binned RAW Output:** $4080 \times 3072$ / $4080 \times 3064$ ($4:3$)
* **Mod-64 HEVC CTU Aligned Stream:** $3840 \times 2880$ ($4:3$)

---

## 2. Dual Conversion Gain (DCG) Architecture

The primary sensor utilizes Dual Conversion Gain (DCG) at the pixel floating diffusion stage:
1. **Low Conversion Gain (LCG) Mode: ISO 50 – ISO 399**
   - Extra floating diffusion capacitor connected.
   - Full-Well Capacity: ~40,000 $e^-$.
   - Read noise: ~2.8 $e^-$.
   - Dynamic Range: 12.8 stops.
   - Optimal for high scene luminance, preserving highlight headroom and resisting sensor blooming.
2. **High Conversion Gain (HCG) Mode: ISO 400 – ISO 3200+**
   - Extra floating diffusion capacitor disconnected.
   - Full-Well Capacity: ~8,000 $e^-$.
   - Read noise: ~1.1 $e^-$ (approx. 60% reduction in read noise floor).
   - Dynamic Range: 12.5 stops.
   - Optimal for low-light cinematography; shadows exhibit dramatically lower read noise variance.

### Gain Switch Point & Kink
In Photon Transfer Curve (PTC) characterization, the transition between LCG and HCG occurs at **ISO 400**. At this point:
- The sensor effective analog gain switches capacitance modes.
- Digital black level remains pegged to ~`256.0` (`CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL`).
- In `CameraController`, the UI explicitly displays the active DCG capacitor state (`LCG` vs `HCG`) based on `targetIso >= 400`.

---

## 3. RAW Bit Depth & Stream Discovery (10-bit vs 12-bit)

### Background & MotionCam Pro Analysis
On Google Tensor series devices:
- Standard Camera2 `RAW_SENSOR` streams are packaged in a 16-bit container (`AHARDWAREBUFFER_FORMAT_RAW16` / format `0x20`).
- Depending on stream resolution and configured frame rate:
  1. Full sensor open-gate 30 fps produces either a 10-bit ADC readout (white level 1023 shifted or 4095 ceiling with lower effective bits) or true 12-bit readout (white level 4095).
  2. The actual dynamic white level is queried from `CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL` (typically 4095 on Pixel 11 Pro primary sensor).
  3. High frame rates (50/60 fps) trigger sensor binning/skipping ($2032 \times 1532$) to maintain bandwidth under 500 MB/s.

### Empirical Selection & Safety Fallback
Camera2 does not expose an explicit public key named `DCG_ENABLE`. Instead:
1. When configuring `SessionConfiguration.SESSION_REGULAR` with a single high-priority `RAW_SENSOR` output, the HAL configures native sensor readout.
2. High-bitrate 10-bit HEVC Main10 encoding quantizes downstream from the full FP32 linear pipeline after debayering and log curve application.
3. If a 12-bit stream is selected, sensor clip maps cleanly to `R = 8.146` (with $k = 5.5$) or `R = 16.0` (with $k = 6.47$), completely avoiding highlight color shifts.
