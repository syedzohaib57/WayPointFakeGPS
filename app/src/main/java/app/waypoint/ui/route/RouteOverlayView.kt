package app.waypoint.ui.route

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Point
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import app.waypoint.R
import app.waypoint.core.util.Motion
import app.waypoint.core.util.dp
import app.waypoint.data.geo.RoutePoint
import app.waypoint.mock.RouteFollower
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * Draws the route exactly like the design: polyline drawing in over 1.4 s,
 * waypoints popping 120 ms apart with white label pills, and the position
 * marker (accent idle / gold + halo while playing).
 */
class RouteOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var mapView: MapView? = null

    private var points: List<RoutePoint> = emptyList()
    private var follower: RouteFollower? = null

    var progressFraction: Float = 0f
        set(value) {
            field = value
            invalidate()
        }
    var playing: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                syncHalo()
                invalidate()
            }
        }

    private var drawIn = 1f
    private var popIn = 1f
    private var haloT = 0f
    private var drawAnim: ValueAnimator? = null
    private var haloAnim: ValueAnimator? = null

    private val accent = ContextCompat.getColor(context, R.color.wp_accent)
    private val gold = ContextCompat.getColor(context, R.color.wp_live)
    private val bg0 = ContextCompat.getColor(context, R.color.wp_bg0)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = accent
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.dp(11f)
        typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
        color = ContextCompat.getColor(context, R.color.wp_text_hi)
    }
    private val path = Path()
    private val measured = Path()
    private val pm = PathMeasure()
    private val pt = Point()
    private val labelRect = RectF()

    fun setRoute(newPoints: List<RoutePoint>, animate: Boolean) {
        points = newPoints
        follower = if (newPoints.size >= 2) RouteFollower(newPoints, 40, loop = false, reverse = false) else null
        drawAnim?.cancel()
        if (animate && newPoints.size >= 2 && !Motion.reduced(context)) {
            drawIn = 0f
            popIn = 0f
            drawAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1400 + newPoints.size * 120L
                interpolator = DecelerateInterpolator(1.2f)
                addUpdateListener {
                    val v = it.animatedValue as Float
                    drawIn = (v * (1400 + newPoints.size * 120L) / 1400f).coerceAtMost(1f)
                    popIn = v
                    invalidate()
                }
                start()
            }
        } else {
            drawIn = 1f
            popIn = 1f
        }
        invalidate()
    }

    private fun syncHalo() {
        haloAnim?.cancel()
        haloAnim = null
        if (playing && isAttachedToWindow && !Motion.reduced(context)) {
            haloAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1800
                repeatCount = ValueAnimator.INFINITE
                interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener {
                    haloT = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncHalo()
    }

    override fun onDetachedFromWindow() {
        drawAnim?.cancel()
        haloAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val map = mapView ?: return
        if (points.isEmpty()) return
        val proj = map.projection ?: return

        // polyline
        if (points.size >= 2) {
            path.reset()
            points.forEachIndexed { i, p ->
                proj.toPixels(GeoPoint(p.lat, p.lon), pt)
                if (i == 0) path.moveTo(pt.x.toFloat(), pt.y.toFloat())
                else path.lineTo(pt.x.toFloat(), pt.y.toFloat())
            }
            if (drawIn >= 1f) {
                canvas.drawPath(path, linePaint)
            } else {
                pm.setPath(path, false)
                measured.reset()
                pm.getSegment(0f, pm.length * drawIn, measured, true)
                canvas.drawPath(measured, linePaint)
            }
        }

        // waypoint markers + labels
        val markerR = context.dp(9f)
        points.forEachIndexed { i, p ->
            val tPop = ((popIn * (points.size + 3)) - i).coerceIn(0f, 1f)
            if (tPop <= 0f) return@forEachIndexed
            proj.toPixels(GeoPoint(p.lat, p.lon), pt)
            val x = pt.x.toFloat()
            val y = pt.y.toFloat()
            val s = 0.6f + 0.4f * tPop
            fill.color = Color.WHITE
            canvas.drawCircle(x, y, markerR * s, fill)
            stroke.strokeWidth = context.dp(3f)
            stroke.color = accent
            canvas.drawCircle(x, y, markerR * s, stroke)

            if (p.name.isNotEmpty() && tPop > 0.6f) {
                val tw = labelPaint.measureText(p.name)
                val padH = context.dp(7f)
                val lx = x + context.dp(16f)
                val ly = y - context.dp(6f)
                labelRect.set(lx, ly - context.dp(9f), lx + tw + padH * 2, ly + context.dp(9f))
                fill.color = ColorUtils.setAlphaComponent(Color.WHITE, 242)
                canvas.drawRoundRect(labelRect, context.dp(6f), context.dp(6f), fill)
                stroke.strokeWidth = context.dp(1f)
                stroke.color = ColorUtils.setAlphaComponent(Color.BLACK, 18)
                canvas.drawRoundRect(labelRect, context.dp(6f), context.dp(6f), stroke)
                canvas.drawText(p.name, lx + padH, ly + context.dp(4f), labelPaint)
            }
        }

        // position marker
        val f = follower ?: return
        val pos = f.positionAt(progressFraction.toDouble())
        proj.toPixels(GeoPoint(pos.lat, pos.lon), pt)
        val x = pt.x.toFloat()
        val y = pt.y.toFloat()
        val c = if (playing) gold else accent

        if (playing && haloT > 0f) {
            val r = context.dp(24f) * (0.5f + 1.5f * haloT)
            val a = ((1f - haloT) * 255).toInt().coerceIn(0, 255)
            fill.color = ColorUtils.setAlphaComponent(gold, (a * 0.18f).toInt())
            canvas.drawCircle(x, y, r, fill)
            stroke.strokeWidth = context.dp(2f)
            stroke.color = ColorUtils.setAlphaComponent(gold, a)
            canvas.drawCircle(x, y, r, stroke)
        }

        fill.color = c
        canvas.drawCircle(x, y, context.dp(12f), fill)
        stroke.strokeWidth = context.dp(3f)
        stroke.color = bg0
        canvas.drawCircle(x, y, context.dp(12f), stroke)
        stroke.strokeWidth = context.dp(2f)
        stroke.color = c
        canvas.drawCircle(x, y, context.dp(14.5f), stroke)
    }
}
