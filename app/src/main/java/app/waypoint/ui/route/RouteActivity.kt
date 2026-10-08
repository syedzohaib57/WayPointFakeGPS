package app.waypoint.ui.route

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.waypoint.R
import app.waypoint.core.base.BaseActivity
import app.waypoint.core.base.showSheet
import app.waypoint.core.util.pressFx
import app.waypoint.data.geo.Geo
import app.waypoint.data.geo.GpxParser
import app.waypoint.data.geo.RouteCodec
import app.waypoint.data.geo.RoutePoint
import app.waypoint.data.geo.TileSources
import app.waypoint.data.prefs.SettingsStore
import app.waypoint.data.repo.RoutesRepo
import app.waypoint.databinding.ActivityRouteBinding
import app.waypoint.databinding.ItemSheetActionBinding
import app.waypoint.mock.MockService
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay

class RouteActivity : BaseActivity<ActivityRouteBinding>() {

    private val routesRepo: RoutesRepo by inject()
    private val settingsStore: SettingsStore by inject()

    private var routeId: Long = 0
    private var routeName: String = ""
    private var points = mutableListOf<RoutePoint>()
    private var speed = 40
    private var loop = false
    private var reverse = false
    private var editing = false

    override fun inflateBinding(inflater: LayoutInflater): ActivityRouteBinding =
        ActivityRouteBinding.inflate(inflater)

    private val gpxLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                try {
                    val imported = contentResolver.openInputStream(uri)?.use { GpxParser.parse(it) }
                        ?: emptyList()
                    if (imported.size >= 2) {
                        points = imported.toMutableList()
                        editing = false
                        renderRoute(animate = true)
                        centreOnRoute()
                        say(getString(R.string.route_gpx_imported, imported.size))
                    } else {
                        say(getString(R.string.favs_import_failed))
                    }
                } catch (_: Exception) {
                    say(getString(R.string.favs_import_failed))
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        padToInsets(binding.routeRoot, top = true, bottom = true)

        setupMap()
        wire()
        observe()

        val wantedId = intent.getLongExtra(EXTRA_ROUTE_ID, -1L)
        lifecycleScope.launch {
            val route = if (wantedId > 0) routesRepo.byId(wantedId) else routesRepo.latest()
            if (route != null) {
                routeId = route.id
                routeName = route.name
                points = routesRepo.points(route).toMutableList()
                speed = route.speedKmh
                loop = route.loop
                reverse = route.reverse
            } else {
                editing = true
            }
            renderControls()
            renderRoute(animate = true)
            centreOnRoute()
        }
    }

