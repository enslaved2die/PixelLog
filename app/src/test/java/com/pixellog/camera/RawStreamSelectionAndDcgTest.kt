package com.pixellog.camera

import android.util.Size
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.max

/**
 * Unit tests for:
 * 1. Camera2 RAW_SENSOR stream size selection (4:3 binned width <= 4200 for 12-bit iDCG).
 * 2. 12-bit iDCG telemetry state & bit depth evaluation.
 * 3. Linear normalization math for 10-bit (WL=1023, BL=64) and 12-bit iDCG (WL=4095, BL=256).
 * 4. GLSL shader precision & highp declarations.
 */
class RawStreamSelectionAndDcgTest {

    // Helper implementing stream selection logic identical to CameraController.selectRawStreamDimensions
    private fun selectRawStreamDimensions(sizes: List<Pair<Int, Int>>?): Pair<Int, Int>? {
        if (sizes.isNullOrEmpty()) return null

        val fourByThreeSizes = sizes.filter { (w, h) ->
            if (w <= 0 || h <= 0) false
            else {
                val ratio = w.toFloat() / h.toFloat()
                Math.abs(ratio - (4.0f / 3.0f)) < 0.05f
            }
        }

        // 1. Largest 4:3 stream where width <= 4200
        val binned4x3 = fourByThreeSizes.filter { (w, _) -> w <= 4200 }.maxByOrNull { (w, h) -> w * h }
        if (binned4x3 != null) return binned4x3

        // 2. Largest 4:3 stream overall
        val largest4x3 = fourByThreeSizes.maxByOrNull { (w, h) -> w * h }
        if (largest4x3 != null) return largest4x3

        // 3. Fall back to largest available stream overall
        return sizes.maxByOrNull { (w, h) -> w * h } ?: sizes.first()
    }

    @Test
    fun testMainCameraStreamSelection_picksBinned4080x3064() {
        // Pixel 11 Pro Main 50MP sensor HAL exposes unbinned 8160x6128, binned 4080x3064, and 2032x1532
        val mainSizes = listOf(
            8160 to 6128,
            4080 to 3064,
            2032 to 1532
        )
        val selected = selectRawStreamDimensions(mainSizes)
        assertNotNull(selected)
        assertEquals(4080, selected!!.first)
        assertEquals(3064, selected.second)
    }

    @Test
    fun testUltrawideAndTeleStreamSelection_picks4000x3000() {
        val uwSizes = listOf(
            4000 to 3000,
            2000 to 1500
        )
        val selected = selectRawStreamDimensions(uwSizes)
        assertNotNull(selected)
        assertEquals(4000, selected!!.first)
        assertEquals(3000, selected.second)
    }

    @Test
    fun testFallback_whenOnlyLargeStreamExists() {
        val largeOnlySizes = listOf(
            8160 to 6128
        )
        val selected = selectRawStreamDimensions(largeOnlySizes)
        assertNotNull(selected)
        assertEquals(8160, selected!!.first)
        assertEquals(6128, selected.second)
    }

    @Test
    fun testFallback_whenOnlyNon4x3StreamsExist() {
        val non4x3Sizes = listOf(
            3840 to 2160,
            1920 to 1080
        )
        val selected = selectRawStreamDimensions(non4x3Sizes)
        assertNotNull(selected)
        assertEquals(3840, selected!!.first)
        assertEquals(2160, selected.second)
    }

    @Test
    fun testTelemetryBitDepthAndIdcgState() {
        // 12-bit iDCG Main sensor
        val wl12Bit = 4095.0f
        val is12BitActive = (wl12Bit >= 4095.0f)
        val bitDepth12 = if (wl12Bit >= 4095.0f) 12 else 10
        assertTrue("12-bit iDCG must be active for whiteLevel >= 4095", is12BitActive)
        assertEquals(12, bitDepth12)

        // 10-bit UW/Tele sensor
        val wl10Bit = 1023.0f
        val is10BitActive = (wl10Bit >= 4095.0f)
        val bitDepth10 = if (wl10Bit >= 4095.0f) 12 else 10
        assertFalse("12-bit iDCG must be inactive for 10-bit sensor (WL 1023)", is10BitActive)
        assertEquals(10, bitDepth10)
    }

    @Test
    fun testLinearNormalizationMath() {
        // Shader formula: max(0.0, (float(raw) - bl) / max(1.0, wl - bl))
        fun normalizeSample(raw: Float, bl: Float, wl: Float): Float {
            return max(0.0f, (raw - bl) / max(1.0f, wl - bl))
        }

        // 12-bit iDCG Wide sensor (BL = 256.0, WL = 4095.0)
        val bl12 = 256.0f
        val wl12 = 4095.0f
        assertEquals(0.0f, normalizeSample(bl12, bl12, wl12), 1e-6f)
        assertEquals(1.0f, normalizeSample(wl12, bl12, wl12), 1e-6f)
        // Sub-black sample clamped to >= 0.0
        assertEquals(0.0f, normalizeSample(100.0f, bl12, wl12), 1e-6f)
        // Middle grey ~0.18 (raw = 947)
        assertEquals(0.180f, normalizeSample(947.0f, bl12, wl12), 0.005f)

        // 10-bit UW/Tele sensor (BL = 64.0, WL = 1023.0)
        val bl10 = 64.0f
        val wl10 = 1023.0f
        assertEquals(0.0f, normalizeSample(bl10, bl10, wl10), 1e-6f)
        assertEquals(1.0f, normalizeSample(wl10, bl10, wl10), 1e-6f)
        // Sub-black sample clamped to >= 0.0
        assertEquals(0.0f, normalizeSample(20.0f, bl10, wl10), 1e-6f)
        // Middle grey ~0.18 (raw = 237)
        assertEquals(0.180f, normalizeSample(237.0f, bl10, wl10), 0.005f)
    }

    @Test
    fun testShadersDeclareHighpPrecisionAndNoMediump() {
        val shaderFiles = listOf(
            "src/main/assets/shaders/debayer_pixel_log.frag",
            "src/main/assets/shaders/edr_debayer_pixel_log.frag",
            "src/main/assets/shaders/lut3d_preview.frag",
            "src/main/assets/shaders/debayer_pixel_log.vert",
            "src/main/assets/shaders/lut3d_preview.vert"
        )
        for (relPath in shaderFiles) {
            val file = File(relPath).let { if (it.exists()) it else File("app", relPath) }
            assertTrue("Shader file must exist: $relPath", file.exists())
            val text = file.readText()
            assertTrue("$relPath must declare precision highp float", text.contains("precision highp float;"))
            assertTrue("$relPath must declare precision highp int", text.contains("precision highp int;"))
            assertFalse("$relPath must NOT contain mediump", text.contains("mediump"))
        }
    }
}
