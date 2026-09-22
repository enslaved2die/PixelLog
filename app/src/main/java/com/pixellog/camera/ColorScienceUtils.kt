package com.pixellog.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.util.Rational
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * CameraCalibration stores the factory colorimetric calibration matrices,
 * reference illuminants, white level, and CFA filter characteristics for a physical sensor.
 */
data class CameraCalibration(
    val cameraId: String,
    val forwardMatrix1: FloatArray, // 3x3 row-major
    val forwardMatrix2: FloatArray, // 3x3 row-major
    val calibrationTransform1: FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    ),
    val calibrationTransform2: FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    ),
    val illuminant1: Int = 17, // Standard-A (2856K)
    val illuminant2: Int = 21, // D65 (6504K)
    val whiteLevel: Float = 4095.0f,
    val dynamicBlackLevel: FloatArray = floatArrayOf(256.0f, 256.0f, 256.0f, 256.0f),
    val bayerPattern: Int = 0 // 0: RGGB
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CameraCalibration
        return cameraId == other.cameraId
    }

    override fun hashCode(): Int = cameraId.hashCode()
}

/**
 * ColorScienceUtils implements the exact physical and mathematical color science
 * for the Pixel 11 Pro multi-lens camera subsystem (Phase 2):
 * - Planckian locus & CIE Daylight CCT (2000K - 10000K)
 * - CIE 1960 UCS Green-Magenta Tint offset
 * - DNG ForwardMatrix & CalibrationTransform Mired interpolation
 * - DNG Neutral-to-Temperature iterative solver
 * - Bradford chromatic adaptation (D50 -> D65)
 * - XYZ (D65) to Rec.2020 linear gamut transformation
 * - Exposure scaling into scene-linear space
 */
object ColorScienceUtils {

    // Bradford Chromatic Adaptation Matrix (D50 to D65) - Row Major
    val M_BRADFORD_D50_TO_D65 = floatArrayOf(
         0.9555766f, -0.0230393f,  0.0631636f,
        -0.0282895f,  1.0099416f,  0.0210077f,
         0.0122982f, -0.0204830f,  1.3299098f
    )

    // Standard CIE XYZ (D65) to Linear BT.2020 Matrix - Row Major
    val M_XYZ_TO_BT2020 = floatArrayOf(
         1.716651f, -0.355671f, -0.253366f,
        -0.666684f,  1.616481f,  0.015769f,
         0.017640f, -0.042771f,  0.942103f
    )

    // Pre-calibrated Bradford-adapted Sensor-to-Linear-BT.2020 fallback (D65 neutral, Column-Major)
    val M_SENSOR_TO_BT2020 = floatArrayOf(
         1.030691f, -0.042001f, -0.013222f, // Col 0
         0.063304f,  1.043993f, -0.105381f, // Col 1
        -0.094073f, -0.001952f,  1.118543f  // Col 2
    )

    @Deprecated("Use M_SENSOR_TO_BT2020 instead", ReplaceWith("M_SENSOR_TO_BT2020"))
    val M_PIXEL_TO_BT2020 = M_SENSOR_TO_BT2020

    // Reference Illuminant Temperatures
    const val TEMP_STANDARD_A = 2856.0f
    const val TEMP_D65        = 6504.0f
    const val MIRED_STANDARD_A = 1_000_000.0f / TEMP_STANDARD_A // ~350.14
    const val MIRED_D65        = 1_000_000.0f / TEMP_D65        // ~153.75

    // Pixel-Log Exposure Scaling Constant (k = 5.5 stops above 18% grey)
    const val LOG_XMAX = 8.14587012f

    // Factory Calibrated Profiles from Google Pixel 11 Pro HAL (grizzly)
    val DEFAULT_CALIBRATION_WIDE = CameraCalibration(
        cameraId = "2",
        forwardMatrix1 = floatArrayOf(
            0.6116f, 0.1736f, 0.1791f,
            0.1363f, 0.8365f, 0.0272f,
           -0.0418f, -0.5848f, 1.4517f
        ),
        forwardMatrix2 = floatArrayOf(
            0.7145f, -0.0008f, 0.2506f,
            0.2755f, 0.6223f, 0.1021f,
            0.0512f, -0.5060f, 1.2799f
        ),
        calibrationTransform1 = floatArrayOf(
            0.992281f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.002768f
        ),
        calibrationTransform2 = floatArrayOf(
            0.989590f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.998444f
        ),
        whiteLevel = 4095.0f,
        dynamicBlackLevel = floatArrayOf(256.0f, 256.0f, 256.0f, 256.0f),
        bayerPattern = 2 // GBRG
    )

