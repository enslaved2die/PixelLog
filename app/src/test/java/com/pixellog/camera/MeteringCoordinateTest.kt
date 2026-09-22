package com.pixellog.camera

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Pure JVM unit tests for the sensor coordinate mapping logic used in
 * [CameraController.mapNormalizedToSensorCoords].
 *
 * Android SDK types (Rect, MeteringRectangle) are **not** used here because
 * their constructors/fields return stub defaults under `isReturnDefaultValues`
 * on the host JVM.  Instead a plain [SensorRect] data class mirrors the four
 * fields accessed from [android.graphics.Rect] in production code.
 *
 * Pixel 11 Pro open-gate sensor reference values (RGGB):
 *   Full active array : 4080 × 3064
 *   EIS crop (85 %)  : 3468 × 2600  (centred, even-aligned)
 */
class MeteringCoordinateTest {

    // ── Android-free geometry type ────────────────────────────────────────────

    /** Mirrors the four fields of [android.graphics.Rect] used in production. */
    data class SensorRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun width()   = right  - left
        fun height()  = bottom - top
        fun centerX() = (left  + right)  / 2
        fun centerY() = (top   + bottom) / 2
    }

    // ── Pure coordinate computation (mirrors CameraController logic) ──────────

    /**
     * Returns the bounds-clamped sensor [SensorRect] for normalised viewfinder
     * coordinates [u, v] ∈ [0, 1]² mapped inside [crop], constrained to
     * [activeArray].
     *
     * This is a verbatim port of [CameraController.mapNormalizedToSensorCoords];
     * any change to the production algorithm must be reflected here.
     */
    private fun computeMeteringRect(
        u: Float,
        v: Float,
        activeArray: SensorRect,
        crop: SensorRect = activeArray,
        regionFraction: Float = 0.12f
    ): SensorRect {
        val clampedU = u.coerceIn(0f, 1f)
        val clampedV = v.coerceIn(0f, 1f)

        val centerX = (crop.left + clampedU * crop.width()).toInt()
        val centerY = (crop.top  + clampedV * crop.height()).toInt()

        val regionW = (crop.width()  * regionFraction).toInt().coerceAtLeast(100)
        val regionH = (crop.height() * regionFraction).toInt().coerceAtLeast(100)

        val halfW = regionW / 2
        val halfH = regionH / 2

        val left   = (centerX - halfW).coerceIn(activeArray.left,  activeArray.right  - 1)
        val top    = (centerY - halfH).coerceIn(activeArray.top,   activeArray.bottom - 1)
        val right  = (centerX + halfW).coerceIn(left + 1, activeArray.right)
        val bottom = (centerY + halfH).coerceIn(top  + 1, activeArray.bottom)

        return SensorRect(left, top, right, bottom)
    }

    // ── Reference geometry ────────────────────────────────────────────────────

    /** Full 4:3 open-gate active array for Pixel 11 Pro. */
    private val fullArray = SensorRect(0, 0, 4080, 3064)

    /**
     * 85 % EIS crop, even-aligned, centred on the full array.
     *   Left offset = (4080 - 3468) / 2 = 306  (already even).
     *   Top  offset = (3064 - 2600) / 2 = 232  (already even).
     */
    private val eisCrop = run {
        val ox = ((fullArray.width()  - 3468) / 2) and 1.inv()
        val oy = ((fullArray.height() - 2600) / 2) and 1.inv()
        SensorRect(ox, oy, ox + 3468, oy + 2600)
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /** A centre tap must map to a region centred on the array midpoint. */
    @Test
    fun testCenter_fullArray() {
        val r = computeMeteringRect(0.5f, 0.5f, fullArray)

        assertApprox("Center X", fullArray.centerX(), r.centerX(), delta = 2)
        assertApprox("Center Y", fullArray.centerY(), r.centerY(), delta = 2)
    }

    /** A top-left corner tap must produce a valid region inside the active array. */
    @Test
    fun testTopLeft_fullArray() {
        val r = computeMeteringRect(0f, 0f, fullArray)

        assertTrue("left  >= array.left",   r.left   >= fullArray.left)
        assertTrue("top   >= array.top",    r.top    >= fullArray.top)
        assertTrue("right  > left",          r.right  >  r.left)
        assertTrue("bottom > top",           r.bottom >  r.top)
        assertTrue("right  <= array.right",  r.right  <= fullArray.right)
        assertTrue("bottom <= array.bottom", r.bottom <= fullArray.bottom)
    }

    /** A bottom-right corner tap must stay inside the active array. */
    @Test
    fun testBottomRight_fullArray() {
        val r = computeMeteringRect(1f, 1f, fullArray)

        assertTrue("right  <= array.right",  r.right  <= fullArray.right)
        assertTrue("bottom <= array.bottom", r.bottom <= fullArray.bottom)
        assertTrue("right  > left",          r.right  >  r.left)
        assertTrue("bottom > top",           r.bottom >  r.top)
    }

    /**
     * EIS-cropped mapping: the HAL expects coordinates in full active-array
     * space, so the result must lie within [fullArray] even when [eisCrop] is
     * the crop region.  The region centre must also align with the EIS centre.
     */
    @Test
    fun testCenter_eisCrop() {
        val r = computeMeteringRect(0.5f, 0.5f, activeArray = fullArray, crop = eisCrop)

        // Must be inside the full active array
        assertTrue("left   >= 0",    r.left   >= 0)
        assertTrue("top    >= 0",    r.top    >= 0)
        assertTrue("right  <= 4080", r.right  <= 4080)
        assertTrue("bottom <= 3064", r.bottom <= 3064)

        // Region centre must align with the EIS crop centre
        assertApprox("Center X (EIS)", eisCrop.centerX(), r.centerX(), delta = 4)
        assertApprox("Center Y (EIS)", eisCrop.centerY(), r.centerY(), delta = 4)
    }

    /**
     * With a tiny array and a very small fraction the algorithm must still
     * produce at least a 1 × 1 pixel region due to the coerceAtLeast(100)
     * floor followed by the right/bottom coerceIn guards.
     */
    @Test
    fun testMinimumRegionDimensions() {
        val tinyArray = SensorRect(0, 0, 200, 200)
        val r = computeMeteringRect(0.5f, 0.5f, tinyArray, regionFraction = 0.01f)

        assertTrue("width  >= 1", r.width()  >= 1)
        assertTrue("height >= 1", r.height() >= 1)
    }

    /** Out-of-range UV inputs must be clamped, never throw. */
    @Test
    fun testOutOfRange_clampedSafely() {
        val r1 = computeMeteringRect(-0.5f, -0.5f, fullArray)
        val r2 = computeMeteringRect( 1.5f,  1.5f, fullArray)

        for (r in listOf(r1, r2)) {
            assertTrue(r.left   >= fullArray.left)
            assertTrue(r.top    >= fullArray.top)
            assertTrue(r.right  <= fullArray.right)
            assertTrue(r.bottom <= fullArray.bottom)
            assertTrue("right  > left",  r.right  > r.left)
            assertTrue("bottom > top",   r.bottom > r.top)
        }
    }

    /**
     * Region dimensions should equal 12 % of the crop width/height (with 100 px
     * minimum).  At 4080 × 3064: expected 489 × 367 px (before edge clamping).
     */
    @Test
    fun testRegionSize_matchesFraction() {
        val r = computeMeteringRect(0.5f, 0.5f, fullArray, regionFraction = 0.12f)

        val expectedW = (fullArray.width()  * 0.12f).toInt().coerceAtLeast(100)
        val expectedH = (fullArray.height() * 0.12f).toInt().coerceAtLeast(100)

        // Allow ±2 px for integer rounding
        assertApprox("Region width",  expectedW, r.width(),  delta = 2)
        assertApprox("Region height", expectedH, r.height(), delta = 2)
    }

    /**
     * A tap at (0.25, 0.75) → region centre must land in the lower-left
     * quadrant of the full active array.
     */
    @Test
    fun testQuadrant_lowerLeft() {
        val r = computeMeteringRect(0.25f, 0.75f, fullArray)

        assertApprox("Quarter-x",       (fullArray.width()  * 0.25f).toInt(), r.centerX(), delta = 4)
        assertApprox("Three-quarter-y", (fullArray.height() * 0.75f).toInt(), r.centerY(), delta = 4)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun assertApprox(msg: String, expected: Int, actual: Int, delta: Int) {
        assertTrue(
            "$msg: expected $expected +/- $delta, got $actual",
            abs(actual - expected) <= delta
        )
    }
}