    private fun setupMap() {
        val map = binding.routeMap
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
        )
        map.isTilesScaledToDpi = true
        map.setTileSource(TileSources.source(settingsStore.value.mapStyle))
        map.overlayManager.tilesOverlay.setColorFilter(TileSources.tint(settingsStore.value.mapStyle))
        map.controller.setZoom(ZOOM)
        binding.routeOverlay.mapView = map
        binding.routeAttribution.text = TileSources.attribution()

        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (editing && p != null && !session.routeActive.value) {
                    points.add(RoutePoint(p.latitude, p.longitude, "P${points.size + 1}"))
                    renderRoute(animate = false)
                    renderControls()
                    return true
                }
                return false
            }

            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }))

        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                binding.routeOverlay.invalidate()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                binding.routeOverlay.invalidate()
                return false
            }
        })
    }

    private fun wire() {
        binding.backBtn.setOnClickListener { finish() }
        binding.menuBtn.setOnClickListener { showMenu() }
        binding.saveBtn.pressFx()
        binding.saveBtn.setOnClickListener { showSaveSheet() }

        binding.speedSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                speed = 5 + progress * 5
                binding.speedValue.text = getString(R.string.route_speed_fmt, speed)
                renderSummary()
                if (fromUser && session.routeActive.value) {
                    MockService.routeTune(this@RouteActivity, speed, null, null)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        binding.loopChip.setOnClickListener {
            loop = !loop
            renderControls()
            if (session.routeActive.value) MockService.routeTune(this, null, loop, null)
        }
        binding.reverseChip.setOnClickListener {
            reverse = !reverse
            renderControls()
            if (session.routeActive.value) MockService.routeTune(this, null, null, reverse)
        }

        binding.playBtn.pressFx()
        binding.playBtn.setOnClickListener {
            if (session.routeActive.value) {
                MockService.routeStop(this, restore = true)
            } else {
                if (points.size < 2) {
                    say(getString(R.string.route_need_points))
                    return@setOnClickListener
                }
                MockService.routePlay(
                    this,
                    RouteCodec.toJson(points),
                    routeName.ifBlank { getString(R.string.route_title) },
                    speed, loop, reverse
                )
            }
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    session.routeActive.collect { renderControls() }
                }
                launch {
                    session.routeProgress.collect { f ->
                        binding.routeOverlay.progressFraction = f
                        binding.livePct.text =
                            getString(R.string.route_pct_fmt, (f * 100).toInt())
                    }
                }
                launch {
                    session.lastError.collect { err ->
                        if (err == MockService.EVENT_ROUTE_FINISHED) {
                            session.lastError.value = null
                            say(getString(R.string.toast_route_done))
                        } else if (err == MockService.ERROR_NOT_SELECTED) {
                            session.lastError.value = null
                            say(getString(R.string.toast_select_mock_app))
                        }
                    }
                }
            }
        }
    }

    private fun renderRoute(animate: Boolean) {
        binding.routeOverlay.setRoute(points.toList(), animate)
        binding.tapHint.visibility =
            if (points.isEmpty() && editing) View.VISIBLE else View.GONE
        renderSummary()
    }

    private fun renderSummary() {
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += Geo.haversine(points[i].lat, points[i].lon, points[i + 1].lat, points[i + 1].lon)
        }
        val mins = if (speed > 0) ((total / (speed / 3.6)) / 60).toInt().coerceAtLeast(1) else 0
        binding.routeSummary.text =
            getString(R.string.route_summary, points.size, Geo.fmtDist(total), mins)
    }

    private fun renderControls() {
        val playing = session.routeActive.value
        binding.liveChip.visibility = if (playing) View.VISIBLE else View.GONE
        binding.routeOverlay.playing = playing

        binding.speedSlider.progress = ((speed - 5) / 5).coerceIn(0, 23)
        binding.speedValue.text = getString(R.string.route_speed_fmt, speed)

        renderChip(binding.loopChip, binding.loopIcon, binding.loopLabel, loop)
        renderChip(binding.reverseChip, binding.reverseIcon, binding.reverseLabel, reverse)

        if (playing) {
            binding.playBtn.setText(R.string.route_stop)
            binding.playBtn.background = ContextCompat.getDrawable(this, R.drawable.btn_live)
            binding.playBtn.setTextColor(ContextCompat.getColor(this, R.color.wp_on_live))
            binding.playBtn.setIconResource(R.drawable.ic_stop_fill)
            binding.playBtn.iconTint = ContextCompat.getColorStateList(this, R.color.wp_on_live)
        } else {
            binding.playBtn.setText(R.string.route_play)
            binding.playBtn.background = ContextCompat.getDrawable(this, R.drawable.btn_accent)
            binding.playBtn.setTextColor(ContextCompat.getColor(this, R.color.wp_on_accent))
            binding.playBtn.setIconResource(R.drawable.ic_play_arrow_fill)
            binding.playBtn.iconTint = ContextCompat.getColorStateList(this, R.color.wp_on_accent)
        }
        renderSummary()
    }

    private fun renderChip(
        chip: android.widget.LinearLayout,
        icon: android.widget.ImageView,
        label: android.widget.TextView,
        on: Boolean
    ) {
        chip.isSelected = on
        val color = ContextCompat.getColorStateList(
            this, if (on) R.color.wp_accent else R.color.wp_text_mid
        )
        icon.imageTintList = color
        label.setTextColor(color)
    }

    private fun centreOnRoute() {
        if (points.isEmpty()) {
            val t = session.target.value
            binding.routeMap.controller.setCenter(GeoPoint(t.lat, t.lon))
            return
        }
        val lat = points.sumOf { it.lat } / points.size
        val lon = points.sumOf { it.lon } / points.size
        binding.routeMap.controller.setCenter(GeoPoint(lat, lon))
        binding.routeMap.controller.setZoom(ZOOM)
    }

    private fun showMenu() {
        showSheet(R.layout.sheet_fav_actions) { v, dialog ->
            v.findViewById<android.widget.TextView>(R.id.sheetTitle).text =
                routeName.ifBlank { getString(R.string.route_title) }
            val container = v.findViewById<android.widget.LinearLayout>(R.id.actionsContainer)

            fun action(icon: Int, label: String, run: () -> Unit) {
                val row = ItemSheetActionBinding.inflate(layoutInflater, container, true)
                row.actionIcon.setImageResource(icon)
                row.actionLabel.text = label
                row.root.setOnClickListener {
                    dialog.dismiss()
                    run()
                }
            }

            action(
                R.drawable.ic_add_location,
                getString(if (editing) R.string.route_menu_stop_add else R.string.route_menu_add)
            ) {
                editing = !editing
                renderRoute(animate = false)
            }
            action(R.drawable.ic_file_upload, getString(R.string.route_menu_import)) {
                gpxLauncher.launch(
                    arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream")
                )
            }
            action(R.drawable.ic_delete, getString(R.string.route_menu_clear)) {
                points.clear()
                editing = true
                renderRoute(animate = false)
                say(getString(R.string.route_cleared))
            }
            if (routeId > 0) {
                action(R.drawable.ic_delete, getString(R.string.route_menu_delete)) {
                    lifecycleScope.launch {
                        routesRepo.delete(routeId)
                        routeId = 0
                        routeName = ""
                        say(getString(R.string.route_deleted))
                    }
                }
            }
        }
    }

    private fun showSaveSheet() {
        if (points.size < 2) {
            say(getString(R.string.route_need_points))
            return
        }
        showSheet(R.layout.sheet_save_route) { v, dialog ->
            val field = v.findViewById<android.widget.EditText>(R.id.routeNameField)
            if (routeName.isNotBlank()) field.setText(routeName)
            v.findViewById<android.widget.TextView>(R.id.saveRouteBtn).setOnClickListener {
                val name = field.text?.toString()?.trim().orEmpty()
                    .ifBlank { getString(R.string.route_default_name) }
                lifecycleScope.launch {
                    routeId = routesRepo.save(routeId, name, points.toList(), speed, loop, reverse)
                    routeName = name
                    say(getString(R.string.route_saved))
                }
                dialog.dismiss()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.routeMap.onResume()
    }

    override fun onPause() {
        binding.routeMap.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        // Leaving the screen stops route playback (PRD §4) and restores the pre-play state.
        if (isFinishing && session.routeActive.value) {
            MockService.routeStop(this, restore = true)
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ROUTE_ID = "routeId"
        const val ZOOM = 15.0
    }
}
