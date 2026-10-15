package com.pixellog.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class ContinuousAutoExposureTest {

    private val masterIsoPresets = intArrayOf(
        50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400
    )

    // Shutter speeds sorted descending (same as CameraActivity after updateActiveShutterSpeeds)
    private val masterShutterSpeeds = intArrayOf(
        8000, 4000, 2000, 1000, 500, 480, 360, 240, 180, 120, 100, 96, 60, 50, 48, 30, 25, 24
    )

    private val wbValues = intArrayOf(
        2000, 2500, 2800, 3200, 3500, 4000, 4500, 5000, 5600, 6000, 6500, 7000, 7500, 8000, 10000
    )

    private fun findContinuousIsoIndex(iso: Int): Float {
        if (masterIsoPresets.isEmpty()) return 0f
        if (iso <= masterIsoPresets.first()) return 0f
        if (iso >= masterIsoPresets.last()) return (masterIsoPresets.size - 1).toFloat()
        for (i in 0 until masterIsoPresets.size - 1) {
            val v0 = masterIsoPresets[i]
            val v1 = masterIsoPresets[i + 1]
            if (iso in v0..v1) {
                if (v1 == v0) return i.toFloat()
                val frac = (ln(iso.toDouble() / v0) / ln(v1.toDouble() / v0)).toFloat()
                return i + frac.coerceIn(0f, 1f)
            }
        }
        return 0f
    }

    private fun findContinuousShutterIndex(shutterNs: Long): Float {
        if (masterShutterSpeeds.isEmpty() || shutterNs <= 0L) return 0f
        val tLive = shutterNs.toDouble() / 1_000_000_000.0
        val tFirst = 1.0 / masterShutterSpeeds.first()
        val tLast = 1.0 / masterShutterSpeeds.last()
        if (tLive <= tFirst) return 0f
        if (tLive >= tLast) return (masterShutterSpeeds.size - 1).toFloat()
        for (i in 0 until masterShutterSpeeds.size - 1) {
            val t0 = 1.0 / masterShutterSpeeds[i]
            val t1 = 1.0 / masterShutterSpeeds[i + 1]
            if (tLive in t0..t1) {
                if (t1 == t0) return i.toFloat()
                val frac = (ln(tLive / t0) / ln(t1 / t0)).toFloat()
                return i + frac.coerceIn(0f, 1f)
            }
        }
        return 0f
    }

    private fun findContinuousWbIndex(kelvin: Int): Float {
        if (wbValues.isEmpty()) return 0f
        if (kelvin <= wbValues.first()) return 0f
        if (kelvin >= wbValues.last()) return (wbValues.size - 1).toFloat()
        for (i in 0 until wbValues.size - 1) {
            val k0 = wbValues[i]
            val k1 = wbValues[i + 1]
            if (kelvin in k0..k1) {
                if (k1 == k0) return i.toFloat()
                val frac = (kelvin - k0).toFloat() / (k1 - k0).toFloat()
                return i + frac.coerceIn(0f, 1f)
            }
        }
        return 0f
    }

    @Test
    fun testExactPresetMatchesProduceIntegerIndices() {
        // Exact preset matches must land exactly on integer tick indices
        for (i in masterIsoPresets.indices) {
            val index = findContinuousIsoIndex(masterIsoPresets[i])
            assertEquals("ISO ${masterIsoPresets[i]} must match index $i", i.toFloat(), index, 0.001f)
        }

        for (i in masterShutterSpeeds.indices) {
            val ns = (1_000_000_000.0 / masterShutterSpeeds[i]).toLong()
            val index = findContinuousShutterIndex(ns)
            assertEquals("Shutter 1/${masterShutterSpeeds[i]} must match index $i", i.toFloat(), index, 0.02f)
        }

        for (i in wbValues.indices) {
            val index = findContinuousWbIndex(wbValues[i])
            assertEquals("WB ${wbValues[i]}K must match index $i", i.toFloat(), index, 0.001f)
        }
    }

    @Test
    fun testInBetweenValuesProduceSmoothFractionalIndicesWithoutSnapping() {
        // Between ISO 100 (index 3) and ISO 125 (index 4):
        val idx100 = findContinuousIsoIndex(100)
        val idx112 = findContinuousIsoIndex(112)
        val idx125 = findContinuousIsoIndex(125)

        assertEquals(3.0f, idx100, 0.001f)
        assertTrue("ISO 112 must be between 3.0 and 4.0", idx112 > 3.0f && idx112 < 4.0f)
        assertEquals(4.0f, idx125, 0.001f)

        // Monotonic progression across multiple in-between ISO values
        val isoValues = listOf(100, 105, 110, 115, 120, 125)
        var prevIdx = 0f
        for (iso in isoValues) {
            val idx = findContinuousIsoIndex(iso)
            assertTrue("Index for $iso ($idx) should be > prev ($prevIdx)", idx >= prevIdx)
            prevIdx = idx
        }

        // Between 1/60s (index 12) and 1/50s (index 13):
        val ns60 = (1_000_000_000.0 / 60).toLong()
        val ns55 = (1_000_000_000.0 / 55).toLong()
        val ns50 = (1_000_000_000.0 / 50).toLong()

        val idx60 = findContinuousShutterIndex(ns60)
        val idx55 = findContinuousShutterIndex(ns55)
        val idx50 = findContinuousShutterIndex(ns50)

        assertTrue("1/55s must be between 1/60s and 1/50s", idx55 > idx60 && idx55 < idx50)
    }

    @Test
    fun testClosedLoopConvergenceIsSmoothWithoutDeadbandStalls() {
        // Simulate closed-loop semi-auto convergence with a 1.0 EV exposure error
        var autoComputedIsoFloat = 100.0
        var autoComputedIso = 100
        var sceneEv = -1.0f // Scene is 1 EV underexposed
        val targetEv = 0.0f
        var isSettled = false

        val recordedIsoSteps = mutableListOf<Int>()
        recordedIsoSteps.add(autoComputedIso)

        // Iterate through simulated frames (converging every 2 frames)
        for (frame in 1..20) {
            if (frame % 2 == 0 && !isSettled) {
                val evError = targetEv - sceneEv
                val errorMagnitude = abs(evError)

                if (errorMagnitude <= 0.02f) {
                    isSettled = true
                } else {
                    val stepEv = (evError * 0.35f).coerceIn(-0.12f, 0.12f)
                    val factor = 2.0.pow(stepEv.toDouble())
                    autoComputedIsoFloat *= factor
                    autoComputedIso = autoComputedIsoFloat.roundToInt()
                    recordedIsoSteps.add(autoComputedIso)

                    // Update simulated sceneEv based on new exposure
                    sceneEv += stepEv
                }
            }
        }

        // Verify convergence smoothly approached ISO 200 without single-step jumps exceeding 0.12 EV (~8.7%)
        assertTrue("Should have converged close to ISO 200", autoComputedIso in 190..205)
        for (i in 1 until recordedIsoSteps.size) {
            val stepRatio = recordedIsoSteps[i].toDouble() / recordedIsoSteps[i - 1]
            assertTrue("Step ratio $stepRatio should be <= 1.09 (smooth, <0.12 EV per 2 frames)", stepRatio <= 1.10)
        }
    }
}
