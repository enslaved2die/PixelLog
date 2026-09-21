#!/usr/bin/env python3
"""
tools/solve_log.py

Single Source of Truth solver and code generator for the PixelLog transfer curve.
Solves for analytical curve parameters:
  x >= 0: y = gamma * log2(x + beta) + delta
  x < 0:  y = yb + s * x, where s = gamma / (beta * ln(2))
Inverse:
  y >= yb: x = 2^((y - delta)/gamma) - beta
  y < yb:  x = (y - yb) / s

Generates:
  - log_params.json
  - app/src/main/java/com/pixellog/camera/LogParams.kt
  - app/src/main/cpp/include/LogParams.h
"""

import math
import json
import os
import sys

def bisection_solve(f, a, b, tol=1e-12, max_iter=200):
    fa = f(a)
    fb = f(b)
    if fa * fb > 0:
        raise ValueError(f"Root not bracketed: f({a})={fa}, f({b})={fb}")
    for _ in range(max_iter):
        mid = 0.5 * (a + b)
        if (b - a) * 0.5 < tol:
            return mid
        fmid = f(mid)
        if abs(fmid) < tol:
            return mid
        if fa * fmid < 0:
            b = mid
            fb = fmid
        else:
            a = mid
            fa = fmid
    return 0.5 * (a + b)

def solve(yb=0.09, ym=0.40, k=5.5, gamut="Rec.2020"):
    """
    Solves for beta, gamma, delta, s given:
      y(0) = yb
      y(0.18) = ym
      y(xmax) = 1.0, where xmax = 0.18 * 2^k
    """
    xmax = 0.18 * (2.0 ** k)
    
    # Root function for beta:
    # (1 - ym)/(ym - yb) = log2((xmax + b)/(0.18 + b)) / log2((0.18 + b)/b)
    d1 = ym - yb
    d2 = 1.0 - ym
    ratio = d2 / d1

    def f(b):
        num = math.log2((xmax + b) / (0.18 + b))
        den = math.log2((0.18 + b) / b)
        return ratio - (num / den)

    try:
        from scipy.optimize import brentq
        beta = brentq(f, 1e-6, 10.0)
    except ImportError:
        beta = bisection_solve(f, 1e-6, 10.0)

    gamma = d1 / math.log2((0.18 + beta) / beta)
    delta = yb - gamma * math.log2(beta)
    s = gamma / (beta * math.log(2.0))

    params = {
        "curve_name": "Pixel-Log",
        "description": "Bespoke Log2 + linear toe transfer curve for Pixel 11 Pro",
        "gamut": gamut,
        "yb": yb,
        "ym": ym,
        "k": k,
        "xmax": xmax,
        "beta": beta,
        "gamma": gamma,
        "delta": delta,
        "s": s,
        "inv_gamma": 1.0 / gamma,
        "inv_s": 1.0 / s,
        "grey_slope_per_stop": gamma,
        "code_values_per_stop_10bit": gamma * 1023.0
    }
    return params

def fwd_log(x, p):
    if x >= 0.0:
        return p["gamma"] * math.log2(x + p["beta"]) + p["delta"]
    else:
        return p["yb"] + p["s"] * x

def inv_log(y, p):
    if y >= p["yb"]:
        return (2.0 ** ((y - p["delta"]) * p["inv_gamma"])) - p["beta"]
    else:
        return (y - p["yb"]) * p["inv_s"]

def verify_params(p):
    # Check anchors
    y0 = fwd_log(0.0, p)
    ym = fwd_log(0.18, p)
    yx = fwd_log(p["xmax"], p)
    assert abs(y0 - p["yb"]) < 1e-9, f"y(0)={y0} != {p['yb']}"
    assert abs(ym - p["ym"]) < 1e-9, f"y(0.18)={ym} != {p['ym']}"
    assert abs(yx - 1.0) < 1e-9, f"y(xmax)={yx} != 1.0"

    # C1 continuity at x = 0
    # Left derivative = s
    # Right derivative = gamma / (beta * ln 2)
    s_right = p["gamma"] / (p["beta"] * math.log(2.0))
    assert abs(p["s"] - s_right) < 1e-9, f"Slope discontinuity at 0: {p['s']} vs {s_right}"

    # Round trip over log-spaced samples
    for i in range(-500, 500):
        if i == 0:
            x = 0.0
        elif i > 0:
            x = 0.18 * (2.0 ** (i * 0.02))
        else:
            x = -0.05 * (abs(i) / 500.0)
        y = fwd_log(x, p)
        x_rec = inv_log(y, p)
        assert abs(x - x_rec) < 1e-6, f"Roundtrip failed at x={x}: rec={x_rec}"

    print(f"[solve_log] Verification passed: beta={p['beta']:.6f}, gamma={p['gamma']:.6f}, delta={p['delta']:.6f}, s={p['s']:.6f}")

