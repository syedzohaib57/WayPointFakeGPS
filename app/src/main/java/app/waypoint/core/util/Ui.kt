package app.waypoint.core.util

import android.content.Context
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import app.waypoint.R

object Motion {
    /** cubic-bezier(.2,.8,.2,1) — easeStd across the app. */
    val easeStd = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

    fun reduced(context: Context): Boolean =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
}

fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

fun Context.dpi(value: Float): Int = (dp(value) + 0.5f).toInt()

/** Design press feedback: scale 0.97, 120 ms. */
fun View.pressFx() {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(120).setInterpolator(Motion.easeStd).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).setInterpolator(Motion.easeStd).start()
        }
        false
    }
}

/** 3D icon float: ±6 dp over 3.2 s (design `float`). No-op under reduced motion. */
fun View.floatLoop() {
    if (Motion.reduced(context)) return
    animate().cancel()
    val up = android.animation.ObjectAnimator.ofFloat(this, View.TRANSLATION_Y, 0f, -context.dp(6f))
    up.duration = 1600
    up.repeatCount = android.animation.ValueAnimator.INFINITE
    up.repeatMode = android.animation.ValueAnimator.REVERSE
    up.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
    up.start()
    setTag(R.id.tag_float_anim, up)
}

fun View.stopFloat() {
    (getTag(R.id.tag_float_anim) as? android.animation.ObjectAnimator)?.cancel()
    translationY = 0f
}

/** Coloured glow under gradient buttons/FABs (API 28+; silently skipped below). */
fun View.glow(color: Int) {
    if (android.os.Build.VERSION.SDK_INT >= 28) {
        outlineSpotShadowColor = color
        outlineAmbientShadowColor = color
    }
}

/** Screen-enter: 300 ms fade + 10 dp rise (reduced motion ⇒ plain fade). */
fun View.enterRise(delayMs: Long = 0) {
    val reduced = Motion.reduced(context)
    alpha = 0f
    if (!reduced) translationY = context.dp(10f)
    animate()
        .alpha(1f)
        .translationY(0f)
        .setStartDelay(delayMs)
        .setDuration(300)
        .setInterpolator(Motion.easeStd)
        .start()
}
