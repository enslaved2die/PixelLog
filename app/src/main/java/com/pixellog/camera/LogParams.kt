package com.pixellog.camera

/**
 * AUTO-GENERATED from log_params.json by tools/solve_log.py.
 * DO NOT HAND-EDIT.
 */
object LogParams {
    const val CURVE_NAME = "Pixel-Log"
    const val GAMUT = "Rec.2020"

    const val YB = 0.09000000f
    const val YM = 0.40000000f
    const val K = 5.50000000f
    const val XMAX = 8.14587012f

    const val BETA = 0.03215250f
    const val GAMMA = 0.11388271f
    const val DELTA = 0.65473586f
    const val S = 5.10996113f
    const val INV_GAMMA = 8.78096412f
    const val INV_S = 0.19569620f

    fun forward(x: Float): Float {
        return if (x >= 0.0f) {
            GAMMA * (Math.log((x + BETA).toDouble()) / Math.log(2.0)).toFloat() + DELTA
        } else {
            YB + S * x
        }
    }

    fun inverse(y: Float): Float {
        return if (y >= YB) {
            Math.pow(2.0, ((y - DELTA) * INV_GAMMA).toDouble()).toFloat() - BETA
        } else {
            (y - YB) * INV_S
        }
    }
}
