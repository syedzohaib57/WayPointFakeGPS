package app.waypoint.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.waypoint.R
import app.waypoint.core.util.Motion

/** 8 dp dot blinking at 1 Hz in steps (design `blink` keyframe). */
class LiveDotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.wp_live)
    }
    private var dim = false
    private val tick = object : Runnable {
        override fun run() {
            dim = !dim
            invalidate()
            postDelayed(this, 500)
        }
    }

    var dotColor: Int
        get() = paint.color
        set(value) { paint.color = value; invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!Motion.reduced(context)) postDelayed(tick, 500)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        paint.alpha = if (dim) 51 else 255
        val r = minOf(width, height) / 2f
        canvas.drawCircle(width / 2f, height / 2f, r, paint)
    }
}
