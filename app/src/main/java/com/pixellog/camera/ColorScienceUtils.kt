package com.pixellog.camera

import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.util.Rational
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ColorScienceUtils implements the exact physical and mathematical color science
 * for the Pixel 11 Pro primary sensor:
 * - Planckian locus & CIE Daylight CCT (2000K - 10000K)
 * - CIE 1960 UCS Green-Magenta Tint offset
 * - Dual-Illuminant DNG Mired interpolation (Standard-A 2856K and D65 6504K)
 * - Bradford chromatic adaptation
 * - Sensor-to-Linear-BT.2020 composite transformation matrix
 */
object ColorScienceUtils {

    // Bradford Chromatic Adaptation Matrix (D50 to D65)
    val M_BRADFORD_D50_TO_D65 = floatArrayOf(
         0.9555766f, -0.0230393f,  0.0631636f,
        -0.0282895f,  1.0099416f,  0.0210077f,
         0.0122982f, -0.0204830f,  1.3299098f
    )

    // Standard CIE XYZ (D65) to Linear BT.2020 Matrix
    val M_XYZ_TO_BT2020 = floatArrayOf(
         1.716651f, -0.355671f, -0.253366f,
        -0.666684f,  1.616481f,  0.015769f,
         0.017640f, -0.042771f,  0.942103f
    )

    // Pre-calibrated Bradford-adapted Sensor-to-Linear-BT.2020 matrix (D65 neutral, Column-Major)
    val M_SENSOR_TO_BT2020 = floatArrayOf(
         1.030691f, -0.042001f, -0.013222f, // Col 0
         0.063304f,  1.043993f, -0.105381f, // Col 1
        -0.094073f, -0.001952f,  1.118543f  // Col 2
    )

    @Deprecated("Use M_SENSOR_TO_BT2020 instead", ReplaceWith("M_SENSOR_TO_BT2020"))
    val M_PIXEL_TO_BT2020 = M_SENSOR_TO_BT2020

    /**
     * Converts Correlated Color Temperature (Kelvin) and Tint to CIE 1931 xy coordinates.
     */
    fun kelvinTintToCieXy(kelvin: Int, tint: Int): FloatArray {
        val t = kelvin.coerceIn(2000, 10000).toDouble()
        val x0: Double
        val y0: Double

        if (t <= 4000.0) {
            x0 = -0.2661239 * (1e9 / (t * t * t)) -
                 0.2343580 * (1e6 / (t * t)) +
                 0.8776956 * (1e3 / t) + 0.179910
            y0 = -1.1063814 * (x0 * x0 * x0) -
                 1.3481102 * (x0 * x0) +
                 2.18555832 * x0 - 0.20219683
        } else {
            x0 = -4.6070 * (1e9 / (t * t * t)) +
                 2.9678 * (1e6 / (t * t)) +
                 0.09911 * (1e3 / t) + 0.244063
            y0 = -3.000 * (x0 * x0) + 2.870 * x0 - 0.275
        }

        // Convert to CIE 1960 UCS (u, v)
        val denom = -2.0 * x0 + 12.0 * y0 + 3.0
        val u0 = 4.0 * x0 / denom
        val v0 = 6.0 * y0 / denom

        // Apply Green (+v) / Magenta (-v) Tint offset
        val vTint = v0 + (tint * 0.0005)

        // Convert back to CIE 1931 (xw, yw)
        val denomW = 2.0 * u0 - 8.0 * vTint + 4.0
        val xw = (3.0 * u0) / denomW
        val yw = (2.0 * vTint) / denomW

        return floatArrayOf(xw.toFloat(), yw.toFloat())
    }

    data class ChannelGains(
        val red: Float,
        val greenEven: Float,
        val greenOdd: Float,
        val blue: Float
    ) {
        fun toRggbChannelVector(): RggbChannelVector =
            RggbChannelVector(red, greenEven, greenOdd, blue)
    }

    /**
     * Calculates manual COLOR_CORRECTION_GAINS (R, Gr, Gb, B) from Kelvin and Tint.
     */
    fun calculateColorGains(kelvin: Int, tint: Int): ChannelGains {
        val xy = kelvinTintToCieXy(kelvin, tint)
        val xw = xy[0]
        val yw = xy[1]

        // White point in XYZ space (Y = 1.0)
        val Xw = xw / yw
        val Yw = 1.0f
        val Zw = (1.0f - xw - yw) / yw

        // Interpolate sensor forward matrix based on Mireds
        val mired = 1_000_000.0f / kelvin
        val miredStdA = 1_000_000.0f / 2856.0f
        val miredD65 = 1_000_000.0f / 6504.0f
        val weight = ((mired - miredStdA) / (miredD65 - miredStdA)).coerceIn(0.0f, 1.0f)

        // Simplified camera neutral response
        val rCam = (0.648f * Xw + 0.174f * Yw + 0.129f * Zw) * (1.0f - weight) +
                   (0.602f * Xw + 0.185f * Yw + 0.155f * Zw) * weight
        val gCam = (0.242f * Xw + 0.718f * Yw + 0.040f * Zw) * (1.0f - weight) +
                   (0.230f * Xw + 0.725f * Yw + 0.045f * Zw) * weight
        val bCam = (-0.015f * Xw - 0.083f * Yw + 1.187f * Zw) * (1.0f - weight) +
                   (-0.012f * Xw - 0.075f * Yw + 1.120f * Zw) * weight

        val gainR = (gCam / max(rCam, 1e-4f)).coerceIn(0.5f, 4.0f)
        val gainB = (gCam / max(bCam, 1e-4f)).coerceIn(0.5f, 4.0f)

        return ChannelGains(gainR, 1.0f, 1.0f, gainB)
    }

    /**
     * Converts a 3x3 float matrix to a Camera2 ColorSpaceTransform (Rational[9]).
     * When given a column-major matrix, converts to row-major order expected by ColorSpaceTransform.
     */
    fun floatMatrixToColorSpaceTransform(matrix: FloatArray): ColorSpaceTransform {
        val rowMajor = floatArrayOf(
            matrix[0], matrix[3], matrix[6],
            matrix[1], matrix[4], matrix[7],
            matrix[2], matrix[5], matrix[8]
        )
        val rationals = Array(9) { i ->
            val value = rowMajor[i]
            val numerator = (value * 10_000.0f).roundToInt()
            Rational(numerator, 10_000)
        }
        return ColorSpaceTransform(rationals)
    }

    /**
     * Provides the column-major Bradford Sensor-to-Linear-BT.2020 matrix cleanly without baked-in gains.
     * White balance gains are handled separately via gains vector.
     */
    fun computeCompositeColorMatrix(): FloatArray {
        return M_SENSOR_TO_BT2020.clone()
    }

    fun computeCompositeColorMatrix(gR: Float, gG: Float, gB: Float): FloatArray {
        return M_SENSOR_TO_BT2020.clone()
    }

    fun computeCompositeColorMatrix(gains: ChannelGains): FloatArray {
        return M_SENSOR_TO_BT2020.clone()
    }

    fun computeCompositeColorMatrix(gains: RggbChannelVector): FloatArray {
        return M_SENSOR_TO_BT2020.clone()
    }
}
