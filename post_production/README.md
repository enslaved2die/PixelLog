# PixelLog Post-Production & Color Grading Guide

This guide details how to import, manage, and grade **Pixel-Log** footage from the Google Pixel 11 Pro in **DaVinci Resolve Free**, **DaVinci Resolve Studio**, **Final Cut Pro**, and **Adobe Premiere Pro**.

---

## 1. Quick Overview of Deliverables

| Deliverable | Type | Recommended Environment | Purpose |
|---|---|---|---|
| `PixelLog_to_Rec709_Display_33.cube` / `_65.cube` | 3D LUT | DaVinci Resolve Free / Studio, FCP, Premiere | Direct technical display transform with rectified Michaelis-Menten tone curve (18% Grey @ 42 IRE, White @ 90 IRE). |
| `PixelLog_to_AgX_Rec709_33.cube` / `_65.cube` | 3D LUT | DaVinci Resolve Free / Studio, FCP, Premiere | **AgX Base Film Transform**: Photochemical film emulation with analytical Inset Matrix crosstalk, natural highlight desaturation to white core, and zero hue clipping shifts. |
| `PixelLog_to_AgX_Punchy_33.cube` / `_65.cube` | 3D LUT | DaVinci Resolve Free / Studio, FCP, Premiere | **AgX Punchy Commercial Transform**: AgX with ASC-CDL Power 1.35 and Saturation 1.40 for rich contrast and vibrant commercial look. |
| `PixelLog_to_DWG_Intermediate_33.cube` / `_65.cube` | 3D LUT | DaVinci Resolve Free & Studio | Transforms Pixel-Log to **DaVinci Wide Gamut / DaVinci Intermediate** for native grading with Resolve's built-in CST. |
| `PixelLog_to_ACEScg_33.cube` / `_65.cube` | 3D LUT | DaVinci Resolve, Nuke, Blender | Transforms Pixel-Log to **ACEScg (AP1)** Scene-Linear for VFX and ACES color-managed projects. |
| `PixelLog_to_Rec2020_Linear_33.cube` / `_65.cube` | 3D LUT | NLEs / Comp | Exact unclipped scene-linear de-log transform. |
| `PixelLog_Inverse.dctl` | DCTL Script | DaVinci Resolve Studio Only | Analytical floating-point de-log math with exposure trim (-5 to +5 stops) and gamut selection. |
| `LogToLinear_1D.cube` | 1D LUT | Any NLE | 4096-point 1D curve de-log. |

---

## 2. DaVinci Resolve Free Workflows (No DCTL Required)

Because DCTL is restricted to DaVinci Resolve Studio, DaVinci Resolve Free users should use the calibrated `.cube` LUTs.

### Workflow A: Native DaVinci Wide Gamut (DWG) Managed Workflow (Recommended)
This workflow allows Resolve Free users to grade in Blackmagic's wide-gamut log space and utilize Resolve's built-in **Color Space Transform (CST)** OFX plugin.

1. **Install LUTs**:
   - In DaVinci Resolve, go to **Project Settings** $\to$ **Color Management** $\to$ **Open LUT Folder**.
   - Copy the `.cube` files from this `post_production/` directory into a new folder named `PixelLog`.
   - Click **Update Lists** in Resolve.
2. **Node Graph Setup**:
   - **Node 1 (Input IDT)**: Right-click node $\to$ **LUT** $\to$ `PixelLog` $\to$ `PixelLog_to_DWG_Intermediate_33.cube` (or `_65.cube`).
   - **Nodes 2 through N (Grading)**: Perform all exposure, contrast, color balance, and creative grading here. The image data is in DaVinci Wide Gamut / DaVinci Intermediate.
   - **Final Node (Output ODT)**: Add Resolve's built-in **Color Space Transform** (CST) plugin:
     - **Input Color Space**: `DaVinci Wide Gamut`
     - **Input Gamma**: `DaVinci Intermediate`
     - **Output Color Space**: `Rec.709`
     - **Output Gamma**: `Rec.709` (or `Gamma 2.4`)
     - **Tone Mapping**: `DaVinci`
     - **White Point Adaptation**: `Bradford`

---

### Workflow B: Direct Technical Rec.709 Display Transform
For fast turnaround when you want immediate, accurate Rec.709 colors:

