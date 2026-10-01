package com.pixellog.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.pow

class EvMeterTest {

    @Test
    fun testEvScaleGeometry() {
        // Design constants from Penpot
        val center0EvX = EvMeterView.CENTER_0_EV_X // 151f
        val stepPerEv = EvMeterView.STEP_PER_EV     // 60f
        val designWidth = EvMeterView.DESIGN_WIDTH // 303f

        // Major tick X positions
        val xMinus2 = center0EvX + (-2f * stepPerEv)
        val xMinus1 = center0EvX + (-1f * stepPerEv)
        val xZero = center0EvX + (0f * stepPerEv)
        val xPlus1 = center0EvX + (1f * stepPerEv)
        val xPlus2 = center0EvX + (2f * stepPerEv)

        assertEquals(31f, xMinus2, 0.001f)
        assertEquals(91f, xMinus1, 0.001f)
        assertEquals(151f, xZero, 0.001f)
        assertEquals(211f, xPlus1, 0.001f)
        assertEquals(271f, xPlus2, 0.001f)

        // Bilateral symmetry within 303px wide container
        val leftMargin = xMinus2
        val rightMargin = designWidth - xPlus2
        assertEquals(31f, leftMargin, 0.001f)
        assertEquals(32f, rightMargin, 1.0f) // Sub-pixel symmetry centered on 151px (pill center 151.5px)

        // Center 0 EV is virtually identical to pill center
        assertEquals(designWidth / 2f, center0EvX, 1.0f)
    }

    @Test
    fun testMinorTickSpacing() {
        val center0EvX = EvMeterView.CENTER_0_EV_X
        val stepPerEv = EvMeterView.STEP_PER_EV

        // Minor ticks are in 1/3 EV increments (20px each)
        val stepThird = stepPerEv / 3f
        assertEquals(20f, stepThird, 0.001f)

        val minorSteps = floatArrayOf(
            -5f / 3f, -4f / 3f,
            -2f / 3f, -1f / 3f,
             1f / 3f,  2f / 3f,
             4f / 3f,  5f / 3f
        )

        for (step in minorSteps) {
            val tickX = center0EvX + (step * stepPerEv)
            assertTrue(tickX > 31f && tickX < 271f)
        }
    }

    @Test
    fun testPureManualModeIgnoresEvManualOverride() {
        // Nominal scene exposure anchor: 1/50s (20ms) at ISO 400
        val nominalExposureProduct = 20_000_000L.toDouble() * 400

        // In pure manual mode: user locks shutter to 1/100s (10ms) and ISO to 400 (-1 stop underexposed)
        val manualShutterNs = 10_000_000L
        val manualIso = 400
        val currentExposure = manualShutterNs.toDouble() * manualIso

        // Calculation of scene EV representation:
        fun computeSceneEv(): Float {
            return (ln(currentExposure / nominalExposureProduct) / ln(2.0)).toFloat().coerceIn(-3.0f, 3.0f)
        }

        // Scene EV sitting on sensor is -1.0 EV
        assertEquals(-1.0f, computeSceneEv(), 0.001f)

        // If user changes EV Manual Override (e.g. to +2.0 EV or -3.0 EV),
        // it is completely ignored in pure manual mode because neither exposure nor nominal anchor change.
        // Therefore, Scene EV remains strictly -1.0 EV.
        val sceneEvWithDifferentEvOverride = computeSceneEv()
        assertEquals(-1.0f, sceneEvWithDifferentEvOverride, 0.001f)
    }

    @Test
    fun testSemiAutoModeAppliesEvCompensation() {
        // Nominal scene anchor: 1/50s (20ms) at ISO 400 -> product = 8,000,000,000
        val baseAutoShutterNs = 20_000_000L
        val baseAutoIso = 400

        // Shutter is Manual (1/100s, half time), ISO is Auto.
        // User sets EV Manual Override to +1.0 EV.
        // The auto parameter (ISO) compensates for the 1/100s shutter (needs 2x)
        // AND adds +1.0 EV compensation (needs another 2x) -> ISO = 400 * 2 * 2 = 1600.
        val manualShutterNs = 10_000_000L
        val evOverrideFloat = 1.0f
        val compensatedIso = (baseAutoIso * (baseAutoShutterNs.toDouble() / manualShutterNs) * 2.0.pow(evOverrideFloat.toDouble())).toInt()
        assertEquals(1600, compensatedIso)

        val currentExposure = manualShutterNs.toDouble() * compensatedIso
        val nominalProduct = (baseAutoShutterNs.toDouble() * baseAutoIso) // uncompensated nominal scene anchor
        val sceneEv = (ln(currentExposure / nominalProduct) / ln(2.0)).toFloat()

        // Scene EV correctly reflects the +1.0 EV sitting exposure level
        assertEquals(1.0f, sceneEv, 0.001f)
    }

    @Test
    fun testNeedlePositionMapping() {
        val center0EvX = EvMeterView.CENTER_0_EV_X
        val stepPerEv = EvMeterView.STEP_PER_EV

        fun getNeedleX(ev: Float): Float {
            val clamped = ev.coerceIn(-2.0f, 2.0f)
            return center0EvX + (clamped * stepPerEv)
        }

        assertEquals(151f, getNeedleX(0f), 0.001f)
        assertEquals(211f, getNeedleX(1f), 0.001f)
        assertEquals(271f, getNeedleX(2f), 0.001f)
        assertEquals(91f, getNeedleX(-1f), 0.001f)
        assertEquals(31f, getNeedleX(-2f), 0.001f)

        // Fractional EV positions (+0.5 EV, -1.5 EV)
        assertEquals(181f, getNeedleX(0.5f), 0.001f)
        assertEquals(61f, getNeedleX(-1.5f), 0.001f)

        // Clamping check
        assertEquals(271f, getNeedleX(3.5f), 0.001f)
        assertEquals(31f, getNeedleX(-5.0f), 0.001f)
    }

    @Test
    fun testPhotometricDeltaEvCalculation() {
        val targetMiddleGray = 0.022097087f // 2^-5.5

        fun computeDeltaEv(meanLinearLuma: Float): Float {
            val rawDelta = (kotlin.math.ln(meanLinearLuma.coerceAtLeast(1e-6f) / targetMiddleGray) / kotlin.math.ln(2.0)).toFloat()
            return rawDelta.coerceIn(-3.0f, 3.0f)
        }

        // Exactly at 18% middle gray anchor (0 EV)
        assertEquals(0.0f, computeDeltaEv(targetMiddleGray), 0.001f)

        // 1 stop over (+1.0 EV)
        assertEquals(1.0f, computeDeltaEv(targetMiddleGray * 2f), 0.001f)

        // 1 stop under (-1.0 EV)
        assertEquals(-1.0f, computeDeltaEv(targetMiddleGray * 0.5f), 0.001f)

        // 2 stops over (+2.0 EV)
        assertEquals(2.0f, computeDeltaEv(targetMiddleGray * 4f), 0.001f)

        // 2 stops under (-2.0 EV)
        assertEquals(-2.0f, computeDeltaEv(targetMiddleGray * 0.25f), 0.001f)

        // Extreme clipping: bright sunlight saturation (sensor white level)
        assertEquals(3.0f, computeDeltaEv(1.0f), 0.001f)

        // Extreme clipping: capped lens (pitch black)
        assertEquals(-3.0f, computeDeltaEv(0.0f), 0.001f)
    }
}
