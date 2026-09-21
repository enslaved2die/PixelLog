#!/usr/bin/env python3
"""
================================================================================
PixelLog Post-Production 3D LUT Generator
Target Platform: Google Pixel 11 Pro Primary Sensor
Color Science: Bespoke Inverse Hyperbolic Sine (asinh) Pixel-Log Curve
Generates:
  1) PixelLog_to_Rec709_Display.cube (33x33x33)
  2) PixelLog_to_Rec2020_Linear.cube (33x33x33)
================================================================================
"""

import math
import os
import sys

LUT_SIZE = 33

# --- Pixel-Log Analytical Constants ---
ALPHA = 0.09499002
BETA = -0.21565232
S = 0.00450000
R0 = -0.02100000
INV_S = 222.22222222  # 1.0 / S
INV_ALPHA = 10.527422  # 1.0 / ALPHA

# --- Bradford-Adapted BT.2020 to Rec.709 Color Matrix ---
M_BT2020_TO_REC709 = [
    [ 1.660491, -0.587641, -0.072850],
    [-0.124550,  1.132900, -0.008349],
    [-0.018151, -0.100579,  1.118730]
]

def pixel_log_oetf(r):
    """Forward OETF: Linear Scene Reflection [-0.021, 16.0] -> Pixel-Log [0, 1]"""
    x = (r - R0) * INV_S
    root = math.sqrt(x * x + 1.0)
    arg = x + root
    if arg <= 0.0:
        return 0.0
    return ALPHA * math.log2(arg) + BETA

def pixel_log_eotf(p):
    """Inverse EOTF: Pixel-Log [0, 1] -> Linear Scene Reflection [-0.021, 16.0]"""
    exp_val = (p - BETA) * INV_ALPHA
    k = math.pow(2.0, exp_val)
    x = 0.5 * (k - 1.0 / k)
    return S * x + R0

def mat33_mul_vec3(mat, v):
    return [
        mat[0][0] * v[0] + mat[0][1] * v[1] + mat[0][2] * v[2],
        mat[1][0] * v[0] + mat[1][1] * v[1] + mat[1][2] * v[2],
        mat[2][0] * v[0] + mat[2][1] * v[1] + mat[2][2] * v[2]
    ]

def filmic_tone_map(x):
    """
    Perceptual Filmic Tone Mapping Curve (Michaelis-Menten Rational Formulation)
    Maps scene-linear [0.0, 16.0] smoothly to display-linear [0.0, 1.0].
    Preserves midtone contrast while gently rolling off highlights.
    """
    if x <= 0.0:
        return 0.0
    a = 1.65
    b = 0.18
    c = 1.42
    d = 0.85
    y = (x * (a * x + b)) / (x * (a * x + c) + d)
    return min(max(y, 0.0), 1.0)

def rec709_display_encode(linear):
    """ITU-R BT.1886 / Rec.709 display encoding (Gamma 2.4)"""
    if linear <= 0.0:
        return 0.0
    return math.pow(linear, 1.0 / 2.4)