    val DEFAULT_CALIBRATION_ULTRAWIDE = CameraCalibration(
        cameraId = "3",
        forwardMatrix1 = floatArrayOf(
            0.7391f, 0.1889f, 0.0363f,
            0.2698f, 0.8598f, -0.1296f,
           -0.0084f, -0.5440f, 1.3775f
        ),
        forwardMatrix2 = floatArrayOf(
            0.7166f, 0.1439f, 0.1038f,
            0.2944f, 0.7786f, -0.0730f,
            0.0470f, -0.4644f, 1.2425f
        ),
        calibrationTransform1 = floatArrayOf(
            0.979521f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.983538f
        ),
        calibrationTransform2 = floatArrayOf(
            0.981904f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.986035f
        ),
        whiteLevel = 1023.0f,
        dynamicBlackLevel = floatArrayOf(64.0f, 64.0f, 64.0f, 64.0f),
        bayerPattern = 0 // RGGB
    )

    val DEFAULT_CALIBRATION_TELE = CameraCalibration(
        cameraId = "4",
        forwardMatrix1 = floatArrayOf(
            0.7391f, 0.1888f, 0.0363f,
            0.2698f, 0.8597f, -0.1295f,
           -0.0082f, -0.5439f, 1.3772f
        ),
        forwardMatrix2 = floatArrayOf(
            0.7166f, 0.1439f, 0.1038f,
            0.2943f, 0.7786f, -0.0730f,
            0.0470f, -0.4644f, 1.2425f
        ),
        calibrationTransform1 = floatArrayOf(
            0.987344f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.010621f
        ),
        calibrationTransform2 = floatArrayOf(
            0.982670f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.009225f
        ),
        whiteLevel = 1023.0f,
        dynamicBlackLevel = floatArrayOf(64.0f, 64.0f, 64.0f, 64.0f),
        bayerPattern = 3 // BGGR
    )

    fun getDefaultCalibration(cameraId: String?): CameraCalibration {
        return when (cameraId) {
            "3" -> DEFAULT_CALIBRATION_ULTRAWIDE
            "4" -> DEFAULT_CALIBRATION_TELE
            else -> DEFAULT_CALIBRATION_WIDE
        }
    }

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
     * Computes the theoretical sensor neutral point [nR, 1.0, nB] along the Planckian / Daylight locus
     * for a given Kelvin temperature by inverting the ForwardMatrix (SensorRGB = FM^-1 * XYZ).
     */
    fun computeModelNeutralPoint(kelvin: Int, calibration: CameraCalibration? = null): FloatArray {
        val xy = kelvinTintToCieXy(kelvin, 0)
        val xw = xy[0]
        val yw = xy[1]

        val Xw = xw / yw
        val Yw = 1.0f
        val Zw = (1.0f - xw - yw) / yw

        val mired = 1_000_000.0f / kelvin.coerceIn(2000, 10000)
        val weight = ((mired - MIRED_STANDARD_A) / (MIRED_D65 - MIRED_STANDARD_A)).coerceIn(0.0f, 1.0f)

        val calib = calibration ?: DEFAULT_CALIBRATION_WIDE
        val fm1 = calib.forwardMatrix1
        val fm2 = if (calib.forwardMatrix2.size >= 9) calib.forwardMatrix2 else fm1

        // Interpolate ForwardMatrix at current mired
        val fmInterp = FloatArray(9)
        for (i in 0 until 9) {
            fmInterp[i] = fm1[i] * (1.0f - weight) + fm2[i] * weight
        }

        // Invert ForwardMatrix: SensorRGB = FM^-1 * XYZ
        val invFm = invertMat3(fmInterp)

        val rCam = invFm[0] * Xw + invFm[1] * Yw + invFm[2] * Zw
        val gCam = invFm[3] * Xw + invFm[4] * Yw + invFm[5] * Zw
        val bCam = invFm[6] * Xw + invFm[7] * Yw + invFm[8] * Zw

        val gNorm = max(gCam, 1e-4f)
        return floatArrayOf(
            (rCam / gNorm).coerceIn(0.1f, 10.0f),
            1.0f,
            (bCam / gNorm).coerceIn(0.1f, 10.0f)
        )
    }

