package com.pixellog.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.pixellog.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * High-precision Exposure Value (EV) Visualizer Meter.
 *
 * Implements the Penpot cinema HUD design element:
 * - Dimensions: 303 x 127 (scaled proportionally to ~101dp x 43dp).
 * - Translucent background pill (15% white, corner radius = 11.5dp).
 * - Scale spanning -2 EV to +2 EV with 60px/EV major ticks and 20px (1/3 EV) minor ticks.
 * - Major tick labels "-2", "-1", "0", "+1", "+2" rendered in Gabarito font.
 * - Dynamic downward-pointing amber triangle (#E1884B) indicating the current exposure level.
 * - Non-interactable visualizer with smooth 60/120 fps damping interpolation.
 */
class EvMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        // Penpot design canvas reference dimensions
        const val DESIGN_WIDTH = 303f
        const val DESIGN_HEIGHT = 127f

        // Pill specifications
        const val PILL_TOP = 29f
        const val PILL_HEIGHT = 69f
        const val PILL_CENTER_Y = PILL_TOP + (PILL_HEIGHT / 2f) // 63.5f

        // Scale geometry (0 EV centered at 151px)
        const val CENTER_0_EV_X = 151f
        const val STEP_PER_EV = 60f
        const val MAJOR_TICK_HEIGHT = 36.86f
        const val MINOR_TICK_HEIGHT = 18f
        const val TICK_STROKE_WIDTH = 4f

        // Triangle pointer
        const val TRIANGLE_HEIGHT = 25f
        const val TRIANGLE_HALF_WIDTH = 16f

        // Text labels
        const val TEXT_BOX_CENTER_Y = 117f
        const val TEXT_FONT_SIZE = 26f

        // Colors
        const val COLOR_PILL_BG = 0x26FFFFFF // #FFFFFF 15% opacity
        const val COLOR_MAJOR_TICK = 0xFFFFFFFF.toInt()
        const val COLOR_MINOR_TICK = 0x80FFFFFF.toInt() // #FFFFFF 50% opacity
        const val COLOR_LABEL = 0xFFFFFFFF.toInt()
        const val COLOR_TRIANGLE = 0xFFE1884B.toInt() // Cinema warm amber
    }

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_PILL_BG
    }

    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = COLOR_MAJOR_TICK
    }

    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = COLOR_MINOR_TICK
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_LABEL
        textAlign = Paint.Align.CENTER
    }

    private val trianglePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_TRIANGLE
    }

    private val pillRect = RectF()
    private val trianglePath = Path()

    // Minor tick EV offsets (1/3 EV increments between -2 and +2)
    private val minorEvSteps = floatArrayOf(
        -5f / 3f, -4f / 3f,
        -2f / 3f, -1f / 3f,
         1f / 3f,  2f / 3f,
         4f / 3f,  5f / 3f
    )

    // Major tick labels
    private val majorTicks = arrayOf(
        -2 to "-2",
        -1 to "-1",
         0 to "0",
         1 to "+1",
         2 to "+2"
    )

    /** Current visual exposure level (animated smoothly) */
    var currentExposureLevel: Float = 0f
        private set

    /** Target exposure level (clamped between -2.0f and +2.0f) */
    var targetExposureLevel: Float = 0f
        private set

    init {
        // Non-interactable visualizer
        isClickable = false
        isFocusable = false

        // Load custom Gabarito typeface
        val gabaritoFont = try {
            ResourcesCompat.getFont(context, R.font.gabarito)
        } catch (_: Throwable) {
            Typeface.DEFAULT
        }
        textPaint.typeface = gabaritoFont ?: Typeface.DEFAULT
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        // Non-interactable visualizer: touch events pass through to parent
        return false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val defaultWidth = (101f * density).roundToInt()
        val defaultHeight = (43f * density).roundToInt()

        val resolvedWidth = resolveSize(defaultWidth, widthMeasureSpec)
        val resolvedHeight = resolveSize(defaultHeight, heightMeasureSpec)
        setMeasuredDimension(resolvedWidth, resolvedHeight)
    }

    /**
     * Updates the target exposure level.
     * @param level Exposure Value (EV), e.g. -2.0 to +2.0.
     * @param animate If true, smoothly animates the triangle needle to the new position.
     */
    fun setExposureLevel(level: Float, animate: Boolean = true) {
        val clamped = level.coerceIn(-2.0f, 2.0f)
        if (clamped == targetExposureLevel && !animate) return

        targetExposureLevel = clamped
        if (!animate) {
            currentExposureLevel = targetExposureLevel
            invalidate()
        } else {
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Scale factor relative to 303px Penpot design width
        val scale = w / DESIGN_WIDTH

        // Smooth damping animation for needle movement
        val diff = targetExposureLevel - currentExposureLevel
        if (abs(diff) > 0.002f) {
            currentExposureLevel += diff * 0.25f
            postInvalidateOnAnimation()
        } else {
            currentExposureLevel = targetExposureLevel
        }

        // Configure paint stroke widths and text sizes
        val tickStroke = TICK_STROKE_WIDTH * scale
        majorTickPaint.strokeWidth = tickStroke
        minorTickPaint.strokeWidth = tickStroke
        textPaint.textSize = TEXT_FONT_SIZE * scale

        // 1. Draw Translucent Background Pill
        val pillTop = PILL_TOP * scale
        val pillBottom = (PILL_TOP + PILL_HEIGHT) * scale
        val pillCornerRadius = (PILL_HEIGHT / 2f) * scale
        pillRect.set(0f, pillTop, w, pillBottom)
        canvas.drawRoundRect(pillRect, pillCornerRadius, pillCornerRadius, pillPaint)

        // 2. Draw Ticks (Centered vertically in the pill at PILL_CENTER_Y)
        val pillCenterY = PILL_CENTER_Y * scale
        val majorHalfH = (MAJOR_TICK_HEIGHT / 2f) * scale
        val minorHalfH = (MINOR_TICK_HEIGHT / 2f) * scale

        // Draw Minor Ticks (1/3 EV increments)
        val minorTopY = pillCenterY - minorHalfH
        val minorBottomY = pillCenterY + minorHalfH
        for (step in minorEvSteps) {
            val tickX = (CENTER_0_EV_X + (step * STEP_PER_EV)) * scale
            canvas.drawLine(tickX, minorTopY, tickX, minorBottomY, minorTickPaint)
        }

        // Draw Major Ticks (-2, -1, 0, +1, +2)
        val majorTopY = pillCenterY - majorHalfH
        val majorBottomY = pillCenterY + majorHalfH
        for ((ev, _) in majorTicks) {
            val tickX = (CENTER_0_EV_X + (ev * STEP_PER_EV)) * scale
            canvas.drawLine(tickX, majorTopY, tickX, majorBottomY, majorTickPaint)
        }

        // 3. Draw Major Tick Text Labels
        val textCenterY = TEXT_BOX_CENTER_Y * scale
        val fontMetrics = textPaint.fontMetrics
        val textBaselineY = textCenterY - ((fontMetrics.ascent + fontMetrics.descent) / 2f)
        for ((ev, label) in majorTicks) {
            val labelX = (CENTER_0_EV_X + (ev * STEP_PER_EV)) * scale
            canvas.drawText(label, labelX, textBaselineY, textPaint)
        }

        // 4. Draw Moving Amber Triangle Indicator
        val clampedEv = currentExposureLevel.coerceIn(-2.0f, 2.0f)
        val triangleCenterX = (CENTER_0_EV_X + (clampedEv * STEP_PER_EV)) * scale
        val triangleHalfW = TRIANGLE_HALF_WIDTH * scale
        val triangleTipY = TRIANGLE_HEIGHT * scale

        trianglePath.reset()
        trianglePath.moveTo(triangleCenterX - triangleHalfW, 0f)
        trianglePath.lineTo(triangleCenterX + triangleHalfW, 0f)
        trianglePath.lineTo(triangleCenterX, triangleTipY)
        trianglePath.close()

        canvas.drawPath(trianglePath, trianglePaint)
    }
}
