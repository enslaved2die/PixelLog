package com.pixellog.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import com.pixellog.R
import kotlin.math.abs
import kotlin.math.roundToInt

class DialStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Orientation {
        HORIZONTAL,
        VERTICAL
    }

    var dialOrientation: Orientation = Orientation.HORIZONTAL
        set(value) {
            field = value
            updateGradients()
            requestLayout()
            invalidate()
        }

    // Configurable range
    var minValue: Int = 0
        private set
    var maxValue: Int = 100
        private set
    var steps: Int = 100
        private set

    /**
     * Optional threshold index for Dual Conversion Gain (DCG) sensor visualization.
     * When >= 0, ticks below this index are rendered in LCG styling (white/cyan),
     * and ticks at or above this index are rendered in HCG styling (amber/gold).
     */
    var dcgThresholdIndex: Int = -1
        set(value) {
            field = value
            lastDcgHcgState = null
            invalidate()
        }

    // Current state
    private var scrollOffset: Float = 0f
    private var isDragging: Boolean = false
    private var lastDcgHcgState: Boolean? = null

    val isUserInteracting: Boolean
        get() = isDragging || !scroller.isFinished

    // Appearance
    private val density = context.resources.displayMetrics.density
    private val tickWidth = 2f * density
    private val majorTickLength = 26f * density
    private val minorTickLength = 16f * density
    private val tickSpacing = 16f * density
    private val bgCornerRadius = 60f * density

    // Paints
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = tickWidth
        strokeCap = Paint.Cap.ROUND
    }

    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        strokeWidth = tickWidth
        strokeCap = Paint.Cap.ROUND
    }

    private val hcgColor = ContextCompat.getColor(context, R.color.perf_cpu_orange)
    private val hcgMinorColor = Color.argb(0x80, 0xE1, 0x88, 0x4B)

    private val hcgTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hcgColor
        strokeWidth = tickWidth
        strokeCap = Paint.Cap.ROUND
    }

    private val hcgMinorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hcgMinorColor
        strokeWidth = tickWidth
        strokeCap = Paint.Cap.ROUND
    }

    private val dcgMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hcgColor
        style = Paint.Style.FILL
    }

    private val centerIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = tickWidth * 1.5f
        strokeCap = Paint.Cap.ROUND
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.overlay_15)
        style = Paint.Style.FILL
    }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private var maskShader: Shader? = null
    private val layerBounds = RectF()

    // Physics & Gesture
    private val scroller = OverScroller(context)
    private val gestureDetector: GestureDetector

    // Callbacks
    var onValueChanged: ((index: Int, fraction: Float) -> Unit)? = null
    var onUserDragStarted: (() -> Unit)? = null

    init {
        // Transparent view background since we draw our own rounded rect and layer mask
        setBackgroundColor(Color.TRANSPARENT)

        val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                isDragging = true
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                onUserDragStarted?.invoke()
                val delta = if (dialOrientation == Orientation.HORIZONTAL) distanceX else distanceY
                scrollOffset = clampOffset(scrollOffset + delta)
                notifyValueChange()
                invalidate()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                onUserDragStarted?.invoke()
                val maxOffset = steps * tickSpacing
                val v = if (dialOrientation == Orientation.HORIZONTAL) -velocityX else -velocityY
                scroller.fling(
                    scrollOffset.roundToInt(), 0,
                    v.roundToInt(), 0,
                    0, maxOffset.roundToInt(),
                    0, 0,
                    (tickSpacing * 2).roundToInt(), 0
                )
                postInvalidateOnAnimation()
                return true
            }
        }
        gestureDetector = GestureDetector(context, gestureListener)
    }

    fun setRange(min: Int, max: Int, stepsCount: Int = (max - min)) {
        this.minValue = min
        this.maxValue = max
        this.steps = maxOf(1, stepsCount)
        scrollOffset = 0f
        invalidate()
    }

    fun setValue(value: Int) {
        if (isDragging) return
        val clamped = value.coerceIn(minValue, maxValue)
        val fraction = if (maxValue > minValue) (clamped - minValue).toFloat() / (maxValue - minValue) else 0f
        scrollOffset = fraction * steps * tickSpacing
        invalidate()
    }

    fun getCurrentValue(): Int {
        val fraction = getFraction()
        return (minValue + fraction * (maxValue - minValue)).roundToInt()
    }

    fun getCurrentIndex(): Int {
        return (scrollOffset / tickSpacing).roundToInt().coerceIn(0, steps)
    }

    fun setCurrentIndex(index: Int) {
        if (isDragging) return
        val clamped = index.coerceIn(0, steps)
        scrollOffset = clamped * tickSpacing
        invalidate()
    }

    private fun getFraction(): Float {
        val totalLength = steps * tickSpacing
        return if (totalLength > 0f) (scrollOffset / totalLength).coerceIn(0f, 1f) else 0f
    }

    private fun clampOffset(offset: Float): Float {
        val maxOffset = steps * tickSpacing
        return offset.coerceIn(0f, maxOffset)
    }

    private fun notifyValueChange() {
        val index = getCurrentIndex()
        val fraction = getFraction()
        if (dcgThresholdIndex in 0..steps) {
            val isHcg = index >= dcgThresholdIndex
            if (lastDcgHcgState != null && lastDcgHcgState != isHcg && isDragging) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            lastDcgHcgState = isHcg
        }
        onValueChanged?.invoke(index, fraction)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layerBounds.set(0f, 0f, w.toFloat(), h.toFloat())
        updateGradients()
    }

    private fun updateGradients() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (dialOrientation == Orientation.HORIZONTAL) {
            maskShader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.WHITE,
                    Color.WHITE,
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.2f, 0.8f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            maskShader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.WHITE,
                    Color.WHITE,
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.2f, 0.8f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        maskPaint.shader = maskShader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cornerRadius = if (dialOrientation == Orientation.VERTICAL) w / 2f else bgCornerRadius
        // Draw pill background
        canvas.drawRoundRect(0f, 0f, w, h, cornerRadius, cornerRadius, bgPaint)

        // Save layer for DST_IN edge fade masking
        val saveCount = canvas.saveLayer(layerBounds, null)

        if (dialOrientation == Orientation.HORIZONTAL) {
            drawHorizontalTicks(canvas, w, h)
        } else {
            drawVerticalTicks(canvas, w, h)
        }

        // Apply edge fade mask
        canvas.drawRect(layerBounds, maskPaint)
        canvas.restoreToCount(saveCount)

        // Draw center indicator on top (unmasked for crisp highlight)
        if (dialOrientation == Orientation.HORIZONTAL) {
            val cx = w / 2f
            val isHcgActive = (dcgThresholdIndex in 0..steps && getCurrentIndex() >= dcgThresholdIndex)
            centerIndicatorPaint.color = if (isHcgActive) hcgColor else Color.WHITE
            canvas.drawLine(cx, 2f * density, cx, h - 2f * density, centerIndicatorPaint)
        } else {
            val cy = h / 2f
            val cx = w / 2f
            val len = 36f * density
            centerIndicatorPaint.color = Color.WHITE
            centerIndicatorPaint.strokeWidth = 2.7f * density
            canvas.drawLine(cx - len / 2f, cy, cx + len / 2f, cy, centerIndicatorPaint)
        }
    }

    private fun drawHorizontalTicks(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val centerY = h / 2f
        tickPaint.strokeWidth = tickWidth

        val isDcgActive = (dcgThresholdIndex in 0..steps)

        for (i in 0..steps) {
            val tickX = cx + (i * tickSpacing) - scrollOffset
            if (tickX < -tickSpacing || tickX > w + tickSpacing) continue

            val isMajor = (i % 2 == 0)
            val len = if (isMajor) majorTickLength else minorTickLength
            val y1 = centerY - len / 2f
            val y2 = centerY + len / 2f

            val p = if (isDcgActive && i >= dcgThresholdIndex) {
                if (isMajor) hcgTickPaint else hcgMinorTickPaint
            } else {
                if (isMajor) tickPaint else minorTickPaint
            }
            canvas.drawLine(tickX, y1, tickX, y2, p)

            // Distinctive DCG switchover marker at threshold index (e.g. ISO 400)
            if (isDcgActive && i == dcgThresholdIndex) {
                val dotY = y1 - 2.5f * density
                canvas.drawCircle(tickX, dotY, 2f * density, dcgMarkerPaint)
            }
        }
    }

    private fun drawVerticalTicks(canvas: Canvas, w: Float, h: Float) {
        val cy = h / 2f
        val centerX = w / 2f
        val len = 36f * density
        tickPaint.strokeWidth = 2.7f * density

        for (i in 0..steps) {
            val tickY = cy + (i * tickSpacing) - scrollOffset
            if (tickY < -tickSpacing || tickY > h + tickSpacing) continue

            val x1 = centerX - len / 2f
            val x2 = centerX + len / 2f
            canvas.drawLine(x1, tickY, x2, tickY, tickPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            isDragging = false
            if (scroller.isFinished) {
                snapToNearestTick()
            }
        }
        return handled || super.onTouchEvent(event)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = clampOffset(scroller.currX.toFloat())
            notifyValueChange()
            postInvalidateOnAnimation()
        } else if (!isDragging && !scroller.isFinished) {
            snapToNearestTick()
        }
    }

    private fun snapToNearestTick() {
        val nearestIndex = (scrollOffset / tickSpacing).roundToInt().coerceIn(0, steps)
        val targetOffset = nearestIndex * tickSpacing
        if (abs(scrollOffset - targetOffset) > 0.5f) {
            scroller.startScroll(
                scrollOffset.roundToInt(), 0,
                (targetOffset - scrollOffset).roundToInt(), 0,
                150
            )
            postInvalidateOnAnimation()
        } else {
            scrollOffset = targetOffset
            notifyValueChange()
            invalidate()
        }
    }
}
