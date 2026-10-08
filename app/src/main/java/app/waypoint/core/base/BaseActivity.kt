package app.waypoint.core.base

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import app.waypoint.R
import app.waypoint.core.session.SessionState
import app.waypoint.core.util.dpi
import app.waypoint.core.util.enterRise
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Shared chrome for every screen: edge-to-edge, the gold status strip while a
 * fake location is live (G1), the floating toast, and the screen-enter rise.
 */
abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {

    protected lateinit var binding: VB
    protected val session: SessionState by inject()

    protected abstract fun inflateBinding(inflater: LayoutInflater): VB

    /** Set false on screens that manage their own entrance (e.g. Splash). */
    protected open val enterAnimation = true

    /** Splash keeps white status icons; everything else is dark-on-light. */
    protected open val lightStatusIcons = false

    private var statusStrip: FrameLayout? = null
    private var toastView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = inflateBinding(layoutInflater)
        setContentView(binding.root)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !lightStatusIcons
            isAppearanceLightNavigationBars = !lightStatusIcons
        }
        installStatusStrip()
        if (enterAnimation) binding.root.enterRise()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.live.collect { live -> statusStrip?.visibility = if (live) View.VISIBLE else View.GONE }
            }
        }
    }

    private fun installStatusStrip() {
        val strip = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@BaseActivity, R.drawable.status_strip)
            visibility = View.GONE
            elevation = dpi(30f).toFloat()
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val glyph = ImageView(this).apply {
            setImageResource(R.drawable.ic_location_on_fill)
            imageTintList = ContextCompat.getColorStateList(this@BaseActivity, R.color.wp_on_live)
            contentDescription = null
        }
        strip.addView(glyph, FrameLayout.LayoutParams(dpi(14f), dpi(14f), Gravity.CENTER))
        addContentView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1, Gravity.TOP))
        ViewCompat.setOnApplyWindowInsetsListener(strip) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            if (v.layoutParams.height != top && top > 0) v.updateLayoutParams { height = top }
            insets
        }
        statusStrip = strip
    }

    /** Floating toast card 92 dp above the bottom; replaces the previous one (design `Toast`). */
    fun say(message: String) {
        val root = findViewById<ViewGroup>(android.R.id.content) ?: return
        toastView?.let { root.removeView(it) }
        val tv = layoutInflater.inflate(R.layout.view_toast, root, false) as TextView
        tv.text = message
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )
        lp.marginStart = dpi(16f)
        lp.marginEnd = dpi(16f)
        lp.bottomMargin = dpi(92f) +
            ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom.orZero()
        root.addView(tv, lp)
        tv.enterRise()
        toastView = tv
        tv.announceForAccessibility(message)
        tv.postDelayed({
            if (toastView === tv) {
                root.removeView(tv)
                toastView = null
            }
        }, 2400)
    }

    private fun Int?.orZero() = this ?: 0

    /** Pad a view by system-bar insets without stacking on re-dispatch. */
    protected fun padToInsets(view: View, top: Boolean = false, bottom: Boolean = false) {
        val basePt = view.paddingTop
        val basePb = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                if (top) basePt + sb.top else v.paddingTop,
                v.paddingRight,
                if (bottom) basePb + sb.bottom else v.paddingBottom
            )
            insets
        }
    }
}
