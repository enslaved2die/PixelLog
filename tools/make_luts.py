#!/usr/bin/env python3
"""
tools/make_luts.py

Generates all NLE post-production deliverables from log_params.json:
1. LogToLinear_1D.cube (1D LUT, 4096 points, output normalized to x / xmax)
2. Gamut_Rec2020_to_*.txt (Rec.709, ACEScg AP1, DaVinci Wide Gamut)
3. PixelLog_Inverse.dctl (DaVinci Resolve DCTL: exact inverse + gamut transforms)
4. PixelLog_to_Rec709_65.cube (33x33x33 technical preview LUT with filmic curve)
5. post_production/README.md with setup instructions
"""

import math
import json
import os
import shutil

# Standard 3x3 Matrix Multiplication and Inversion
def mat33_mul_vec3(m, v):
    return [
        m[0]*v[0] + m[1]*v[1] + m[2]*v[2],
        m[3]*v[0] + m[4]*v[1] + m[5]*v[2],
        m[6]*v[0] + m[7]*v[1] + m[8]*v[2]
    ]

def mat33_mul_mat33(a, b):
    res = [0.0]*9
    for r in range(3):
        for c in range(3):
            res[r*3 + c] = a[r*3 + 0]*b[0*3 + c] + a[r*3 + 1]*b[1*3 + c] + a[r*3 + 2]*b[2*3 + c]
    return res

# Standard CIE 1931 xy to RGB-XYZ matrix generator
def get_rgb_to_xyz_matrix(rx, ry, gx, gy, bx, by, wx, wy):
    rz = 1.0 - rx - ry
    gz = 1.0 - gx - gy
    bz = 1.0 - bx - by
    wz = 1.0 - wx - wy

    Xw = wx / wy
    Yw = 1.0
    Zw = wz / wy

    # Matrix M = [[rx/ry, gx/gy, bx/by], [1, 1, 1], [rz/ry, gz/gy, bz/by]]
    # Solve M * [Sr, Sg, Sb]^T = [Xw, Yw, Zw]^T
    m00, m01, m02 = rx / ry, gx / gy, bx / by
    m10, m11, m12 = 1.0, 1.0, 1.0
    m20, m21, m22 = rz / ry, gz / gy, bz / by

    # Invert M
    det = (m00*(m11*m22 - m12*m21) -
           m01*(m10*m22 - m12*m20) +
           m02*(m10*m21 - m11*m20))

    inv = [
        (m11*m22 - m12*m21) / det, (m02*m21 - m01*m22) / det, (m01*m12 - m02*m11) / det,
        (m12*m20 - m10*m22) / det, (m00*m22 - m02*m20) / det, (m02*m10 - m00*m12) / det,
        (m10*m21 - m11*m20) / det, (m01*m20 - m00*m21) / det, (m00*m11 - m01*m10) / det
    ]

    Sr = inv[0]*Xw + inv[1]*Yw + inv[2]*Zw
    Sg = inv[3]*Xw + inv[4]*Yw + inv[5]*Zw
    Sb = inv[6]*Xw + inv[7]*Yw + inv[8]*Zw

    # Final RGB -> XYZ matrix
    rgb_to_xyz = [
        Sr*m00, Sg*m01, Sb*m02,
        Sr*m10, Sg*m11, Sb*m12,
        Sr*m20, Sg*m21, Sb*m22
    ]
    return rgb_to_xyz

def invert_mat33(m):
    det = (m[0]*(m[4]*m[8] - m[5]*m[7]) -
           m[1]*(m[3]*m[8] - m[5]*m[6]) +
           m[2]*(m[3]*m[7] - m[4]*m[6]))
    return [
        (m[4]*m[8] - m[5]*m[7]) / det, (m[2]*m[7] - m[1]*m[8]) / det, (m[1]*m[5] - m[2]*m[4]) / det,
        (m[5]*m[6] - m[3]*m[8]) / det, (m[0]*m[8] - m[2]*m[6]) / det, (m[2]*m[3] - m[0]*m[5]) / det,
        (m[3]*m[7] - m[4]*m[6]) / det, (m[1]*m[6] - m[0]*m[7]) / det, (m[0]*m[4] - m[1]*m[3]) / det
    ]

