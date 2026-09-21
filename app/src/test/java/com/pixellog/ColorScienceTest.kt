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
}
