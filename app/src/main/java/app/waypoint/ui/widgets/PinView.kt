package app.waypoint.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.waypoint.R
import app.waypoint.core.util.Motion
import app.waypoint.core.util.dp

/**
 * Reticle pin (design `Pin`): soft 128 dp halo, 30 dp ring, four ticks, white core.
 * Idle: accent + one `ping` ring (2.8 s). Live: gold + two `halo` rings (2.2 s, 1.1 s offset).
 */
class PinView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val accent = ContextCompat.getColor(context, R.color.wp_accent)
    private val gold = ContextCompat.getColor(context, R.color.wp_live)

    var live: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                haloShader = null
                restartLoops()
                invalidate()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var haloShader: RadialGradient? = null

    // loop progress 0..1 per ring
    private var ringA = 0f
    private var ringB = 0f
    private var animA: ValueAnimator? = null
    private var animB: ValueAnimator? = null

    init {
        context.obtainStyledAttributes(attrs, R.styleable.PinView).apply {
            live = getBoolean(R.styleable.PinView_pinLive, false)
            recycle()
        }
    }

    private fun colorNow() = if (live) gold else accent

    private fun restartLoops() {
        animA?.cancel(); animB?.cancel(); animA = null; animB = null
        ringA = 0f; ringB = -1f
        if (!isAttachedToWindow || Motion.reduced(context)) return
        val dur = if (live) 2200L else 2800L
        animA = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { ringA = it.animatedValue as Float; invalidate() }
            start()
        }
        if (live) {
            animB = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = dur
                startDelay = dur / 2
                repeatCount = ValueAnimator.INFINITE
                interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener { ringB = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartLoops()
    }

    override fun onDetachedFromWindow() {
        animA?.cancel(); animB?.cancel(); animA = null; animB = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val c = colorNow()

        // Soft halo disc, 128 dp
        val haloR = context.dp(64f)
        if (haloShader == null) {
            haloShader = RadialGradient(
                0f, 0f, haloR,
                intArrayOf(
                    ColorUtils.setAlphaComponent(c, 66),
                    ColorUtils.setAlphaComponent(c, 18),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.55f, 0.72f),
                Shader.TileMode.CLAMP
            )
        }
        paint.shader = haloShader
        canvas.save()
        canvas.translate(cx, cy)
        canvas.drawCircle(0f, 0f, haloR, paint)
        canvas.restore()
        paint.shader = null
        stroke.strokeWidth = context.dp(1f)
        stroke.color = ColorUtils.setAlphaComponent(c, 89)
        canvas.drawCircle(cx, cy, haloR, stroke)

        // Expanding rings
        if (live) {
            drawHaloRing(canvas, cx, cy, ringA)
            if (ringB >= 0f) drawHaloRing(canvas, cx, cy, ringB)
        } else if (ringA > 0f) {
            val r = context.dp(30f) * (1f + ringA)
            stroke.strokeWidth = context.dp(1.5f)
            stroke.color = ColorUtils.setAlphaComponent(accent, (140 * (1 - ringA)).toInt().coerceAtLeast(0))
            canvas.drawCircle(cx, cy, r, stroke)
        }

        // Four ticks: 2×9 dp, inner edge 15 dp from centre (design: top -24 → 24..15)
        paint.color = c
        val tickHalf = context.dp(1f)
        val tIn = context.dp(15f)
        val tOut = context.dp(24f)
        for (i in 0 until 4) {
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(i * 90f)
            canvas.drawRect(-tickHalf, -tOut, tickHalf, -tIn, paint)
            canvas.restore()
        }

        // 30 dp ring with glow
        stroke.strokeWidth = context.dp(2f)
        stroke.color = ColorUtils.setAlphaComponent(c, 60)
        canvas.drawCircle(cx, cy, context.dp(16.5f), stroke)
        stroke.color = c
        canvas.drawCircle(cx, cy, context.dp(15f), stroke)

        // Core: 10 dp white + 2 dp colour ring
        paint.color = ColorUtils.setAlphaComponent(c, 70)
        canvas.drawCircle(cx, cy, context.dp(8.5f), paint)
        paint.color = c
        canvas.drawCircle(cx, cy, context.dp(7f), paint)
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, context.dp(5f), paint)
    }

    private fun drawHaloRing(canvas: Canvas, cx: Float, cy: Float, t: Float) {
        if (t < 0f) return
        val scale = 0.5f + 1.9f * t
        val r = context.dp(28f) * scale
        val alpha = ((1f - t) * 0.9f * 255).toInt().coerceIn(0, 255)
        paint.color = ColorUtils.setAlphaComponent(gold, (alpha * 0.16f).toInt())
        canvas.drawCircle(cx, cy, r, paint)
        stroke.strokeWidth = context.dp(2f)
        stroke.color = ColorUtils.setAlphaComponent(gold, alpha)
        canvas.drawCircle(cx, cy, r, stroke)
    }
}