# Primaries & White Points
# Rec.2020 (D65)
M_REC2020_TO_XYZ = get_rgb_to_xyz_matrix(0.708, 0.292, 0.170, 0.797, 0.131, 0.046, 0.3127, 0.3290)
# Rec.709 (D65)
M_REC709_TO_XYZ  = get_rgb_to_xyz_matrix(0.640, 0.330, 0.300, 0.600, 0.150, 0.060, 0.3127, 0.3290)
M_XYZ_TO_REC709  = invert_mat33(M_REC709_TO_XYZ)
M_REC2020_TO_REC709 = mat33_mul_mat33(M_XYZ_TO_REC709, M_REC2020_TO_XYZ)

# DaVinci Wide Gamut (D65)
M_DWG_TO_XYZ     = get_rgb_to_xyz_matrix(0.800, 0.3130, 0.1682, 0.9877, 0.0790, -0.1155, 0.3127, 0.3290)
M_XYZ_TO_DWG     = invert_mat33(M_DWG_TO_XYZ)
M_REC2020_TO_DWG = mat33_mul_mat33(M_XYZ_TO_DWG, M_REC2020_TO_XYZ)

# ACEScg AP1 (D60 with Bradford D65->D60 adaptation)
# Standard published Rec2020 to ACEScg matrix:
# [ 0.613097, 0.339523, 0.047379 ]
# [ 0.070194, 0.916354, 0.013452 ]
# [ 0.020616, 0.109570, 0.869814 ]
M_REC2020_TO_ACESCG = [
    0.613097, 0.339523, 0.047379,
    0.070194, 0.916354, 0.013452,
    0.020616, 0.109570, 0.869814
]

def inv_log(y, p):
    if y >= p["yb"]:
        return (2.0 ** ((y - p["delta"]) * p["inv_gamma"])) - p["beta"]
    else:
        return (y - p["yb"]) * p["inv_s"]

def fwd_log(x, p):
    if x >= 0.0:
        return p["gamma"] * math.log2(x + p["beta"]) + p["delta"]
    else:
        return p["yb"] + p["s"] * x

def write_1d_lut(out_path, p):
    points = 4096
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w") as f:
        f.write("# PixelLog 1D Log-to-Linear De-Log LUT\n")
        f.write("# Curve: Pixel-Log C1 log2 + linear toe\n")
        f.write(f"# Input: [0.0, 1.0] Log Code | Output: Scene-Linear x / xmax (xmax = {p['xmax']:.6f})\n")
        f.write(f"# Middle Grey (y={p['ym']:.2f}) -> x/xmax = {0.18 / p['xmax']:.6f}\n")
        f.write("LUT_1D_SIZE 4096\n")
        f.write("DOMAIN_MIN 0.0\n")
        f.write("DOMAIN_MAX 1.0\n\n")
        for i in range(points):
            y = i / (points - 1)
            x = inv_log(y, p)
            norm_x = x / p["xmax"]
            f.write(f"{norm_x:.7f}\n")
    print(f"[make_luts] Generated 1D LUT: {out_path}")

