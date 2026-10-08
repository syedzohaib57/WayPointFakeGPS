package app.waypoint.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import app.waypoint.core.util.Motion
import app.waypoint.core.util.dp

/**
 * Splash radar: three rings, cross-hairs, a rotating 90° sweep (true conic
 * gradient via SweepGradient) and four gold satellite dots blinking in sequence.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f)
        color = Color.argb(77, 255, 255, 255)
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = context.dp(1f)
        color = Color.argb(56, 255, 255, 255)
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotGlow = Paint(Paint.ANTI_ALIAS_FLAG)

    private var sweepAngle = 0f
    private var time = 0f
    private var animator: ValueAnimator? = null

    // design positions in a 270×270 box (dot centres)
    private val dots = arrayOf(
        floatArrayOf(67.5f, 47.5f),
        floatArrayOf(207.5f, 93.5f),
        floatArrayOf(159.5f, 215.5f),
        floatArrayOf(53.5f, 179.5f)
    )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Motion.reduced(context)) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                sweepAngle = (it.animatedValue as Float) * 360f
                time += 0.016f
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2
        val unit = w / 270f

        // rings at 270/190/110 design-px diameters
        floatArrayOf(135f, 95f, 55f).forEach { r ->
            canvas.drawCircle(cx, cy, r * unit, ring)
        }
        // cross-hairs
        canvas.drawLine(0f, cy, w, cy, cross)
        canvas.drawLine(cx, 0f, cx, h, cross)

        // 90° sweep
        val shader = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.argb(107, 255, 255, 255),
                Color.argb(0, 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.25f, 1f)
        )
        val m = android.graphics.Matrix()
        m.setRotate(sweepAngle - 90f, cx, cy)
        shader.setLocalMatrix(m)
        sweepPaint.shader = shader
        canvas.drawCircle(cx, cy, 135f * unit, sweepPaint)

        // gold dots, blinking 2.4 s staggered 0.6 s
        dots.forEachIndexed { i, (dx, dy) ->
            val phase = ((time / 2.4f) - i * 0.25f).mod(1f)
            val alpha = if (phase < 0.5f) 1f else 0.25f
            dotGlow.color = Color.argb((alpha * 110).toInt(), 255, 176, 32)
            dotPaint.color = Color.argb((alpha * 255).toInt(), 255, 211, 106)
            canvas.drawCircle(dx * unit, dy * unit, 7f * unit, dotGlow)
            canvas.drawCircle(dx * unit, dy * unit, 3.5f * unit, dotPaint)
        }
    }
}
