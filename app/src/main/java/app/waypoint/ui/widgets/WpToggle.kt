package app.waypoint.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import app.waypoint.R
import app.waypoint.core.util.Motion
import app.waypoint.core.util.dp

/**
 * Design toggle (46×26, accent track when on) — custom-drawn so OEM themes
 * can't restyle it the way they do SwitchCompat.
 */
class WpToggle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val accent = ContextCompat.getColor(context, R.color.wp_accent)
    private val trackOff = ContextCompat.getColor(context, R.color.wp_bg3)
    private val lineOff = ContextCompat.getColor(context, R.color.wp_line)
    private val thumbOn = ContextCompat.getColor(context, R.color.wp_on_accent)
    private val thumbOff = ContextCompat.getColor(context, R.color.wp_text_low)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val rect = RectF()

    private var fraction = 0f
    private var animator: ValueAnimator? = null

    var onChange: ((Boolean) -> Unit)? = null

    var checked: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                animateTo(if (value) 1f else 0f)
            }
        }

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            checked = !checked
            onChange?.invoke(checked)
        }
    }

    /** Set without firing onChange (for state restoration from flows). */
    fun setCheckedSilently(value: Boolean) {
        if (checked != value) {
            val wasListener = onChange
            onChange = null
            checked = value
            onChange = wasListener
        }
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        if (Motion.reduced(context) || !isAttachedToWindow) {
            fraction = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(fraction, target).apply {
            duration = 250
            interpolator = Motion.easeStd
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dp(46f).toInt(), context.dp(26f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f

        // track
        paint.color = blend(trackOff, accent, fraction)
        rect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
        canvas.drawRoundRect(rect, r, r, paint)
        stroke.strokeWidth = context.dp(1f)
        stroke.color = blend(lineOff, accent, fraction)
        canvas.drawRoundRect(rect, r, r, stroke)

        // thumb 18 dp sliding 4→24 dp
        val thumbR = context.dp(9f)
        val cx = context.dp(4f) + thumbR + (context.dp(20f)) * fraction
        paint.color = blend(thumbOff, thumbOn, fraction)
        canvas.drawCircle(cx, h / 2f, thumbR, paint)
    }

    private fun blend(from: Int, to: Int, t: Float): Int =
        androidx.core.graphics.ColorUtils.blendARGB(from, to, t)

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Switch::class.java.name
        info.isCheckable = true
        info.isChecked = checked
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