    /**
     * Calculates the sensor neutral point [nR, 1.0, nB] for a given Kelvin temperature,
     * maintaining the live scene's auto green-magenta tint via the Planckian ratio:
     * n(T) = n_live * (n_model(T) / n_model(T_live))
     */
    fun calculateNeutralColorPoint(
        kelvin: Int,
        liveNeutral: FloatArray? = null,
        liveKelvin: Int = 5600,
        calibration: CameraCalibration? = null
    ): FloatArray {
        val targetModel = computeModelNeutralPoint(kelvin, calibration)
        if (liveNeutral == null || liveNeutral.size < 3) {
            return targetModel
        }
        val liveModel = computeModelNeutralPoint(liveKelvin.coerceIn(2000, 10000), calibration)

        val rRatio = targetModel[0] / max(liveModel[0], 1e-4f)
        val bRatio = targetModel[2] / max(liveModel[2], 1e-4f)

        return floatArrayOf(
            (liveNeutral[0] * rRatio).coerceIn(0.1f, 10.0f),
            1.0f,
            (liveNeutral[2] * bRatio).coerceIn(0.1f, 10.0f)
        )
    }

    fun calculateColorGains(kelvin: Int, tint: Int = 0, calibration: CameraCalibration? = null): ChannelGains {
        val neutral = computeModelNeutralPoint(kelvin, calibration)
        val gainR = (1.0f / max(neutral[0], 1e-4f)).coerceIn(0.2f, 5.0f)
        val gainB = (1.0f / max(neutral[2], 1e-4f)).coerceIn(0.2f, 5.0f)
        return ChannelGains(gainR, 1.0f, 1.0f, gainB)
    }

    // =========================================================================
    // 3x3 Matrix Linear Algebra Utilities (Row-Major Representation)
    // =========================================================================