def generate_display_lut(filepath):
    """Generates PixelLog_to_Rec709_Display.cube (33x33x33)"""
    os.makedirs(os.path.dirname(os.path.abspath(filepath)), exist_ok=True)
    print(f"[LUT Generator] Writing Display LUT: {filepath} ...")
    with open(filepath, "w") as f:
        f.write("# PixelLog to Rec.709 Display Transform LUT\n")
        f.write("# Target Device: Google Pixel 11 Pro Primary Sensor\n")
        f.write("# Color Science: Bespoke asinh Pixel-Log Curve\n")
        f.write("# Transform: Pixel-Log BT.2020 -> Scene-Linear -> Gamut Compress -> Filmic Knee -> BT.1886\n")
        f.write(f"TITLE \"PixelLog_to_Rec709_Display\"\n")
        f.write(f"LUT_3D_SIZE {LUT_SIZE}\n")
        f.write("DOMAIN_MIN 0.0 0.0 0.0\n")
        f.write("DOMAIN_MAX 1.0 1.0 1.0\n\n")

        for b_idx in range(LUT_SIZE):
            p_b = b_idx / (LUT_SIZE - 1)
            for g_idx in range(LUT_SIZE):
                p_g = g_idx / (LUT_SIZE - 1)
                for r_idx in range(LUT_SIZE):
                    p_r = r_idx / (LUT_SIZE - 1)

                    # 1. Decode Pixel-Log to Scene-Linear BT.2020
                    lin_bt2020 = [
                        pixel_log_eotf(p_r),
                        pixel_log_eotf(p_g),
                        pixel_log_eotf(p_b)
                    ]

                    # 2. Gamut Transform: BT.2020 -> Rec.709
                    lin_rec709 = mat33_mul_vec3(M_BT2020_TO_REC709, lin_bt2020)

                    # 3. Filmic Tone Reproduction on Luminance (Preserve chromaticity)
                    y_in = 0.2126 * lin_rec709[0] + 0.7152 * lin_rec709[1] + 0.0722 * lin_rec709[2]
                    if y_in > 1e-6:
                        y_out = filmic_tone_map(y_in)
                        gain = y_out / y_in
                        # Soft highlight desaturation prevents color clipping at high luminance
                        sat_factor = 1.0 / (1.0 + 0.08 * max(y_in - 0.18, 0.0))
                        mapped = [
                            y_out + (channel * gain - y_out) * sat_factor for channel in lin_rec709
                        ]
                    else:
                        mapped = [0.0, 0.0, 0.0]

                    # 4. Display Encoding: BT.1886 (Gamma 2.4)
                    out_r = rec709_display_encode(min(max(mapped[0], 0.0), 1.0))
                    out_g = rec709_display_encode(min(max(mapped[1], 0.0), 1.0))
                    out_b = rec709_display_encode(min(max(mapped[2], 0.0), 1.0))

                    f.write(f"{out_r:.6f} {out_g:.6f} {out_b:.6f}\n")
    print(f"[LUT Generator] Completed: {filepath}")

def generate_linear_lut(filepath):
    """Generates PixelLog_to_Rec2020_Linear.cube (33x33x33 unclipped scene-linear)"""
    os.makedirs(os.path.dirname(os.path.abspath(filepath)), exist_ok=True)
    print(f"[LUT Generator] Writing Linear LUT: {filepath} ...")
    with open(filepath, "w") as f:
        f.write("# PixelLog to BT.2020 Scene-Linear Transform LUT\n")
        f.write("# Target Device: Google Pixel 11 Pro Primary Sensor\n")
        f.write("# Transform: 1:1 Unclipped Scene-Linear for ACEScg / DaVinci YRGB Color Managed\n")
        f.write(f"TITLE \"PixelLog_to_Rec2020_Linear\"\n")
        f.write(f"LUT_3D_SIZE {LUT_SIZE}\n")
        f.write("DOMAIN_MIN 0.0 0.0 0.0\n")
        f.write("DOMAIN_MAX 1.0 1.0 1.0\n\n")

        for b_idx in range(LUT_SIZE):
            p_b = b_idx / (LUT_SIZE - 1)
            for g_idx in range(LUT_SIZE):
                p_g = g_idx / (LUT_SIZE - 1)
                for r_idx in range(LUT_SIZE):
                    p_r = r_idx / (LUT_SIZE - 1)

                    lin_r = pixel_log_eotf(p_r)
                    lin_g = pixel_log_eotf(p_g)
                    lin_b = pixel_log_eotf(p_b)

                    f.write(f"{lin_r:.6f} {lin_g:.6f} {lin_b:.6f}\n")
    print(f"[LUT Generator] Completed: {filepath}")

if __name__ == "__main__":
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)))
    disp_path = os.path.join(out_dir, "PixelLog_to_Rec709_Display.cube")
    lin_path = os.path.join(out_dir, "PixelLog_to_Rec2020_Linear.cube")
    generate_display_lut(disp_path)
    generate_linear_lut(lin_path)