1. On the clip or first node, right-click $\to$ **LUT** $\to$ `PixelLog` $\to$ `PixelLog_to_Rec709_Display_33.cube` (or `_65.cube`).
2. The footage instantly displays with:
   - 18% middle grey anchored at **42.0 IRE**.
   - 100% diffuse white anchored at **90.0 IRE**.
   - Smooth highlight roll-off extending to 99.8 IRE (+5.5 stops headroom).

---

### Workflow C: ACEScg VFX Workflow
For compositing in Nuke, Fusion, Blender, or an ACEScg pipeline:
1. Apply `PixelLog_to_ACEScg_33.cube` or `_65.cube`.
2. The output is scene-linear ACES AP1 RGB.

---

### Workflow D: AgX Photochemical Film Transform (Recommended for High-Contrast / Harsh Highlights)
For projects with intense highlights (sunsets, direct sunlight, practical lights, fire, neon lights, car headlights):
1. On the clip or final node, right-click $\to$ **LUT** $\to$ `PixelLog` $\to$ `PixelLog_to_AgX_Rec709_65.cube` (for neutral filmic response) or `PixelLog_to_AgX_Punchy_65.cube` (for rich contrast and commercial saturation).
2. **Key Advantages of AgX**:
   - **Zero Hue Skewing:** Fire never turns lemon-yellow; blue LEDs never turn cyan.
   - **Photochemical Emulsion Crosstalk:** Multi-channel inset compression forces highlights to desaturate naturally into a clean white core.
   - **Graceful Highlight Knee:** 16.5 stops of dynamic range (-10 EV to +6.5 EV) smoothly compressed into Rec.709 display space.
   - Prior nodes can be used to trim exposure or balance tint smoothly before the AgX transform.

---

## 3. DaVinci Resolve Studio Workflow (DCTL)

1. Open **Project Settings** $\to$ **Color Management** $\to$ **Open LUT Folder**.
2. Open the `LUT` parent folder and navigate to the `LUT/` directory (or place in `LUT/PixelLog/`).
3. Copy `PixelLog_Inverse.dctl` into this directory and click **Update Lists**.
4. In the Color Page, apply the **DCTL** plugin to a node.
5. In the DCTL inspector:
   - **Transform Direction**: `Pixel-Log to Scene-Linear (Inverse)`
   - **Exposure Trim (Stops)**: Adjust exposure optically before tone mapping.
   - **Output Gamut Mode**: Choose `Native BT.2020`, `Rec.709`, `ACEScg (AP1)`, or `DaVinci Wide Gamut`.

---

## 4. Automatic HLG HDR Detection

When shooting with **SIGNAL: HLG** enabled in PixelLog:
- The video bitstream Sequence Parameter Set (SPS) VUI and MP4 container `colr` atom are tagged with ITU-T H.273 standard identifiers:
  - `colour_primaries = 9` (Rec.2020)
  - `transfer_characteristics = 18` (ARIB STD-B67 / Rec.2100 HLG)
  - `matrix_coefficients = 9` (Rec.2020 non-constant luminance)
- **DaVinci Resolve Behavior**:
  - When importing HLG-tagged clips into a DaVinci YRGB Color Managed timeline, Resolve automatically flags the clip as `Rec.2100 HLG`.
  - On HDR-compatible displays or Apple Silicon Liquid Retina XDR displays, Resolve displays the full dynamic range up to 1,000 nits without manual color management adjustments.

---

## 5. In-Camera Recording Modes

PixelLog provides two recording modes in the in-app settings card:

1. **REC BAKE: OFF (Clean Log)**:
   - High-bitrate 10-bit HEVC/AV1 records the raw, unclipped scene-linear $\log_2$ curve.
   - Viewfinder preview displays the active 3D LUT in real-time, but recorded bitstream is clean Log.
   - Accompanied by a frame-accurate `.json` sidecar containing sensor metadata, black/white levels, and color matrices.

2. **REC BAKE: BAKED**:
   - The active 3D LUT (built-in Rec.709, DWG, ACES, or imported `.cube`) is burned directly into the 10-bit recording stream during GPU Pass 2.
   - Output clips are contrasty, color-graded, and ready for immediate client delivery or editing with zero post-processing required.
