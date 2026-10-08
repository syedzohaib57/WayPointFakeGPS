package app.waypoint.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import app.waypoint.R
import app.waypoint.core.util.Motion
import app.waypoint.core.util.dp

/**
 * Progress ring. progress == null ⇒ indeterminate: a 28 % arc spinning at 1.1 s/turn.
 */
class RingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val rect = RectF()

    var ringColor: Int = ContextCompat.getColor(context, R.color.wp_accent)
        set(value) { field = value; arcPaint.color = value; invalidate() }
    var trackColor: Int = ContextCompat.getColor(context, R.color.wp_bg3)
        set(value) { field = value; trackPaint.color = value; invalidate() }
    var stroke: Float = context.dp(5f)
        set(value) { field = value; arcPaint.strokeWidth = value; trackPaint.strokeWidth = value; invalidate() }

    /** null = indeterminate */
    var progress: Float? = null
        set(value) { field = value; syncSpin(); invalidate() }

    private var spinAngle = 0f
    private var spinner: ValueAnimator? = null

    init {
        context.obtainStyledAttributes(attrs, R.styleable.RingView).use { a ->
            ringColor = a.getColor(R.styleable.RingView_ringColor, ringColor)
            trackColor = a.getColor(R.styleable.RingView_ringTrackColor, trackColor)
            stroke = a.getDimension(R.styleable.RingView_ringStroke, stroke)
            if (a.getBoolean(R.styleable.RingView_ringIndeterminate, false)) progress = null
            else progress = 0f
        }
        arcPaint.color = ringColor
        trackPaint.color = trackColor
        arcPaint.strokeWidth = stroke
        trackPaint.strokeWidth = stroke
    }

    private fun syncSpin() {
        if (progress == null && isAttachedToWindow && !Motion.reduced(context)) {
            if (spinner == null) {
                spinner = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 1100
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { spinAngle = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        } else {
            spinner?.cancel()
            spinner = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncSpin()
    }

    override fun onDetachedFromWindow() {
        spinner?.cancel()
        spinner = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val half = stroke / 2f
        rect.set(half, half, width - half, height - half)
        canvas.drawOval(rect, trackPaint)
        val p = progress
        if (p != null) {
            canvas.drawArc(rect, -90f, 360f * p.coerceIn(0f, 1f), false, arcPaint)
        } else {
            canvas.drawArc(rect, spinAngle - 90f, 100.8f, false, arcPaint)
        }
    }
}
