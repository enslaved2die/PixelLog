package com.pixellog

import com.pixellog.camera.ColorScienceUtils
import org.junit.Assert.*
import org.junit.Test

class ColorScienceTest {

    @Test
    fun testKelvinTintToCieXy_D65() {
        // D65 neutral target is approx x=0.3127, y=0.3290
        val xy = ColorScienceUtils.kelvinTintToCieXy(6500, 0)
        assertEquals(0.313f, xy[0], 0.015f)
        assertEquals(0.329f, xy[1], 0.015f)
    }

    @Test
    fun testKelvinTintToCieXy_Tungsten() {
        // 3200K tungsten target is warm: x > 0.40, y > 0.38
        val xy = ColorScienceUtils.kelvinTintToCieXy(3200, 0)
        assertTrue("Expected warm x > 0.40, got ${xy[0]}", xy[0] > 0.40f)
        assertTrue("Expected warm y > 0.38, got ${xy[1]}", xy[1] > 0.38f)
    }

    @Test
    fun testCalculateColorGains_PositiveAndReasonable() {
        val gains = ColorScienceUtils.calculateColorGains(5600, 0)
        assertTrue("Red gain should be > 0.5, got ${gains.red}", gains.red >= 0.5f)
        assertEquals(1.0f, gains.greenEven, 1e-4f)
        assertEquals(1.0f, gains.greenOdd, 1e-4f)
        assertTrue("Blue gain should be > 0.5, got ${gains.blue}", gains.blue >= 0.5f)
    }

    @Test
    fun testComputeCompositeColorMatrix_DimensionsAndDeterminant() {
        val gains = ColorScienceUtils.calculateColorGains(5600, 0)
        val matrix = ColorScienceUtils.computeCompositeColorMatrix(gains)
        assertEquals(9, matrix.size)

        // Diagonal elements should be dominant positive
        assertTrue("m00 should be > 0", matrix[0] > 0f)
        assertTrue("m11 should be > 0", matrix[4] > 0f)
        assertTrue("m22 should be > 0", matrix[8] > 0f)
    }

    @Test
    fun testComputeModelNeutralPoint_TungstenVsDaylight() {
        val neutralTungsten = ColorScienceUtils.computeModelNeutralPoint(3200)
        assertEquals(3, neutralTungsten.size)
        // At 3200K tungsten, incoming light is red-heavy: sensor neutral red > 1.2, blue < daylight blue
        assertTrue("Tungsten nR should be > 1.2, got ${neutralTungsten[0]}", neutralTungsten[0] > 1.2f)
        assertEquals(1.0f, neutralTungsten[1], 1e-4f)
        assertTrue("Tungsten nB should be < 0.85, got ${neutralTungsten[2]}", neutralTungsten[2] < 0.85f)

        val neutralDaylight = ColorScienceUtils.computeModelNeutralPoint(6500)
        assertEquals(1.0f, neutralDaylight[1], 1e-4f)
        assertTrue("Daylight nR should be around 1.0, got ${neutralDaylight[0]}", neutralDaylight[0] in 0.8f..1.2f)
        assertTrue("Daylight nB should be around 1.0, got ${neutralDaylight[2]}", neutralDaylight[2] in 0.8f..1.3f)
        assertTrue("Tungsten should have higher red than daylight", neutralTungsten[0] > neutralDaylight[0])
        assertTrue("Tungsten should have lower blue than daylight", neutralTungsten[2] < neutralDaylight[2])
    }

    @Test
    fun testCalculateNeutralColorPoint_AutoTintRetention() {
        val liveNeutral = floatArrayOf(1.48f, 1.0f, 0.44f)
        val liveKelvin = 3000

        // Zero-jump verification: setting manual WB to current live Kelvin reproduces live neutral point exactly
        val manualNeutralSame = ColorScienceUtils.calculateNeutralColorPoint(
            kelvin = 3000,
            liveNeutral = liveNeutral,
            liveKelvin = liveKelvin
        )
        assertEquals(liveNeutral[0], manualNeutralSame[0], 0.01f)
        assertEquals(1.0f, manualNeutralSame[1], 1e-4f)
        assertEquals(liveNeutral[2], manualNeutralSame[2], 0.01f)

        // Shifting warmer (2500K) increases nR
        val manualWarmer = ColorScienceUtils.calculateNeutralColorPoint(
            kelvin = 2500,
            liveNeutral = liveNeutral,
            liveKelvin = liveKelvin
        )
        assertTrue(manualWarmer[0] > liveNeutral[0])

        // Shifting cooler (5600K) increases nB and decreases nR
        val manualCooler = ColorScienceUtils.calculateNeutralColorPoint(
            kelvin = 5600,
            liveNeutral = liveNeutral,
            liveKelvin = liveKelvin
        )
        assertTrue(manualCooler[2] > liveNeutral[2])
        assertTrue(manualCooler[0] < liveNeutral[0])
    }