def generate_kotlin(p, out_path):
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    content = f"""package com.pixellog.camera

/**
 * AUTO-GENERATED from log_params.json by tools/solve_log.py.
 * DO NOT HAND-EDIT.
 */
object LogParams {{
    const val CURVE_NAME = "{p['curve_name']}"
    const val GAMUT = "{p['gamut']}"

    const val YB = {p['yb']:.8f}f
    const val YM = {p['ym']:.8f}f
    const val K = {p['k']:.8f}f
    const val XMAX = {p['xmax']:.8f}f

    const val BETA = {p['beta']:.8f}f
    const val GAMMA = {p['gamma']:.8f}f
    const val DELTA = {p['delta']:.8f}f
    const val S = {p['s']:.8f}f
    const val INV_GAMMA = {p['inv_gamma']:.8f}f
    const val INV_S = {p['inv_s']:.8f}f

    fun forward(x: Float): Float {{
        return if (x >= 0.0f) {{
            GAMMA * (Math.log((x + BETA).toDouble()) / Math.log(2.0)).toFloat() + DELTA
        }} else {{
            YB + S * x
        }}
    }}

    fun inverse(y: Float): Float {{
        return if (y >= YB) {{
            Math.pow(2.0, ((y - DELTA) * INV_GAMMA).toDouble()).toFloat() - BETA
        }} else {{
            (y - YB) * INV_S
        }}
    }}
}}
"""
    with open(out_path, "w") as f:
        f.write(content)
    print(f"[solve_log] Generated Kotlin: {out_path}")

def generate_cpp_header(p, out_path):
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    content = f"""#pragma once

// AUTO-GENERATED from log_params.json by tools/solve_log.py.
// DO NOT HAND-EDIT.

#include <cmath>

namespace pixellog {{

constexpr float LOG_YB        = {p['yb']:.8f}f;
constexpr float LOG_YM        = {p['ym']:.8f}f;
constexpr float LOG_K         = {p['k']:.8f}f;
constexpr float LOG_XMAX      = {p['xmax']:.8f}f;

constexpr float LOG_BETA      = {p['beta']:.8f}f;
constexpr float LOG_GAMMA     = {p['gamma']:.8f}f;
constexpr float LOG_DELTA     = {p['delta']:.8f}f;
constexpr float LOG_S         = {p['s']:.8f}f;
constexpr float LOG_INV_GAMMA = {p['inv_gamma']:.8f}f;
constexpr float LOG_INV_S     = {p['inv_s']:.8f}f;

inline float forwardPixelLog(float x) {{
    if (x >= 0.0f) {{
        return LOG_GAMMA * std::log2(x + LOG_BETA) + LOG_DELTA;
    }} else {{
        return LOG_YB + LOG_S * x;
    }}
}}

inline float inversePixelLog(float y) {{
    if (y >= LOG_YB) {{
        return std::exp2((y - LOG_DELTA) * LOG_INV_GAMMA) - LOG_BETA;
    }} else {{
        return (y - LOG_YB) * LOG_INV_S;
    }}
}}

}} // namespace pixellog
"""
    with open(out_path, "w") as f:
        f.write(content)
    print(f"[solve_log] Generated C++ Header: {out_path}")

def main():
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    json_path = os.path.join(root_dir, "log_params.json")
    kt_path = os.path.join(root_dir, "app/src/main/java/com/pixellog/camera/LogParams.kt")
    cpp_path = os.path.join(root_dir, "app/src/main/cpp/include/LogParams.h")

    p = solve(yb=0.09, ym=0.40, k=5.5)
    verify_params(p)

    with open(json_path, "w") as f:
        json.dump(p, f, indent=2)
    print(f"[solve_log] Wrote {json_path}")

    generate_kotlin(p, kt_path)
    generate_cpp_header(p, cpp_path)

if __name__ == "__main__":
    main()
