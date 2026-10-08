package app.waypoint.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.waypoint.BuildConfig
import app.waypoint.R
import app.waypoint.core.base.BaseActivity
import app.waypoint.core.base.showSheet
import app.waypoint.core.util.dpi
import app.waypoint.data.geo.TileSources
import app.waypoint.data.prefs.MapStyle
import app.waypoint.data.prefs.SettingsStore
import app.waypoint.databinding.ActivitySettingsBinding
import app.waypoint.databinding.ItemOptionRowBinding
import app.waypoint.databinding.ItemSettingRowBinding
import app.waypoint.ui.setup.SetupActivity
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView

class SettingsActivity : BaseActivity<ActivitySettingsBinding>() {

    private val store: SettingsStore by inject()

    private lateinit var accuracyRow: ItemSettingRowBinding
    private lateinit var altitudeRow: ItemSettingRowBinding
    private lateinit var intervalRow: ItemSettingRowBinding
    private lateinit var jitterRow: ItemSettingRowBinding
    private lateinit var styleRow: ItemSettingRowBinding
    private lateinit var offlineRow: ItemSettingRowBinding

    private var downloading = false

    override fun inflateBinding(inflater: LayoutInflater): ActivitySettingsBinding =
        ActivitySettingsBinding.inflate(inflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        padToInsets(binding.settingsRoot, top = true, bottom = true)
        binding.backBtn.setOnClickListener { finish() }
        build()
        observe()
    }

    // ---- screen construction ----