    fun mulMat3(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(9)
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                r[i * 3 + j] = a[i * 3 + 0] * b[0 * 3 + j] +
                               a[i * 3 + 1] * b[1 * 3 + j] +
                               a[i * 3 + 2] * b[2 * 3 + j]
            }
        }
        return r
    }

    fun invertMat3(m: FloatArray): FloatArray {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
                  m[1] * (m[3] * m[8] - m[5] * m[6]) +
                  m[2] * (m[3] * m[7] - m[4] * m[6])
        if (Math.abs(det) < 1e-8f) {
            // Degenerate matrix fallback: identity
            return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
        val invDet = 1.0f / det
        return floatArrayOf(
            (m[4] * m[8] - m[5] * m[7]) * invDet, (m[2] * m[7] - m[1] * m[8]) * invDet, (m[1] * m[5] - m[2] * m[4]) * invDet,
            (m[5] * m[6] - m[3] * m[8]) * invDet, (m[0] * m[8] - m[2] * m[6]) * invDet, (m[2] * m[3] - m[0] * m[5]) * invDet,
            (m[3] * m[7] - m[4] * m[6]) * invDet, (m[1] * m[6] - m[0] * m[7]) * invDet, (m[0] * m[4] - m[1] * m[3]) * invDet
        )
    }

    fun rowMajorToColumnMajor(rowMajor: FloatArray): FloatArray {
        return floatArrayOf(
            rowMajor[0], rowMajor[3], rowMajor[6],
            rowMajor[1], rowMajor[4], rowMajor[7],
            rowMajor[2], rowMajor[5], rowMajor[8]
        )
    }

    fun columnMajorToRowMajor(colMajor: FloatArray): FloatArray {
        return rowMajorToColumnMajor(colMajor)
    }

    fun colorSpaceTransformToFloatArray(cst: ColorSpaceTransform?): FloatArray? {
        if (cst == null) return null
        val arr = FloatArray(9)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val r = cst.getElement(col, row)
                arr[row * 3 + col] = r.toFloat()
            }
        }
        return arr
    }

    fun floatMatrixToColorSpaceTransform(matrix: FloatArray): ColorSpaceTransform {
        val rowMajor = if (matrix.size == 9) matrix else FloatArray(9) { 0f }
        val rationals = Array(9) { i ->
            val value = rowMajor[i]
            val numerator = (value * 10_000.0f).roundToInt()
            Rational(numerator, 10_000)
        }
        return ColorSpaceTransform(rationals)
    }

    /**
     * Estimates Correlated Color Temperature (Kelvin) from SENSOR_NEUTRAL_COLOR_POINT
     * using the ForwardMatrix and McCamy's empirical CCT cubic equation on CIE (x, y).
     * n = [n_R, n_G, n_B] where n_G is 1.0.
     */
    fun estimateTemperatureFromNeutral(
        neutralPoint: FloatArray,
        calibration: CameraCalibration? = null
    ): Float {
        if (neutralPoint.size < 3) return 5600.0f
        val nR = max(neutralPoint[0], 0.01f)
        val nG = max(neutralPoint[1], 0.01f)
        val nB = max(neutralPoint[2], 0.01f)

        val calib = calibration ?: DEFAULT_CALIBRATION_WIDE
        val fm = calib.forwardMatrix1
        val X = fm[0] * nR + fm[1] * nG + fm[2] * nB
        val Y = fm[3] * nR + fm[4] * nG + fm[5] * nB
        val Z = fm[6] * nR + fm[7] * nG + fm[8] * nB

        val sum = X + Y + Z
        if (sum <= 1e-4f) return 5600.0f

        val x = (X / sum).toDouble()
        val y = (Y / sum).toDouble()

        val denom = 0.1858 - y
        if (Math.abs(denom) < 1e-5) return 5600.0f

        // McCamy's equation
        val n = (x - 0.3320) / denom
        val cct = 449.0 * (n * n * n) + 3525.0 * (n * n) + 6823.3 * n + 5520.33
        return cct.toFloat().coerceIn(2000.0f, 10000.0f)
    }

    /**
     * Evaluates composite Sensor -> Linear BT.2020 matrix following Phase 2:
     * 1. Interpolate ForwardMatrix and CalibrationTransform between illuminants 1 and 2 in mired
     * 2. Compute M_cam_to_XYZ = FM(T) * CC(T)^-1
     * 3. Apply Bradford D50 -> D65 adaptation
     * 4. Apply XYZ(D65) -> Rec.2020 linear gamut transformation
     * 5. Multiply by exposure gain g = xmax
     * 6. Output column-major 3x3 for GLSL uniform
     */
    fun computeCompositeColorMatrix(
        neutralPoint: FloatArray? = null,
        calibration: CameraCalibration? = null,
        exposureGain: Float = LOG_XMAX
    ): FloatArray {
        val calib = calibration ?: DEFAULT_CALIBRATION_WIDE
        if (calib.forwardMatrix1.size < 9) {
            val m = M_SENSOR_TO_BT2020.clone()
            for (i in m.indices) m[i] *= exposureGain
            return m
        }

        val neutral = neutralPoint ?: floatArrayOf(0.55f, 1.0f, 0.70f)
        val tempK = estimateTemperatureFromNeutral(neutral, calib)
        val mired = 1_000_000.0f / tempK

        // Mired linear interpolation weight: w = (M - M1) / (M2 - M1)
        val w = ((mired - MIRED_STANDARD_A) / (MIRED_D65 - MIRED_STANDARD_A)).coerceIn(0.0f, 1.0f)

        // 1. Interpolate ForwardMatrix
        val fmInterp = FloatArray(9)
        val fm1 = calib.forwardMatrix1
        val fm2 = if (calib.forwardMatrix2.size >= 9) calib.forwardMatrix2 else fm1
        for (i in 0 until 9) {
            fmInterp[i] = fm1[i] * (1.0f - w) + fm2[i] * w
        }

        // 2. Interpolate CalibrationTransform & Invert
        val ccInterp = FloatArray(9)
        val cc1 = calib.calibrationTransform1
        val cc2 = if (calib.calibrationTransform2.size >= 9) calib.calibrationTransform2 else cc1
        for (i in 0 until 9) {
            ccInterp[i] = cc1[i] * (1.0f - w) + cc2[i] * w
        }
        val invCc = invertMat3(ccInterp)

        // 3. M_cam_to_XYZ_D50 = FM(T) * CC(T)^-1
        val mCamToXyzD50 = mulMat3(fmInterp, invCc)

        // 4. M_cam_to_XYZ_D65 = M_Bradford * M_cam_to_XYZ_D50
        val mCamToXyzD65 = mulMat3(M_BRADFORD_D50_TO_D65, mCamToXyzD50)

        // 5. M_cam_to_Rec2020 = M_XYZ_to_BT2020 * M_cam_to_XYZ_D65
        val mCamToRec2020 = mulMat3(M_XYZ_TO_BT2020, mCamToXyzD65)

        // 6. Scale by exposure gain (maps sensor clip 1.0 to xmax in linear working space)
        val mExposed = FloatArray(9)
        for (i in 0 until 9) {
            mExposed[i] = mCamToRec2020[i] * exposureGain
        }

        // 7. Convert Row-Major to Column-Major for GLSL
        return rowMajorToColumnMajor(mExposed)
    }

    fun computeCompositeColorMatrix(gR: Float, gG: Float, gB: Float): FloatArray {
        val m = M_SENSOR_TO_BT2020.clone()
        for (i in m.indices) m[i] *= LOG_XMAX
        return m
    }

    fun computeCompositeColorMatrix(gains: ChannelGains): FloatArray {
        val m = M_SENSOR_TO_BT2020.clone()
        for (i in m.indices) m[i] *= LOG_XMAX
        return m
    }

    fun computeCompositeColorMatrix(gains: RggbChannelVector): FloatArray {
        val m = M_SENSOR_TO_BT2020.clone()
        for (i in m.indices) m[i] *= LOG_XMAX
        return m
    }
}
