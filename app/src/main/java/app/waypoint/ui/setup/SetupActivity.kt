package app.waypoint.ui.setup

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.waypoint.R
import app.waypoint.core.base.BaseActivity
import app.waypoint.core.util.dpi
import app.waypoint.core.util.enterRise
import app.waypoint.core.util.floatLoop
import app.waypoint.core.util.pressFx
import app.waypoint.core.util.stopFloat
import app.waypoint.data.prefs.SettingsStore
import app.waypoint.databinding.ActivitySetupBinding
import app.waypoint.databinding.ItemCheckLineBinding
import app.waypoint.databinding.ItemSetupStepBinding
import app.waypoint.mock.MockEngine
import app.waypoint.mock.SelfCheck
import app.waypoint.ui.map.MapActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class SetupActivity : BaseActivity<ActivitySetupBinding>() {

    private val settingsStore: SettingsStore by inject()
    private val engine by lazy { MockEngine(this) }

    private var step = 0
    private var busy = false
    private var checkStarted = false
    private var checkRunning = false
    private var checkDone = false
    private var hint: String? = null
    private var fromSettings = false

    private val stepViews = mutableListOf<ItemSetupStepBinding>()
    private val lineViews = mutableListOf<ItemCheckLineBinding>()

    private val titles by lazy {
        listOf(R.string.setup_s1_title, R.string.setup_s2_title, R.string.setup_s3_title)
    }
    private val bodies by lazy {
        listOf(R.string.setup_s1_body, R.string.setup_s2_body, R.string.setup_s3_body)
    }
    private val buttons by lazy {
        listOf(R.string.setup_s1_btn, R.string.setup_s2_btn, R.string.setup_s3_btn)
    }
    private val icons3d = intArrayOf(
        R.drawable.wp3d_gear, R.drawable.wp3d_mobile_phone, R.drawable.wp3d_bullseye
    )

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                runCheck()
            } else {
                hint = getString(R.string.setup_perm_needed)
                render()
            }
        }

    override fun inflateBinding(inflater: LayoutInflater): ActivitySetupBinding =
        ActivitySetupBinding.inflate(inflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fromSettings = intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)
        padToInsets(binding.setupRoot, top = true, bottom = true)

        repeat(3) { i ->
            val v = ItemSetupStepBinding.inflate(layoutInflater, binding.stepsContainer, true)
            v.stepButton.pressFx()
            v.stepButton.setOnClickListener { onStepButton(i) }
            stepViews.add(v)
        }
        val defaults = listOf(
            getString(R.string.check_line1),
            getString(R.string.check_line2),
            getString(R.string.check_line3)
        )
        repeat(3) { i ->
            val lv = ItemCheckLineBinding.inflate(layoutInflater, stepViews[2].checkLines, true)
            lv.lineText.text = defaults[i]
            lineViews.add(lv)
        }

        binding.batteryRow.setOnClickListener { openAppDetails() }
        render()
    }

    override fun onResume() {
        super.onResume()
        busy = false
        autoAdvance()
    }

    private fun autoAdvance() {
        if (checkDone || checkRunning) {
            render()
            return
        }
        val dev = Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        val mock = engine.isMockAppSelected()
        val target = when {
            !dev -> 0
            !mock -> 1
            else -> 2
        }
        if (target != step) {
            step = target
            if (target != 1) hint = null
        }
        render()
    }

    private fun render() {
        val shownStep = if (checkDone) 3 else step + 1
        binding.stepCounter.text = getString(R.string.setup_counter, shownStep.coerceAtMost(3))
        val filled = step + if (checkDone) 1 else 0
        listOf(binding.seg1, binding.seg2, binding.seg3).forEachIndexed { i, seg ->
            seg.setBackgroundResource(if (i < filled) R.drawable.seg_on else R.drawable.seg_off)
        }
        stepViews.forEachIndexed { i, v -> bindStep(v, i) }
    }

    private fun bindStep(v: ItemSetupStepBinding, i: Int) {
        val active = i == step
        val done = i < step || (i == 2 && checkDone)

        when {
            done -> {
                v.badge.text = "✓"
                v.badge.setTextColor(ContextCompat.getColor(this, R.color.wp_on_accent))
                v.badge.setBackgroundResource(R.drawable.badge_done)
            }
            active -> {
                v.badge.text = (i + 1).toString()
                v.badge.setTextColor(ContextCompat.getColor(this, R.color.wp_text_hi))
                v.badge.setBackgroundResource(R.drawable.badge_active)
            }
            else -> {
                v.badge.text = (i + 1).toString()
                v.badge.setTextColor(ContextCompat.getColor(this, R.color.wp_text_low))
                v.badge.setBackgroundResource(R.drawable.badge_idle)
            }
        }

        v.stepTitle.setText(titles[i])
        v.stepTitle.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    active -> R.color.wp_text_hi
                    done -> R.color.wp_text_mid
                    else -> R.color.wp_text_low
                }
            )
        )

        v.stepBody.setText(bodies[i])
        v.stepBody.visibility = if (active) android.view.View.VISIBLE else android.view.View.GONE
        v.checkLines.visibility =
            if (active && i == 2 && checkStarted) android.view.View.VISIBLE else android.view.View.GONE
        v.stepHint.text = hint
        v.stepHint.visibility =
            if (active && hint != null) android.view.View.VISIBLE else android.view.View.GONE

        if (active) {
            v.stepRoot.setBackgroundResource(R.drawable.card_step)
            v.stepRoot.elevation = dpi(8f).toFloat()
            v.stepRoot.setPadding(dpi(18f), dpi(16f), dpi(16f), dpi(16f))
            v.iconDisc.visibility = android.view.View.VISIBLE
            v.icon3d.setImageResource(icons3d[i])
            v.icon3d.floatLoop()
            v.stepRoot.enterRise()
        } else {
            v.stepRoot.background = null
            v.stepRoot.elevation = 0f
            v.stepRoot.setPadding(dpi(18f), dpi(8f), 0, dpi(8f))
            v.iconDisc.visibility = android.view.View.GONE
            v.icon3d.stopFloat()
        }

        // Button
        v.stepButton.visibility = if (active) android.view.View.VISIBLE else android.view.View.GONE
        if (!active) return
        when {
            i == 2 && checkDone -> styleButton(v, getString(R.string.setup_finish), R.drawable.ic_check, filled = true, enabled = true)
            i == 2 && checkRunning -> styleButton(v, getString(R.string.setup_checking), null, filled = false, enabled = false)
            i == 2 -> styleButton(v, getString(buttons[i]), R.drawable.ic_radar, filled = false, enabled = true)
            busy -> styleButton(v, getString(R.string.setup_opening), null, filled = false, enabled = false)
            else -> styleButton(v, getString(buttons[i]), R.drawable.ic_open_in_new, filled = false, enabled = true)
        }
    }

    private fun styleButton(
        v: ItemSetupStepBinding,
        text: String,
        iconRes: Int?,
        filled: Boolean,
        enabled: Boolean
    ) {
        val color = if (filled) R.color.wp_on_accent else R.color.wp_accent2
        v.stepButton.text = text
        v.stepButton.isEnabled = enabled
        v.stepButton.alpha = if (enabled) 1f else 0.55f
        v.stepButton.background =
            ContextCompat.getDrawable(this, if (filled) R.drawable.btn_accent else R.drawable.btn_accent_outline)
        v.stepButton.setTextColor(ContextCompat.getColor(this, color))
        if (iconRes != null) v.stepButton.setIconResource(iconRes) else v.stepButton.icon = null
        v.stepButton.iconTint = ContextCompat.getColorStateList(this, color)
    }

    private fun onStepButton(i: Int) {
        when (i) {
            0 -> {
                busy = true
                render()
                openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS, Settings.ACTION_SETTINGS)
            }
            1 -> {
                busy = true
                render()
                openSettings(
                    Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                    Settings.ACTION_DEVICE_INFO_SETTINGS
                )
            }
            2 -> {
                if (checkDone) finishSetup() else ensurePermissionThenCheck()
            }
        }
    }

    private fun openSettings(primary: String, fallback: String) {
        try {
            startActivity(Intent(primary))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(fallback))
            } catch (_: ActivityNotFoundException) {
                busy = false
                render()
            }
        }
    }

    private fun openAppDetails() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionThenCheck() {
        if (hasLocationPermission()) {
            runCheck()
        } else {
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun runCheck() {
        checkStarted = true
        checkRunning = true
        hint = null
        resetLines()
        render()
        lifecycleScope.launch(Dispatchers.Default) {
            val result = SelfCheck(this@SetupActivity).run { idx, text ->
                withContext(Dispatchers.Main) { completeLine(idx, text) }
            }
            withContext(Dispatchers.Main) {
                checkRunning = false
                when (result) {
                    is SelfCheck.Result.Ok -> {
                        checkDone = true
                        binding.setupRoot.announceForAccessibility(getString(R.string.check_line3))
                    }
                    is SelfCheck.Result.NotSelected -> {
                        hint = getString(R.string.setup_not_selected)
                        checkStarted = false
                        step = 1
                    }
                    is SelfCheck.Result.Failed -> {
                        hint = if (result.reason == "permission") {
                            getString(R.string.setup_perm_needed)
                        } else {
                            getString(R.string.setup_read_back_fail)
                        }
                        checkStarted = false
                    }
                }
                render()
            }
        }
    }

    private fun resetLines() {
        val defaults = listOf(
            getString(R.string.check_line1),
            getString(R.string.check_line2),
            getString(R.string.check_line3)
        )
        lineViews.forEachIndexed { i, lv ->
            lv.lineText.text = defaults[i]
            lv.lineText.setTextColor(
                ContextCompat.getColor(this, if (i == 0) R.color.wp_text_mid else R.color.wp_text_low)
            )
            lv.lineCheck.visibility = android.view.View.GONE
            lv.lineSpin.visibility = if (i == 0) android.view.View.VISIBLE else android.view.View.GONE
            lv.lineIdle.visibility = if (i == 0) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    private fun completeLine(idx: Int, text: String) {
        if (idx >= lineViews.size) return
        val lv = lineViews[idx]
        lv.lineText.text = text
        lv.lineText.setTextColor(ContextCompat.getColor(this, R.color.wp_green))
        lv.lineSpin.visibility = android.view.View.GONE
        lv.lineIdle.visibility = android.view.View.GONE
        lv.lineCheck.visibility = android.view.View.VISIBLE
        lineViews.getOrNull(idx + 1)?.let { next ->
            next.lineIdle.visibility = android.view.View.GONE
            next.lineSpin.visibility = android.view.View.VISIBLE
            next.lineText.setTextColor(ContextCompat.getColor(this, R.color.wp_text_mid))
        }
    }

    private fun finishSetup() {
        settingsStore.setSetupDone(true)
        if (fromSettings) {
            finish()
        } else {
            startActivity(Intent(this, MapActivity::class.java))
            finish()
        }
    }

    companion object {
        const val EXTRA_FROM_SETTINGS = "fromSettings"
    }
}