def write_gamut_matrices(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    targets = [
        ("Gamut_Rec2020_to_Rec709.txt", "Rec.2020 to Rec.709 Linear Matrix", M_REC2020_TO_REC709),
        ("Gamut_Rec2020_to_ACEScg.txt", "Rec.2020 to ACEScg (AP1) Linear Matrix", M_REC2020_TO_ACESCG),
        ("Gamut_Rec2020_to_DWG.txt", "Rec.2020 to DaVinci Wide Gamut Matrix", M_REC2020_TO_DWG),
    ]
    for filename, title, m in targets:
        path = os.path.join(out_dir, filename)
        with open(path, "w") as f:
            f.write(f"# {title}\n")
            f.write(f"{m[0]:.6f} {m[1]:.6f} {m[2]:.6f}\n")
            f.write(f"{m[3]:.6f} {m[4]:.6f} {m[5]:.6f}\n")
            f.write(f"{m[6]:.6f} {m[7]:.6f} {m[8]:.6f}\n")
        print(f"[make_luts] Generated matrix: {path}")

def write_dctl(out_path, p):
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    dctl = f"""// ==============================================================================
// PixelLog_Inverse.dctl
// DaVinci Resolve DCTL: Exact Pixel-Log Inverse & Gamut Transforms
// AUTO-GENERATED from log_params.json by tools/make_luts.py
// ==============================================================================

DEFINE_UI_PARAMS(p_Direction, Transform Direction, DCTLUI_COMBO_BOX, 1, {{0, 1}}, {{Scene-Linear to Pixel-Log (Forward), Pixel-Log to Scene-Linear (Inverse)}})
DEFINE_UI_PARAMS(p_Exposure, Exposure Trim (Stops), DCTLUI_SLIDER_FLOAT, 0.0, -5.0, 5.0, 0.01)
DEFINE_UI_PARAMS(p_Gamut, Output Gamut Mode, DCTLUI_COMBO_BOX, 0, {{0, 1, 2, 3}}, {{Native BT.2020, Rec.709, ACEScg (AP1), DaVinci Wide Gamut}})

// --- Curve Parameters (from log_params.json) ---
#define PL_YB        {p['yb']:.8f}f
#define PL_YM        {p['ym']:.8f}f
#define PL_K         {p['k']:.8f}f
#define PL_XMAX      {p['xmax']:.8f}f
#define PL_BETA      {p['beta']:.8f}f
#define PL_GAMMA     {p['gamma']:.8f}f
#define PL_DELTA     {p['delta']:.8f}f
#define PL_S         {p['s']:.8f}f
#define PL_INV_GAMMA {p['inv_gamma']:.8f}f
#define PL_INV_S     {p['inv_s']:.8f}f

// --- Gamut Matrices ---
__CONSTANT__ float M_BT2020_to_Rec709[9] = {{
    {M_REC2020_TO_REC709[0]:.6f}f, {M_REC2020_TO_REC709[1]:.6f}f, {M_REC2020_TO_REC709[2]:.6f}f,
    {M_REC2020_TO_REC709[3]:.6f}f, {M_REC2020_TO_REC709[4]:.6f}f, {M_REC2020_TO_REC709[5]:.6f}f,
    {M_REC2020_TO_REC709[6]:.6f}f, {M_REC2020_TO_REC709[7]:.6f}f, {M_REC2020_TO_REC709[8]:.6f}f
}};

__CONSTANT__ float M_BT2020_to_ACEScg[9] = {{
    {M_REC2020_TO_ACESCG[0]:.6f}f, {M_REC2020_TO_ACESCG[1]:.6f}f, {M_REC2020_TO_ACESCG[2]:.6f}f,
    {M_REC2020_TO_ACESCG[3]:.6f}f, {M_REC2020_TO_ACESCG[4]:.6f}f, {M_REC2020_TO_ACESCG[5]:.6f}f,
    {M_REC2020_TO_ACESCG[6]:.6f}f, {M_REC2020_TO_ACESCG[7]:.6f}f, {M_REC2020_TO_ACESCG[8]:.6f}f
}};

__CONSTANT__ float M_BT2020_to_DWG[9] = {{
    {M_REC2020_TO_DWG[0]:.6f}f, {M_REC2020_TO_DWG[1]:.6f}f, {M_REC2020_TO_DWG[2]:.6f}f,
    {M_REC2020_TO_DWG[3]:.6f}f, {M_REC2020_TO_DWG[4]:.6f}f, {M_REC2020_TO_DWG[5]:.6f}f,
    {M_REC2020_TO_DWG[6]:.6f}f, {M_REC2020_TO_DWG[7]:.6f}f, {M_REC2020_TO_DWG[8]:.6f}f
}};

__DEVICE__ float fwd_pixellog(float x) {{
    if (x >= 0.0f) {{
        return PL_GAMMA * _log2f(x + PL_BETA) + PL_DELTA;
    }} else {{
        return PL_YB + PL_S * x;
    }}
}}

__DEVICE__ float inv_pixellog(float y) {{
    if (y >= PL_YB) {{
        return _exp2f((y - PL_DELTA) * PL_INV_GAMMA) - PL_BETA;
    }} else {{
        return (y - PL_YB) * PL_INV_S;
    }}
}}

__DEVICE__ float3 mul_m3_v3(const float m[9], float3 v) {{
    return make_float3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z
    );
}}

__DEVICE__ float3 transform(int p_Width, int p_Height, int p_X, int p_Y, float p_R, float p_G, float p_B) {{
    float3 in_rgb = make_float3(p_R, p_G, p_B);
    float3 out_rgb;
    float exp_scale = _exp2f(p_Exposure);

    if (p_Direction == 0) {{
        // Forward: Linear -> Pixel-Log
        in_rgb.x *= exp_scale;
        in_rgb.y *= exp_scale;
        in_rgb.z *= exp_scale;
        out_rgb.x = fwd_pixellog(in_rgb.x);
        out_rgb.y = fwd_pixellog(in_rgb.y);
        out_rgb.z = fwd_pixellog(in_rgb.z);
    }} else {{
        // Inverse: Pixel-Log -> Scene-Linear
        out_rgb.x = inv_pixellog(in_rgb.x);
        out_rgb.y = inv_pixellog(in_rgb.y);
        out_rgb.z = inv_pixellog(in_rgb.z);

        out_rgb.x *= exp_scale;
        out_rgb.y *= exp_scale;
        out_rgb.z *= exp_scale;

        // Gamut Adaptation
        if (p_Gamut == 1) {{
            out_rgb = mul_m3_v3(M_BT2020_to_Rec709, out_rgb);
        }} else if (p_Gamut == 2) {{
            out_rgb = mul_m3_v3(M_BT2020_to_ACEScg, out_rgb);
        }} else if (p_Gamut == 3) {{
            out_rgb = mul_m3_v3(M_BT2020_to_DWG, out_rgb);
        }}
    }}
    return out_rgb;
}}
"""
    with open(out_path, "w") as f:
        f.write(dctl)
    print(f"[make_luts] Generated DCTL: {out_path}")

def filmic_tone_curve(x):
    """
    Rectified Michaelis-Menten rational tone curve.
    Maps scene-linear light to display-linear [0.0, 1.0]:
      - 18% middle grey (x = 0.18) -> 42.0 IRE (0.1888 linear -> 0.4205 Rec.709 OETF)
      - 100% diffuse white (x = 1.0) -> 90.0 IRE (0.8088 linear -> 0.8995 Rec.709 OETF)
      - Dynamic range ceiling xmax = 8.146 (+5.5 stops) -> 99.8 IRE with gentle asymptotic roll-off
    """
    if x <= 0.0:
        return 0.0
    a = 0.9308
    b = 0.1685
    c = 0.1685
    d = 0.2599
    num = x * (a * x + b)
    den = x * (a * x + c) + d
    return min(max(num / den, 0.0), 1.0)

def rec709_oetf(lin):
    if lin <= 0.0:
        return 0.0
    if lin < 0.018:
        return lin * 4.5
    return 1.099 * (lin ** 0.45) - 0.099

# DaVinci Intermediate OETF parameters (Blackmagic Design standard)
DI_A = 0.0075
DI_B = 7.0
DI_C = 0.07329248
DI_M = 10.44426855
DI_LIN_CUT = 0.00262409

def linear_to_davinci_intermediate(lin):
    if lin > DI_LIN_CUT:
        return (math.log2(lin + DI_A) + DI_B) * DI_C
    else:
        return max(lin * DI_M, 0.0)

def write_3d_display_lut(out_path, p, size=33):
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w") as f:
        f.write("# PixelLog to Rec.709 Display Transform 3D LUT\n")
        f.write("# Target Device: Google Pixel 11 Pro Primary Sensor (Tensor G6)\n")
        f.write("# Transform: Pixel-Log BT.2020 -> Scene-Linear -> BT.2020 to Rec.709 -> Rational Tone Map -> Rec.709 OETF\n")
        f.write(f"# Middle Grey (0.40) -> 42.0 IRE | Diffuse White (1.0) -> 90.0 IRE | Highlight Ceiling -> 99.8 IRE\n")
        f.write(f"LUT_3D_SIZE {size}\n")
        f.write("DOMAIN_MIN 0.0 0.0 0.0\n")
        f.write("DOMAIN_MAX 1.0 1.0 1.0\n\n")

        for b_idx in range(size):
            pb = b_idx / (size - 1)
            for g_idx in range(size):
                pg = g_idx / (size - 1)
                for r_idx in range(size):
                    pr = r_idx / (size - 1)

                    lin_bt2020 = [inv_log(pr, p), inv_log(pg, p), inv_log(pb, p)]
                    lin_rec709 = mat33_mul_vec3(M_REC2020_TO_REC709, lin_bt2020)

                    # Rational tone mapping on luminance
                    y_in = 0.2126 * lin_rec709[0] + 0.7152 * lin_rec709[1] + 0.0722 * lin_rec709[2]
                    if y_in > 1e-6:
                        y_out = filmic_tone_curve(y_in)
                        gain = y_out / y_in
                        sat = 1.0 / (1.0 + 0.08 * max(y_in - 0.18, 0.0))
                        mapped = [y_out + (c * gain - y_out) * sat for c in lin_rec709]
                    else:
                        mapped = [0.0, 0.0, 0.0]

                    out_r = rec709_oetf(min(max(mapped[0], 0.0), 1.0))
                    out_g = rec709_oetf(min(max(mapped[1], 0.0), 1.0))
                    out_b = rec709_oetf(min(max(mapped[2], 0.0), 1.0))
                    f.write(f"{out_r:.6f} {out_g:.6f} {out_b:.6f}\n")
    print(f"[make_luts] Generated Display LUT ({size}x{size}x{size}): {out_path}")

# ==============================================================================
# AgX Color Transform Implementation for PixelLog
# Architecture: Troy Sobotka / Blender 4.0+ / OpenColorIO v2
# ==============================================================================

# AgX Inset Matrix (Rec.709 primaries)
AGX_INSET_MATRIX = [
    0.842479062253094, 0.0784335999999992, 0.0792237451477643,
    0.0423282422610123, 0.878468636469772, 0.0791661274605434,
    0.0423756549057051, 0.0784336, 0.879142973793104
]

# AgX Outset Matrix (Inverse Inset)
AGX_OUTSET_MATRIX = [
    1.19687900512017, -0.0980208811401368, -0.0990297440797205,
    -0.0528968517574562, 1.15190312990417, -0.0989611768448433,
    -0.0529716355144438, -0.0980434501171241, 1.15107367264116
]

def agx_default_contrast_approx(x):
    """
    AgX 6th/7th-order polynomial sigmoid contrast approximation
    (Troy Sobotka / Benjamin Wrensch / Three.js / Filament)
    MSE ~ 3.67e-6 against reference OCIO sigmoid.
    """
    x2 = x * x
    x4 = x2 * x2
    return (+ 15.5   * x4 * x2
            - 40.14  * x4 * x
            + 31.96  * x4
            - 6.868  * x2 * x
            + 0.4298 * x2
            + 0.1191 * x
            - 0.00232)

def apply_agx_transform(lin_rec709, look="base"):
    """
    Transforms Linear Rec.709 -> Inset -> Log2 EV -> Sigmoid -> Look -> Outset -> Display Code
    """
    # 1. Inset Matrix (gamut compression & crosstalk)
    val = mat33_mul_vec3(AGX_INSET_MATRIX, lin_rec709)

    # 2. Log2 space encoding: -10 EV to +6.5 EV around 18% middle grey
    min_ev = -12.47393
    max_ev = 4.026069
    inv_range = 1.0 / (max_ev - min_ev)

    norm = []
    for c in val:
        c_clamped = max(c, 1e-10)
        log_c = max(min(math.log2(c_clamped), max_ev), min_ev)
        norm.append((log_c - min_ev) * inv_range)

    # 3. Apply sigmoid function approximation
    sig = [max(min(agx_default_contrast_approx(n), 1.0), 0.0) for n in norm]

    # 4. Optional Creative Look (ASC-CDL)
    if look == "punchy":
        lw = [0.2126, 0.7152, 0.0722]
        # ASC-CDL: slope=1.0, power=1.35, sat=1.40
        pow_sig = [s ** 1.35 for s in sig]
        luma_pow = sum(pow_sig[i] * lw[i] for i in range(3))
        sig = [max(min(luma_pow + 1.40 * (pow_sig[i] - luma_pow), 1.0), 0.0) for i in range(3)]

    # 5. Outset matrix (restores chromaticity to display space)
    out = mat33_mul_vec3(AGX_OUTSET_MATRIX, sig)
    return [max(min(c, 1.0), 0.0) for c in out]

def write_3d_agx_lut(out_path, p, size=33, look="base"):
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    look_title = "Punchy" if look == "punchy" else "Base"
    with open(out_path, "w") as f:
        f.write(f"# PixelLog to AgX ({look_title}) Rec.709 Display Transform 3D LUT\n")
        f.write("# Architecture: AgX Display Rendering Transform (Troy Sobotka / Blender 4.0+)\n")
        f.write("# Target Device: Google Pixel 11 Pro Primary Sensor (Tensor G6)\n")
        f.write("# Transform: Pixel-Log BT.2020 -> Scene-Linear -> BT.2020 to Rec.709 -> AgX Inset -> Log2 EV -> Sigmoid -> Outset\n")
        f.write(f"LUT_3D_SIZE {size}\n")
        f.write("DOMAIN_MIN 0.0 0.0 0.0\n")
        f.write("DOMAIN_MAX 1.0 1.0 1.0\n\n")

        for b_idx in range(size):
            pb = b_idx / (size - 1)
            for g_idx in range(size):
                pg = g_idx / (size - 1)
                for r_idx in range(size):
                    pr = r_idx / (size - 1)

                    lin_bt2020 = [inv_log(pr, p), inv_log(pg, p), inv_log(pb, p)]
                    lin_rec709 = mat33_mul_vec3(M_REC2020_TO_REC709, lin_bt2020)

                    out_rgb = apply_agx_transform(lin_rec709, look=look)
                    f.write(f"{out_rgb[0]:.6f} {out_rgb[1]:.6f} {out_rgb[2]:.6f}\n")
    print(f"[make_luts] Generated AgX Display LUT ({look_title}, {size}x{size}x{size}): {out_path}")

def write_3d_dwg_lut(out_path, p, size=33):
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w") as f:
        f.write("# PixelLog to DaVinci Wide Gamut / DaVinci Intermediate 3D LUT\n")
        f.write("# For DaVinci Resolve Free & Studio (enables built-in CST and DWG grading)\n")
        f.write(f"# Input: Pixel-Log BT.2020 [0, 1] -> Inverse -> BT.2020 to DWG -> DaVinci Intermediate Log\n")
        f.write(f"LUT_3D_SIZE {size}\n")
        f.write("DOMAIN_MIN 0.0 0.0 0.0\n")
        f.write("DOMAIN_MAX 1.0 1.0 1.0\n\n")

        for b_idx in range(size):
            pb = b_idx / (size - 1)
            for g_idx in range(size):
                pg = g_idx / (size - 1)
                for r_idx in range(size):
                    pr = r_idx / (size - 1)

                    lin_bt2020 = [inv_log(pr, p), inv_log(pg, p), inv_log(pb, p)]
                    lin_dwg = mat33_mul_vec3(M_REC2020_TO_DWG, lin_bt2020)

                    out_r = min(max(linear_to_davinci_intermediate(lin_dwg[0]), 0.0), 1.0)
                    out_g = min(max(linear_to_davinci_intermediate(lin_dwg[1]), 0.0), 1.0)
                    out_b = min(max(linear_to_davinci_intermediate(lin_dwg[2]), 0.0), 1.0)
                    f.write(f"{out_r:.6f} {out_g:.6f} {out_b:.6f}\n")
    print(f"[make_luts] Generated DWG/Intermediate LUT ({size}x{size}x{size}): {out_path}")

def write_3d_acescg_lut(out_path, p, size=33):
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w") as f:
        f.write("# PixelLog to ACEScg (AP1) Scene-Linear 3D LUT\n")
        f.write("# For ACEScg VFX pipelines and DaVinci Resolve Free ACES grading\n")
        f.write(f"# Input: Pixel-Log BT.2020 [0, 1] -> Inverse -> BT.2020 to ACEScg (AP1) Linear\n")
        f.write(f"LUT_3D_SIZE {size}\n")
        f.write("DOMAIN_MIN 0.0 0.0 0.0\n")
        f.write("DOMAIN_MAX 1.0 1.0 1.0\n\n")

        for b_idx in range(size):
            pb = b_idx / (size - 1)
            for g_idx in range(size):
                pg = g_idx / (size - 1)
                for r_idx in range(size):
                    pr = r_idx / (size - 1)

                    lin_bt2020 = [inv_log(pr, p), inv_log(pg, p), inv_log(pb, p)]
                    lin_acescg = mat33_mul_vec3(M_REC2020_TO_ACESCG, lin_bt2020)

                    out_r = max(lin_acescg[0], 0.0)
                    out_g = max(lin_acescg[1], 0.0)
                    out_b = max(lin_acescg[2], 0.0)
                    f.write(f"{out_r:.6f} {out_g:.6f} {out_b:.6f}\n")
    print(f"[make_luts] Generated ACEScg LUT ({size}x{size}x{size}): {out_path}")

def write_3d_linear_lut(out_path, p, size=33):
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w") as f:
        f.write("# PixelLog to Rec.2020 Scene-Linear 3D LUT\n")
        f.write(f"# Input: Pixel-Log BT.2020 [0, 1] -> Exact Unclipped Scene-Linear\n")
        f.write(f"LUT_3D_SIZE {size}\n")
        f.write("DOMAIN_MIN 0.0 0.0 0.0\n")
        f.write("DOMAIN_MAX 1.0 1.0 1.0\n\n")

        for b_idx in range(size):
            pb = b_idx / (size - 1)
            for g_idx in range(size):
                pg = g_idx / (size - 1)
                for r_idx in range(size):
                    pr = r_idx / (size - 1)

                    lin_r = max(inv_log(pr, p), 0.0)
                    lin_g = max(inv_log(pg, p), 0.0)
                    lin_b = max(inv_log(pb, p), 0.0)
                    f.write(f"{lin_r:.6f} {lin_g:.6f} {lin_b:.6f}\n")
    print(f"[make_luts] Generated Linear LUT ({size}x{size}x{size}): {out_path}")

def main():
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    json_path = os.path.join(root_dir, "log_params.json")
    if not os.path.exists(json_path):
        print(f"Error: {json_path} not found. Run tools/solve_log.py first.")
        import sys
        sys.exit(1)

    with open(json_path, "r") as f:
        p = json.load(f)

    post_dir = os.path.join(root_dir, "post_production")
    assets_dir = os.path.join(root_dir, "app/src/main/assets/luts")
    os.makedirs(post_dir, exist_ok=True)
    os.makedirs(assets_dir, exist_ok=True)

    # 1. 1D De-Log LUT
    write_1d_lut(os.path.join(post_dir, "LogToLinear_1D.cube"), p)

    # 2. Gamut Matrices
    write_gamut_matrices(post_dir)

    # 3. DCTLs for DaVinci Resolve Studio
    write_dctl(os.path.join(post_dir, "PixelLog_Inverse.dctl"), p)
    write_dctl(os.path.join(post_dir, "PixelLog_Transform.dctl"), p)

    # 4. 3D LUTs (both 33x33x33 and 65x65x65) for DaVinci Resolve Free & Studio
    for size in (33, 65):
        write_3d_display_lut(os.path.join(post_dir, f"PixelLog_to_Rec709_Display_{size}.cube"), p, size)
        write_3d_agx_lut(os.path.join(post_dir, f"PixelLog_to_AgX_Rec709_{size}.cube"), p, size, look="base")
        write_3d_agx_lut(os.path.join(post_dir, f"PixelLog_to_AgX_Punchy_{size}.cube"), p, size, look="punchy")
        write_3d_dwg_lut(os.path.join(post_dir, f"PixelLog_to_DWG_Intermediate_{size}.cube"), p, size)
        write_3d_acescg_lut(os.path.join(post_dir, f"PixelLog_to_ACEScg_{size}.cube"), p, size)
        write_3d_linear_lut(os.path.join(post_dir, f"PixelLog_to_Rec2020_Linear_{size}.cube"), p, size)

    # Backward compatibility filenames in post_production
    shutil.copyfile(
        os.path.join(post_dir, "PixelLog_to_Rec709_Display_65.cube"),
        os.path.join(post_dir, "PixelLog_to_Rec709_65.cube")
    )
    shutil.copyfile(
        os.path.join(post_dir, "PixelLog_to_Rec709_Display_33.cube"),
        os.path.join(post_dir, "PixelLog_to_Rec709_Display.cube")
    )
    shutil.copyfile(
        os.path.join(post_dir, "PixelLog_to_Rec2020_Linear_33.cube"),
        os.path.join(post_dir, "PixelLog_to_Rec2020_Linear.cube")
    )

    # 5. Populate app/src/main/assets/luts for camera app built-in selection
    asset_targets = [
        ("PixelLog_to_Rec709_Display_33.cube", "PixelLog_to_Rec709_Display.cube"),
        ("PixelLog_to_AgX_Rec709_33.cube", "PixelLog_to_AgX_Base.cube"),
        ("PixelLog_to_AgX_Punchy_33.cube", "PixelLog_to_AgX_Punchy.cube"),
        ("PixelLog_to_DWG_Intermediate_33.cube", "PixelLog_to_DWG_Intermediate.cube"),
        ("PixelLog_to_ACEScg_33.cube", "PixelLog_to_ACEScg.cube"),
        ("PixelLog_to_Rec2020_Linear_33.cube", "PixelLog_to_Rec2020_Linear.cube"),
    ]
    for src_name, dst_name in asset_targets:
        src = os.path.join(post_dir, src_name)
        dst = os.path.join(assets_dir, dst_name)
        shutil.copyfile(src, dst)
        print(f"[make_luts] Installed asset LUT: {dst}")

if __name__ == "__main__":
    main()

