package com.pixellog.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import com.pixellog.R

/**
 * Custom View rendering CPU, RAM, and GPU performance gauges
 * adhering pixel-perfectly to the Penpot design:
 * - 3 rounded pill bars (#2F2F2F track, #B1B2B5 active fill)
 * - 90-degree rotated typography ("CPU", "RAM", "GPU") below each bar
 * - Smooth hardware-accelerated value transitions
 */
class PerformanceBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density

    // Exact proportions from Penpot (scaled from 2856x1280 @ 480dpi / density 3.0)
    private val barWidth = 6f * density
    private val barHeight = 76f * density
    private val barSpacing = 6f * density
    private val cornerRadius = 3f * density
    private val textGap = 4f * density
    private val textSizePx = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP,
        8f,
        context.resources.displayMetrics
    )

    private val labels = arrayOf("CPU", "RAM", "GPU")

    // Dynamic metrics (0..100)
    var cpuPercent: Float = 20f
        private set
    var ramPercent: Float = 40f
        private set
    var gpuPercent: Float = 30f
        private set

    // Displayed values for smooth interpolation
    private var displayedCpu = 20f
    private var displayedRam = 40f
    private var displayedGpu = 30f

    private var animator: ValueAnimator? = null

    // Paints
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2F2F2F")
        style = Paint.Style.FILL
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B1B2B5")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = textSizePx
        textAlign = Paint.Align.LEFT
        try {
            val gabaritoFont = ResourcesCompat.getFont(context, R.font.gabarito)
            typeface = if (gabaritoFont != null) {
                Typeface.create(gabaritoFont, Typeface.BOLD)
            } else {
                Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        } catch (_: Exception) {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    private val trackRect = RectF()
    private val trackPath = Path()

    fun setPerformance(cpu: Float, ram: Float, gpu: Float, animate: Boolean = true) {
        val targetCpu = cpu.coerceIn(0f, 100f)
        val targetRam = ram.coerceIn(0f, 100f)
        val targetGpu = gpu.coerceIn(0f, 100f)

        cpuPercent = targetCpu
        ramPercent = targetRam
        gpuPercent = targetGpu

        if (!animate) {
            displayedCpu = targetCpu
            displayedRam = targetRam
            displayedGpu = targetGpu
            invalidate()
            return
        }

        animator?.cancel()
        val startCpu = displayedCpu
        val startRam = displayedRam
        val startGpu = displayedGpu

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350L
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val fraction = va.animatedValue as Float
                displayedCpu = startCpu + (targetCpu - startCpu) * fraction
                displayedRam = startRam + (targetRam - startRam) * fraction
                displayedGpu = startGpu + (targetGpu - startGpu) * fraction
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (paddingLeft + paddingRight + 3 * barWidth + 2 * barSpacing).toInt()
        // Text length is ~26dp for "CPU"/"RAM"/"GPU"
        val desiredHeight = (paddingTop + paddingBottom + barHeight + textGap + 26f * density).toInt()

        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val fm = textPaint.fontMetrics
        val textBaselineOffsetY = -(fm.ascent + fm.descent) / 2f
        val textTop = paddingTop + barHeight + textGap

        for (i in 0..2) {
            val barLeft = paddingLeft + i * (barWidth + barSpacing)
            val barRight = barLeft + barWidth
            val barTop = paddingTop.toFloat()
            val barBottom = barTop + barHeight

            // 1. Draw rounded track (#2F2F2F)
            trackRect.set(barLeft, barTop, barRight, barBottom)
            trackPath.reset()
            trackPath.addRoundRect(trackRect, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.drawPath(trackPath, trackPaint)

            // 2. Draw active fill (#B1B2B5) clipped to the track capsule
            val percent = when (i) {
                0 -> displayedCpu
                1 -> displayedRam
                else -> displayedGpu
            }
            val fillHeight = barHeight * (percent / 100f).coerceIn(0f, 1f)
            if (fillHeight > 0f) {
                canvas.save()
                canvas.clipPath(trackPath)
                canvas.drawRect(barLeft, barBottom - fillHeight, barRight, barBottom, fillPaint)
                canvas.restore()
            }

            // 3. Draw 90-degree rotated typography ("CPU", "RAM", "GPU") reading upwards
            val barCenterX = barLeft + barWidth / 2f
            val textLen = textPaint.measureText(labels[i])
            val textBottom = textTop + textLen
            canvas.save()
            canvas.translate(barCenterX, textBottom)
            canvas.rotate(-90f)
            canvas.drawText(labels[i], 0f, textBaselineOffsetY, textPaint)
            canvas.restore()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