    @Test
    fun testEstimateTemperatureFromNeutral_McCamy() {
        val tungstenNeutral = floatArrayOf(1.50f, 1.0f, 0.42f)
        val tungstenTemp = ColorScienceUtils.estimateTemperatureFromNeutral(tungstenNeutral)
        assertTrue("Indoor tungsten should estimate between 2200K and 3600K, got $tungstenTemp", tungstenTemp in 2200f..3600f)

        val daylightNeutral = floatArrayOf(0.98f, 1.0f, 1.05f)
        val daylightTemp = ColorScienceUtils.estimateTemperatureFromNeutral(daylightNeutral)
        assertTrue("D65 daylight should estimate between 5000K and 7500K, got $daylightTemp", daylightTemp in 5000f..7500f)
    }

    @Test
    fun testPerSensorDefaultCalibrations() {
        val calibWide = ColorScienceUtils.getDefaultCalibration("2")
        assertEquals(4095.0f, calibWide.whiteLevel, 1e-4f)
        assertEquals(256.0f, calibWide.dynamicBlackLevel[0], 1e-4f)
        assertEquals(2, calibWide.bayerPattern) // GBRG

        val calibUW = ColorScienceUtils.getDefaultCalibration("3")
        assertEquals(1023.0f, calibUW.whiteLevel, 1e-4f)
        assertEquals(64.0f, calibUW.dynamicBlackLevel[0], 1e-4f)
        assertEquals(0, calibUW.bayerPattern) // RGGB

        val calibTele = ColorScienceUtils.getDefaultCalibration("4")
        assertEquals(1023.0f, calibTele.whiteLevel, 1e-4f)
        assertEquals(64.0f, calibTele.dynamicBlackLevel[0], 1e-4f)
        assertEquals(3, calibTele.bayerPattern) // BGGR
    }

    @Test
    fun testSensorMiddleGreyNormalization() {
        // 18% middle grey card linear normalization on 12-bit Wide sensor
        val rawWide = 947.0f
        val blWide = 256.0f
        val wlWide = 4095.0f
        val xWide = (rawWide - blWide) / (wlWide - blWide)
        assertEquals(0.180f, xWide, 0.005f)

        // 18% middle grey card linear normalization on 10-bit Ultrawide / Telephoto sensor
        val raw10Bit = 237.0f
        val bl10Bit = 64.0f
        val wl10Bit = 1023.0f
        val x10Bit = (raw10Bit - bl10Bit) / (wl10Bit - bl10Bit)
        assertEquals(0.180f, x10Bit, 0.005f)

        // Verify that with incorrect 256 bl on 10-bit sensor, middle grey is negative (crushed)
        val xCrushed = (raw10Bit - 256.0f) / (wl10Bit - 256.0f)
        assertTrue("Expected negative (crushed) x with wrong black level, got $xCrushed", xCrushed < 0.0f)
    }

    @Test
    fun testCompositeMatrixScalingByLogXmax() {
        val matrix = ColorScienceUtils.computeCompositeColorMatrix(
            neutralPoint = floatArrayOf(1.0f, 1.0f, 1.0f),
            calibration = ColorScienceUtils.DEFAULT_CALIBRATION_WIDE
        )
        assertEquals(9, matrix.size)
        // With LOG_XMAX ~ 8.146, the diagonal elements should be scaled by ~8.146
        assertTrue("Scaled matrix element m00 should be > 5.0, got ${matrix[0]}", matrix[0] > 5.0f)
        assertTrue("Scaled matrix element m11 should be > 5.0, got ${matrix[4]}", matrix[4] > 5.0f)
        assertTrue("Scaled matrix element m22 should be > 5.0, got ${matrix[8]}", matrix[8] > 5.0f)
    }
}

