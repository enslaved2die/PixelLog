package com.pixellog.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * FocusExposureOverlayView renders cinema-grade focus and exposure reticles directly
 * over the viewfinder SurfaceView.
 *
 * Interactivity:
 * - Single Tap: Positions and animates the autofocus (AF) reticle.
 * - Long Press (Hold): Positions and animates the autoexposure (AE) spot reticle with AE-LOCK badge.
 * - Double Tap: Dismisses reticles and resets AF/AE to full-scene continuous mode.
 */
class FocusExposureOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Callbacks ──
    var onTapFocus: ((normX: Float, normY: Float) -> Unit)? = null
    var onHoldExposure: ((normX: Float, normY: Float) -> Unit)? = null
    var onResetAfAe: (() -> Unit)? = null

    // ── Reticle State ──
    private var isFocusVisible = false
    private var focusX = 0f
    private var focusY = 0f
    private var focusScale = 1f
    private var focusAlpha = 1f
    private var focusColor = Color.parseColor("#00E5FF") // Cyan

    private var isExposureVisible = false
    private var exposureX = 0f
    private var exposureY = 0f
    private var exposureScale = 1f
    private var exposureAlpha = 1f
    private var isExposureLocked = false
    private val exposureColor = Color.parseColor("#E1884B") // Amber / Orange

    private val mainHandler = Handler(Looper.getMainLooper())
    private var focusFadeRunnable: Runnable? = null
    private var exposureFadeRunnable: Runnable? = null

    // ── Paints ──
    private val density = context.resources.displayMetrics.density
    private val strokeWidthDp = 2f * density
    private val cornerLen = 14f * density
    private val focusBoxHalf = 32f * density

    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthDp
        strokeCap = Paint.Cap.ROUND
    }

    private val exposurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthDp
        strokeCap = Paint.Cap.ROUND
    }

    private val exposureFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val badgeRect = RectF()

    // ── Gesture Detector ──
    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val normX = (e.x / width).coerceIn(0f, 1f)
            val normY = (e.y / height).coerceIn(0f, 1f)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showFocus(e.x, e.y)
            onTapFocus?.invoke(normX, normY)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val normX = (e.x / width).coerceIn(0f, 1f)
            val normY = (e.y / height).coerceIn(0f, 1f)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showExposure(e.x, e.y, locked = true)
            onHoldExposure?.invoke(normX, normY)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            resetAll()
            onResetAfAe?.invoke()
            return true
        }
    }

    private val gestureDetector = GestureDetector(context, gestureListener).apply {
        setIsLongpressEnabled(true)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    // ── Public Reticle Control ──

    fun showFocus(x: Float, y: Float) {
        focusX = x.coerceIn(focusBoxHalf, width - focusBoxHalf)
        focusY = y.coerceIn(focusBoxHalf, height - focusBoxHalf)
        isFocusVisible = true
        focusColor = Color.parseColor("#00E5FF") // Cyan seeking
        focusAlpha = 1f

        focusFadeRunnable?.let { mainHandler.removeCallbacks(it) }

        // Animate pop-in: 1.35x -> 1.0x scale
        ValueAnimator.ofFloat(1.35f, 1f).apply {
            duration = 240
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener {
                focusScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        scheduleFocusFade()
    }

    fun setFocusLocked(success: Boolean) {
        if (!isFocusVisible) return
        focusColor = if (success) {
            Color.parseColor("#54E14B") // Green on lock
        } else {
            Color.parseColor("#FF1744") // Red on fail
        }
        invalidate()

        // After 600ms, revert green flash back to cyan accent and settle
        mainHandler.postDelayed({
            if (isFocusVisible) {
                focusColor = Color.parseColor("#00E5FF")
                invalidate()
            }
        }, 600)
    }

    fun dismissFocus() {
        if (!isFocusVisible) return
        focusFadeRunnable?.let { mainHandler.removeCallbacks(it) }
        ValueAnimator.ofFloat(focusAlpha, 0f).apply {
            duration = 200
            addUpdateListener {
                focusAlpha = it.animatedValue as Float
                if (focusAlpha <= 0.01f) {
                    isFocusVisible = false
                }
                invalidate()
            }
            start()
        }
    }

    fun showExposure(x: Float, y: Float, locked: Boolean = true) {
        val expRadius = 36f * density
        exposureX = x.coerceIn(expRadius, width - expRadius)
        exposureY = y.coerceIn(expRadius + 20f * density, height - expRadius)
        isExposureVisible = true
        isExposureLocked = locked
        exposureAlpha = 1f

        exposureFadeRunnable?.let { mainHandler.removeCallbacks(it) }

        // Animate pop-in with gentle pulse
        ValueAnimator.ofFloat(1.3f, 1f).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                exposureScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        scheduleExposureFade()
    }

    fun setExposureLocked(locked: Boolean) {
        isExposureLocked = locked
        invalidate()
    }

    fun dismissExposure() {
        if (!isExposureVisible) return
        exposureFadeRunnable?.let { mainHandler.removeCallbacks(it) }
        ValueAnimator.ofFloat(exposureAlpha, 0f).apply {
            duration = 200
            addUpdateListener {
                exposureAlpha = it.animatedValue as Float
                if (exposureAlpha <= 0.01f) {
                    isExposureVisible = false
                }
                invalidate()
            }
            start()
        }
    }

    fun resetAll() {
        dismissFocus()
        dismissExposure()
    }

    private fun scheduleFocusFade() {
        focusFadeRunnable?.let { mainHandler.removeCallbacks(it) }
        focusFadeRunnable = Runnable {
            ValueAnimator.ofFloat(focusAlpha, 0.4f).apply {
                duration = 400
                addUpdateListener {
                    focusAlpha = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        mainHandler.postDelayed(focusFadeRunnable!!, 3500)
    }

    private fun scheduleExposureFade() {
        exposureFadeRunnable?.let { mainHandler.removeCallbacks(it) }
        exposureFadeRunnable = Runnable {
            ValueAnimator.ofFloat(exposureAlpha, 0.4f).apply {
                duration = 400
                addUpdateListener {
                    exposureAlpha = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        mainHandler.postDelayed(exposureFadeRunnable!!, 3500)
    }

    // ── Drawing ──

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (isFocusVisible) {
            drawFocusReticle(canvas)
        }

        if (isExposureVisible) {
            drawExposureReticle(canvas)
        }
    }

    private fun drawFocusReticle(canvas: Canvas) {
        val half = focusBoxHalf * focusScale
        val cl = cornerLen * focusScale

        focusPaint.color = focusColor
        focusPaint.alpha = (focusAlpha * 255).toInt()

        val l = focusX - half
        val r = focusX + half
        val t = focusY - half
        val b = focusY + half

        // Top-Left Corner
        canvas.drawLine(l, t, l + cl, t, focusPaint)
        canvas.drawLine(l, t, l, t + cl, focusPaint)

        // Top-Right Corner
        canvas.drawLine(r - cl, t, r, t, focusPaint)
        canvas.drawLine(r, t, r, t + cl, focusPaint)

        // Bottom-Left Corner
        canvas.drawLine(l, b, l + cl, b, focusPaint)
        canvas.drawLine(l, b - cl, l, b, focusPaint)

        // Bottom-Right Corner
        canvas.drawLine(r - cl, b, r, b, focusPaint)
        canvas.drawLine(r, b - cl, r, b, focusPaint)

        // Center crosshair (small 8dp cross with 4dp center gap)
        val chLen = 5f * density * focusScale
        val chGap = 2.5f * density * focusScale
        canvas.drawLine(focusX - chGap - chLen, focusY, focusX - chGap, focusY, focusPaint)
        canvas.drawLine(focusX + chGap, focusY, focusX + chGap + chLen, focusY, focusPaint)
        canvas.drawLine(focusX, focusY - chGap - chLen, focusX, focusY - chGap, focusPaint)
        canvas.drawLine(focusX, focusY + chGap, focusX, focusY + chGap + chLen, focusPaint)
    }

    private fun drawExposureReticle(canvas: Canvas) {
        val radius = 24f * density * exposureScale
        val tickLen = 6f * density * exposureScale
        val tickGap = 4f * density * exposureScale

        exposurePaint.color = exposureColor
        val alphaInt = (exposureAlpha * 255).toInt()
        exposurePaint.alpha = alphaInt

        // Metering Ring
        canvas.drawCircle(exposureX, exposureY, radius, exposurePaint)

        // 8 Radial Sun Rays
        val innerR = radius + tickGap
        val outerR = innerR + tickLen
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45.0))
            val x1 = (exposureX + innerR * cos(angle)).toFloat()
            val y1 = (exposureY + innerR * sin(angle)).toFloat()
            val x2 = (exposureX + outerR * cos(angle)).toFloat()
            val y2 = (exposureY + outerR * sin(angle)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, exposurePaint)
        }

        // [AE LOCK] or [AE METER] pill badge above ring
        val badgeW = 58f * density
        val badgeH = 16f * density
        val badgeTop = exposureY - radius - tickGap - tickLen - 16f * density
        badgeRect.set(
            exposureX - badgeW / 2f,
            badgeTop,
            exposureX + badgeW / 2f,
            badgeTop + badgeH
        )

        exposureFillPaint.color = exposureColor
        exposureFillPaint.alpha = alphaInt
        val cornerRadius = 4f * density
        canvas.drawRoundRect(badgeRect, cornerRadius, cornerRadius, exposureFillPaint)

        textPaint.alpha = alphaInt
        val text = if (isExposureLocked) "AE LOCK" else "AE METER"
        val textY = badgeTop + (badgeH / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(text, exposureX, textY, textPaint)
    }
}