    private fun section(title: Int) {
        val tv = TextView(this)
        androidx.core.widget.TextViewCompat.setTextAppearance(tv, 0)
        tv.setText(title)
        tv.textSize = 11f
        tv.typeface = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.inter_bold)
        tv.letterSpacing = 0.14f
        tv.isAllCaps = true
        tv.setTextColor(ContextCompat.getColor(this, R.color.wp_accent))
        tv.setPadding(dpi(16f), dpi(18f), dpi(16f), dpi(6f))
        binding.settingsContent.addView(tv)
    }

    private fun group(vararg rows: ItemSettingRowBinding) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card)
            elevation = dpi(2f).toFloat()
            clipToOutline = true
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) {
                val divider = View(this)
                divider.setBackgroundColor(ContextCompat.getColor(this, R.color.wp_hairline_06))
                card.addView(
                    divider,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                )
            }
            (r.root.parent as? android.view.ViewGroup)?.removeView(r.root)
            card.addView(r.root)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginStart = dpi(16f)
        lp.marginEnd = dpi(16f)
        binding.settingsContent.addView(card, lp)
    }

    private fun row(
        icon: Int,
        title: Int,
        sub: String? = null,
        value: String? = null,
        chevron: Boolean = false,
        toggle: Boolean = false,
        onClick: (() -> Unit)? = null
    ): ItemSettingRowBinding {
        val r = ItemSettingRowBinding.inflate(layoutInflater)
        r.rowIcon.setImageResource(icon)
        r.rowTitle.setText(title)
        if (sub != null) {
            r.rowSub.text = sub
            r.rowSub.visibility = View.VISIBLE
        }
        if (value != null) {
            r.rowValue.text = value
            r.rowValue.visibility = View.VISIBLE
        }
        if (chevron) r.rowChevron.visibility = View.VISIBLE
        if (toggle) r.rowToggle.visibility = View.VISIBLE
        if (onClick != null) r.root.setOnClickListener { onClick() }
        return r
    }

    private fun build() {
        val s = store.value

        section(R.string.sec_location)
        accuracyRow = row(
            R.drawable.ic_my_location, R.string.row_accuracy,
            value = getString(R.string.metres_fmt, s.accuracyM), chevron = true
        ) { showAccuracy() }
        altitudeRow = row(
            R.drawable.ic_terrain, R.string.row_altitude,
            value = getString(R.string.metres_fmt, s.altitudeM), chevron = true
        ) { showAltitude() }
        intervalRow = row(
            R.drawable.ic_timer, R.string.row_interval,
            value = intervalLabel(s.intervalS), chevron = true
        ) { showInterval() }
        jitterRow = row(
            R.drawable.ic_shuffle, R.string.row_jitter,
            sub = getString(R.string.row_jitter_sub), toggle = true
        )
        jitterRow.rowToggle.setCheckedSilently(s.jitter)
        jitterRow.rowToggle.onChange = { on -> store.setJitter(on) }
        jitterRow.root.setOnClickListener {
            jitterRow.rowToggle.checked = !jitterRow.rowToggle.checked
            store.setJitter(jitterRow.rowToggle.checked)
        }
        group(accuracyRow, altitudeRow, intervalRow, jitterRow)

        section(R.string.sec_map)
        styleRow = row(
            R.drawable.ic_map, R.string.row_map_style,
            value = styleLabel(s.mapStyle), chevron = true
        ) { showMapStyle() }
        offlineRow = row(
            R.drawable.ic_cloud_download, R.string.row_offline,
            sub = getString(R.string.row_offline_sub, cacheSizeMb()), chevron = true
        ) { showOffline() }
        group(styleRow, offlineRow)

        section(R.string.sec_automation)
        val taskerRow = row(R.drawable.ic_bolt, R.string.row_tasker, chevron = true) {
            showAutomation(tasker = true)
        }
        val intentRow = row(R.drawable.ic_code, R.string.row_intent, chevron = true) {
            showAutomation(tasker = false)
        }
        group(taskerRow, intentRow)

        section(R.string.sec_about)
        val detectionRow = row(R.drawable.ic_visibility, R.string.row_detection, chevron = true) {
            showText(R.string.row_detection, R.string.detect_body)
        }
        val setupRow = row(R.drawable.ic_checklist, R.string.row_setup, chevron = true) {
            startActivity(
                Intent(this, SetupActivity::class.java)
                    .putExtra(SetupActivity.EXTRA_FROM_SETTINGS, true)
            )
        }
        val privacyRow = row(
            R.drawable.ic_shield, R.string.row_privacy,
            sub = getString(R.string.row_privacy_sub), chevron = true
        ) { showText(R.string.row_privacy, R.string.privacy_body) }
        val licencesRow = row(R.drawable.ic_lock, R.string.row_licences, chevron = true) {
            showText(R.string.row_licences, R.string.licences_body)
        }
        group(detectionRow, setupRow, privacyRow, licencesRow)

        // footer
        val footer = TextView(this)
        footer.typeface =
            androidx.core.content.res.ResourcesCompat.getFont(this, R.font.dm_mono_regular)
        footer.textSize = 12f
        footer.setTextColor(ContextCompat.getColor(this, R.color.wp_text_low))
        footer.gravity = android.view.Gravity.CENTER_HORIZONTAL
        footer.text =
            getString(R.string.version_fmt, BuildConfig.VERSION_NAME)
        footer.setPadding(0, dpi(24f), 0, 0)
        binding.settingsContent.addView(
            footer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                store.settings.collect { s ->
                    accuracyRow.rowValue.text = getString(R.string.metres_fmt, s.accuracyM)
                    altitudeRow.rowValue.text = getString(R.string.metres_fmt, s.altitudeM)
                    intervalRow.rowValue.text = intervalLabel(s.intervalS)
                    jitterRow.rowToggle.setCheckedSilently(s.jitter)
                    styleRow.rowValue.text = styleLabel(s.mapStyle)
                    offlineRow.rowSub.text = getString(R.string.row_offline_sub, cacheSizeMb())
                }
            }
        }
    }

    private fun intervalLabel(v: Float): String = when (v) {
        0.5f -> getString(R.string.interval_half)
        1f -> getString(R.string.interval_one)
        else -> getString(R.string.interval_fmt, v.toInt())
    }

    private fun styleLabel(s: MapStyle): String = when (s) {
        MapStyle.Light -> getString(R.string.style_light)
        MapStyle.Dark -> getString(R.string.style_dark)
        MapStyle.DarkNoLabels -> getString(R.string.style_dark_nl)
        MapStyle.Voyager -> getString(R.string.style_voyager)
    }

    // ---- sheets ----

    private fun showOptions(
        title: Int,
        options: List<Pair<String, () -> Unit>>,
        selectedIndex: Int
    ) {
        showSheet(R.layout.sheet_options) { v, dialog ->
            v.findViewById<TextView>(R.id.sheetTitle).setText(title)
            val container = v.findViewById<LinearLayout>(R.id.optionsContainer)
            options.forEachIndexed { i, (label, apply) ->
                val o = ItemOptionRowBinding.inflate(layoutInflater, container, true)
                o.optionLabel.text = label
                o.optionRadio.setImageResource(
                    if (i == selectedIndex) R.drawable.ic_radio_on else R.drawable.ic_radio_off
                )
                o.root.setOnClickListener {
                    apply()
                    dialog.dismiss()
                }
            }
        }
    }

    private fun showAccuracy() {
        val values = listOf(1, 5, 10, 25, 50)
        showOptions(
            R.string.row_accuracy,
            values.map { v -> getString(R.string.metres_fmt, v) to { store.setAccuracy(v) } },
            values.indexOf(store.value.accuracyM)
        )
    }

    private fun showInterval() {
        val values = listOf(0.5f, 1f, 2f, 5f)
        showOptions(
            R.string.row_interval,
            values.map { v -> intervalLabel(v) to { store.setInterval(v) } },
            values.indexOf(store.value.intervalS)
        )
    }

    private fun showMapStyle() {
        val values = MapStyle.entries
        showOptions(
            R.string.row_map_style,
            values.map { v -> styleLabel(v) to { store.setMapStyle(v) } },
            values.indexOf(store.value.mapStyle)
        )
    }

    private fun showAltitude() {
        showSheet(R.layout.sheet_altitude) { v, dialog ->
            val slider = v.findViewById<SeekBar>(R.id.altSlider)
            val value = v.findViewById<TextView>(R.id.altValue)
            slider.progress = (store.value.altitudeM + 50).coerceIn(0, 3050)
            value.text = getString(R.string.metres_fmt, store.value.altitudeM)
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val alt = progress - 50
                    value.text = getString(R.string.metres_fmt, alt)
                    if (fromUser) store.setAltitude(alt)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
            v.findViewById<TextView>(R.id.altDone).setOnClickListener { dialog.dismiss() }
        }
    }

    private fun showAutomation(tasker: Boolean) {
        showSheet(R.layout.sheet_automation) { v, _ ->
            v.findViewById<TextView>(R.id.sheetTitle)
                .setText(if (tasker) R.string.row_tasker else R.string.row_intent)
            v.findViewById<TextView>(R.id.autoBody)
                .setText(if (tasker) R.string.tasker_body else R.string.intent_body)
            val example = getString(R.string.intent_example).replace("\\\\", "\\")
            v.findViewById<TextView>(R.id.autoExample).text = example
            val toggle = v.findViewById<app.waypoint.ui.widgets.WpToggle>(R.id.externalToggle)
            toggle.setCheckedSilently(store.value.externalControl)
            toggle.onChange = { on -> store.setExternalControl(on) }
            v.findViewById<TextView>(R.id.copyBtn).setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("waypoint", example))
                say(getString(R.string.copied))
            }
        }
    }

    private fun showText(title: Int, body: Int) {
        showSheet(R.layout.sheet_text) { v, _ ->
            v.findViewById<TextView>(R.id.sheetTitle).setText(title)
            v.findViewById<TextView>(R.id.sheetBody).setText(body)
        }
    }

    // ---- offline tiles ----

    private fun cacheSizeMb(): Int {
        val dir = Configuration.getInstance().osmdroidTileCache ?: return 0
        var bytes = 0L
        dir.walkTopDown().forEach { f -> if (f.isFile) bytes += f.length() }
        return (bytes / (1024 * 1024)).toInt()
    }

    private fun tileCount(): Long = try {
        SqlTileWriter().getRowCount(null)
    } catch (_: Throwable) {
        -1L
    }

    private fun showOffline() {
        showSheet(R.layout.sheet_offline) { v, dialog ->
            val summary = v.findViewById<TextView>(R.id.offlineSummary)

            fun refreshSummary() {
                val count = tileCount()
                val countText = if (count >= 0) String.format(Locale.US, "%,d", count) else "–"
                val area = store.value.offlineArea.ifBlank { "–" }
                summary.text = getString(R.string.offline_summary, cacheSizeMb(), countText, area)
            }
            refreshSummary()

            v.findViewById<TextView>(R.id.clearBtn).setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        SqlTileWriter().purgeCache()
                    } catch (_: Throwable) {
                    }
                    store.setOfflineArea("")
                    withContext(Dispatchers.Main) {
                        refreshSummary()
                        say(getString(R.string.offline_cleared))
                        dialog.dismiss()
                    }
                }
            }

            v.findViewById<TextView>(R.id.downloadBtn).setOnClickListener {
                if (downloading) return@setOnClickListener
                downloading = true
                val t = session.target.value
                val areaName = t.name.substringBefore(",").substringBefore("—").trim()
                    .ifBlank { getString(R.string.current_location) }
                say(getString(R.string.offline_downloading, areaName))
                dialog.dismiss()
                downloadArea(t.lat, t.lon, areaName)
            }
        }
    }

    private fun downloadArea(lat: Double, lon: Double, areaName: String) {
        try {
            val map = MapView(this)
            map.setTileSource(TileSources.source(store.value.mapStyle))
            val cm = CacheManager(map)
            val dLat = 0.09
            val dLon = 0.09 / kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.2)
            val box = BoundingBox(lat + dLat, lon + dLon, lat - dLat, lon - dLon)
            cm.downloadAreaAsyncNoUI(this, box, 11, 15, object : CacheManager.CacheManagerCallback {
                override fun onTaskComplete() {
                    runOnUiThread {
                        downloading = false
                        store.setOfflineArea(areaName)
                        say(getString(R.string.offline_done, cacheSizeMb()))
                    }
                }

                override fun onTaskFailed(errors: Int) {
                    runOnUiThread {
                        downloading = false
                        store.setOfflineArea(areaName)
                        say(getString(R.string.offline_failed))
                    }
                }

                override fun updateProgress(
                    progress: Int,
                    currentZoomLevel: Int,
                    zoomMin: Int,
                    zoomMax: Int
                ) = Unit

                override fun downloadStarted() = Unit
                override fun setPossibleTilesInArea(total: Int) = Unit
            })
        } catch (_: Throwable) {
            downloading = false
            say(getString(R.string.offline_failed))
        }
    }
}
