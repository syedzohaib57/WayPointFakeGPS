package app.waypoint.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.waypoint.BuildConfig
import app.waypoint.R
import app.waypoint.core.base.BaseActivity
import app.waypoint.core.base.showSheet
import app.waypoint.core.session.MockPhase
import app.waypoint.core.session.TargetPlace
import app.waypoint.core.util.dpi
import app.waypoint.core.util.pressFx
import app.waypoint.data.geo.Geo
import app.waypoint.data.geo.TileSources
import app.waypoint.data.prefs.SettingsStore
import app.waypoint.data.repo.FavouritesRepo
import app.waypoint.data.repo.RecentsRepo
import app.waypoint.databinding.ActivityMapBinding
import app.waypoint.databinding.ItemDrawerBinding
import app.waypoint.databinding.ItemQuickChipBinding
import app.waypoint.mock.MockService
import app.waypoint.ui.favourites.FavouritesActivity
import app.waypoint.ui.repair.RepairActivity
import app.waypoint.ui.route.RouteActivity
import app.waypoint.ui.search.SearchActivity
import app.waypoint.ui.settings.SettingsActivity
import app.waypoint.ui.setup.SetupActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint

class MapActivity : BaseActivity<ActivityMapBinding>() {

    private val settingsStore: SettingsStore by inject()
    private val favourites: FavouritesRepo by inject()
    private val recents: RecentsRepo by inject()

    private var userGesture = false
    private var suppressPanHandling = false
    private var satCount = 0
    private var realAccM = 12
    private var gnssCallback: Any? = null
    private var pendingStartAfterPermission = false
    private var askedNotifications = false

    private val lm by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }

    override fun inflateBinding(inflater: LayoutInflater): ActivityMapBinding =
        ActivityMapBinding.inflate(inflater)

    // ---- result launchers ----

    private val searchLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val d = res.data ?: return@registerForActivityResult
            if (res.resultCode == RESULT_OK) {
                pick(
                    d.getDoubleExtra("lat", 0.0), d.getDoubleExtra("lon", 0.0),
                    d.getStringExtra("name") ?: "", d.getStringExtra("sub") ?: ""
                )
            }
        }

    private val favouritesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val d = res.data ?: return@registerForActivityResult
            if (res.resultCode == RESULT_OK) {
                val lat = d.getDoubleExtra("lat", 0.0)
                val lon = d.getDoubleExtra("lon", 0.0)
                val name = d.getStringExtra("name") ?: ""
                val sub = d.getStringExtra("sub") ?: ""
                pick(lat, lon, name, sub)
                if (d.getStringExtra("action") == "mock" && !session.isMockingNow) {
                    startMocking()
                }
            }
        }

    private val locationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) {
                registerGnss()
                if (pendingStartAfterPermission) {
                    pendingStartAfterPermission = false
                    startMocking()
                }
            } else if (pendingStartAfterPermission) {
                pendingStartAfterPermission = false
                say(getString(R.string.setup_perm_needed))
            }
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { launchService() }

    // ---- lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        insets()
        setupMap()
        buildDrawer()
        wireClicks()
        restoreTarget()
        observe()
    }

    private fun insets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.topChrome) { v, ins ->
            val top = ins.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                topMargin = top + dpi(12f)
            }
            ins
        }
        padToInsets(binding.bottomStack, bottom = true)
        padToInsets(binding.drawer.drawerRoot, bottom = true)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMap() {
        val map = binding.mapView
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
        )
        map.isTilesScaledToDpi = true
        map.setTileSource(TileSources.source(settingsStore.value.mapStyle))
        map.controller.setZoom(ZOOM)
        map.minZoomLevel = 4.0
        map.maxZoomLevel = 19.5

        map.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) userGesture = true
            false
        }
        map.addMapListener(DelayedMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                onMapMovedByUser()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                onMapMovedByUser()
                return false
            }
        }, 350))
    }

    private fun onMapMovedByUser() {
        if (!userGesture || suppressPanHandling) return
        userGesture = false
        val live = session.isLiveNow
        if (live) {
            // live pan is a preview only — spring back to the reported position
            lifecycleScope.launch {
                delay(1600)
                if (session.isLiveNow) animateToTarget()
            }
        } else {
            val c = binding.mapView.mapCenter
            val t = TargetPlace(c.latitude, c.longitude, getString(R.string.dropped_pin), "")
            session.target.value = t
            settingsStore.setLastTarget(t.lat, t.lon, t.name, t.sub)
            renderTarget(t)
            say(getString(R.string.toast_moved_idle))
        }
    }

    private fun restoreTarget() {
        val s = settingsStore.value
        val t = TargetPlace(s.lastLat, s.lastLon, s.lastName, s.lastSub)
        if (!session.isMockingNow) session.target.value = t
        binding.mapView.controller.setCenter(GeoPoint(session.target.value.lat, session.target.value.lon))
        renderTarget(session.target.value)
        binding.attribution.text = TileSources.attribution()
    }

    private fun wireClicks() {
        binding.searchPill.setOnClickListener {
            searchLauncher.launch(Intent(this, SearchActivity::class.java))
        }
        binding.menuBtn.pressFx()
        binding.menuBtn.setOnClickListener { binding.drawerLayout.open() }
        val pos = binding.panelPosition
        pos.actionBtn.pressFx()
        pos.actionBtn.setOnClickListener {
            when (session.phase.value) {
                MockPhase.Idle -> startMocking()
                MockPhase.Mocking -> MockService.stop(this)
                else -> Unit
            }
        }
        pos.recentreBtn.setOnClickListener { recentreOnRealFix() }
    }

    // ---- drawer ----

    private data class DrawerItem(
        val icon: Int, val iconFill: Int, val label: Int, val action: () -> Unit
    )

    private fun buildDrawer() {
        val items = listOf(
            DrawerItem(R.drawable.ic_map, R.drawable.ic_map_fill, R.string.drawer_map) {
                binding.drawerLayout.close()
            },
            DrawerItem(R.drawable.ic_route, R.drawable.ic_route_fill, R.string.drawer_route) {
                binding.drawerLayout.close()
                startActivity(Intent(this, RouteActivity::class.java))
            },
            DrawerItem(R.drawable.ic_bookmark, R.drawable.ic_bookmark_fill, R.string.drawer_favs) {
                binding.drawerLayout.close()
                favouritesLauncher.launch(Intent(this, FavouritesActivity::class.java))
            },
            DrawerItem(R.drawable.ic_build, R.drawable.ic_build_fill, R.string.drawer_repair) {
                binding.drawerLayout.close()
                startActivity(Intent(this, RepairActivity::class.java))
            },
            DrawerItem(R.drawable.ic_checklist, R.drawable.ic_checklist_fill, R.string.drawer_setup) {
                binding.drawerLayout.close()
                startActivity(
                    Intent(this, SetupActivity::class.java)
                        .putExtra(SetupActivity.EXTRA_FROM_SETTINGS, true)
                )
            },
            DrawerItem(R.drawable.ic_settings, R.drawable.ic_settings_fill, R.string.drawer_settings) {
                binding.drawerLayout.close()
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        )
        items.forEachIndexed { index, item ->
            val v = ItemDrawerBinding.inflate(layoutInflater, binding.drawer.drawerItems, true)
            val selected = index == 0
            v.root.isSelected = selected
            v.itemIcon.setImageResource(if (selected) item.iconFill else item.icon)
            v.itemIcon.imageTintList = ContextCompat.getColorStateList(
                this, if (selected) R.color.wp_accent else R.color.wp_text_mid
            )
            v.itemLabel.setText(item.label)
            v.itemLabel.setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.wp_accent else R.color.wp_text_hi)
            )
            v.itemLabel.typeface = androidx.core.content.res.ResourcesCompat.getFont(
                this, if (selected) R.font.inter_semibold else R.font.inter_medium
            )
            v.root.setOnClickListener { item.action() }
        }
    }

    // ---- observers ----

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    session.phase.collect {
                        renderState()
                        if (it == MockPhase.Mocking) maybeShowGmsHint()
                    }
                }
                launch {
                    session.routeActive.collect { active ->
                        suppressPanHandling = active
                        renderState()
                    }
                }
                launch {
                    session.target.collect { t ->
                        renderTarget(t)
                        val c = binding.mapView.mapCenter
                        val d = Geo.haversine(c.latitude, c.longitude, t.lat, t.lon)
                        if (session.routeActive.value) {
                            if (d > 1.0) {
                                binding.mapView.controller.animateTo(GeoPoint(t.lat, t.lon), ZOOM, 950L)
                            }
                        } else if (d > 2.0) {
                            animateToTarget()
                        }
                    }
                }
                launch {
                    session.acquireStep.collect { step ->
                        val texts = listOf(
                            R.string.acquire_step1, R.string.acquire_step2, R.string.acquire_step3
                        )
                        binding.panelAcquiring.acquireStep.setText(texts[step.coerceIn(0, 2)])
                    }
                }
                launch {
                    session.acquireSats.collect { n ->
                        binding.panelAcquiring.acquireRing.progress = n / 12f
                        binding.panelAcquiring.acquireSats.text =
                            getString(R.string.sats_fmt, n, (n * 100 / 12))
                    }
                }
                launch {
                    session.lastError.collect { err ->
                        when (err) {
                            MockService.ERROR_NOT_SELECTED -> {
                                session.lastError.value = null
                                say(getString(R.string.toast_select_mock_app))
                                startActivity(
                                    Intent(this@MapActivity, SetupActivity::class.java)
                                        .putExtra(SetupActivity.EXTRA_FROM_SETTINGS, true)
                                )
                            }
                            MockService.EVENT_ROUTE_FINISHED -> {
                                session.lastError.value = null
                                say(getString(R.string.toast_route_done))
                            }
                            MockService.ERROR_FOREGROUND -> {
                                session.lastError.value = null
                                say(getString(R.string.setup_perm_needed))
                            }
                            app.waypoint.ui.repair.RepairActivity.EVENT_REPAIRED -> {
                                session.lastError.value = null
                                say(getString(R.string.toast_fresh_fix))
                            }
                        }
                    }
                }
                launch {
                    settingsStore.settings.collect { s ->
                        binding.mapView.setTileSource(TileSources.source(s.mapStyle))
                        binding.mapView.overlayManager.tilesOverlay.setColorFilter(
                            TileSources.tint(s.mapStyle)
                        )
                        renderReadout()
                    }
                }
                launch {
                    favourites.observe().collect { favs -> renderChips(favs.take(4)) }
                }
                launch {
                    // session timer + idle readout refresher
                    while (true) {
                        val since = session.since.value
                        if (since != null) {
                            val secs = (System.currentTimeMillis() - since) / 1000
                            binding.sessionTimer.text = Geo.fmtDuration(secs)
                        }
                        refreshRealAccuracy()
                        renderReadout()
                        delay(1000)
                    }
                }
            }
        }
    }

    private fun renderChips(favs: List<app.waypoint.data.db.FavouriteEntity>) {
        binding.chipsRow.removeAllViews()
        favs.forEach { f ->
            val chip = ItemQuickChipBinding.inflate(layoutInflater, binding.chipsRow, true)
            chip.chipLabel.text = f.name
            chip.chipDot.backgroundTintList = android.content.res.ColorStateList.valueOf(f.colour)
            chip.root.setOnClickListener { pick(f.lat, f.lon, f.name, "", pushRecent = true) }
        }
        binding.chipsScroll.visibility = if (favs.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---- state rendering ----

    private fun renderState() {
        val phase = session.phase.value
        val live = session.isLiveNow
        val pos = binding.panelPosition
        val acq = binding.panelAcquiring

        // top pill
        binding.searchPill.visibility = if (live) View.INVISIBLE else View.VISIBLE
        binding.livePill.visibility = if (live) View.VISIBLE else View.GONE
        binding.menuIcon.imageTintList = ContextCompat.getColorStateList(
            this, if (live) R.color.wp_live_lt else R.color.wp_text_hi
        )

        // pin
        binding.pin.live = live

        // panel swap at fixed 160 dp (G2)
        val acquiring = phase == MockPhase.Acquiring
        acq.acquiringPanel.visibility = if (acquiring) View.VISIBLE else View.GONE
        pos.positionPanel.visibility = if (acquiring) View.INVISIBLE else View.VISIBLE
        if (acquiring && acq.acquiringPanel.alpha < 1f) acq.acquiringPanel.alpha = 1f

        // eyebrow + readout colours
        pos.eyebrow.setText(if (live) R.string.eyebrow_reported else R.string.eyebrow_selected)
        pos.eyebrow.setTextColor(
            ContextCompat.getColor(this, if (live) R.color.wp_live_lt else R.color.wp_text_low)
        )
        pos.readoutDot.visibility = if (live) View.VISIBLE else View.GONE
        pos.readout.setTextColor(
            ContextCompat.getColor(this, if (live) R.color.wp_text_mid else R.color.wp_text_low)
        )

        // action button
        when {
            phase == MockPhase.Releasing -> styleAction(
                R.string.releasing_provider, null, R.drawable.btn_live, R.color.wp_on_live, false
            )
            live -> styleAction(
                R.string.stop_mocking, R.drawable.ic_stop_fill, R.drawable.btn_live, R.color.wp_on_live, true
            )
            else -> styleAction(
                R.string.start_mocking, R.drawable.ic_play_arrow_fill, R.drawable.btn_accent, R.color.wp_on_accent, true
            )
        }
        renderReadout()

        // drawer status line
        val vName = BuildConfig.VERSION_NAME
        binding.drawer.drawerStatus.text =
            getString(if (live) R.string.drawer_active else R.string.drawer_idle, vName)
        binding.drawer.drawerStatus.setTextColor(
            ContextCompat.getColor(this, if (live) R.color.wp_live_lt2 else R.color.wp_white_75)
        )
        binding.drawer.drawerDot.visibility = if (live) View.VISIBLE else View.GONE

        if (live) {
            binding.pin.contentDescription = getString(R.string.cd_live, session.target.value.name)
        }
    }

    private fun styleAction(text: Int, icon: Int?, bg: Int, color: Int, enabled: Boolean) {
        val b = binding.panelPosition.actionBtn
        b.setText(text)
        b.background = ContextCompat.getDrawable(this, bg)
        b.setTextColor(ContextCompat.getColor(this, color))
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.55f
        if (icon != null) b.setIconResource(icon) else b.icon = null
        b.iconTint = ContextCompat.getColorStateList(this, color)
    }

    private fun renderTarget(t: TargetPlace) {
        binding.panelPosition.coords.text = Geo.fmtCoord(t.lat, t.lon)
        binding.panelPosition.placeName.text =
            if (t.sub.isNotEmpty()) "${t.name}" else t.name
    }

    private fun renderReadout() {
        val s = settingsStore.value
        val live = session.isLiveNow
        binding.panelPosition.readout.text = if (live) {
            getString(
                R.string.readout_active,
                s.altitudeM,
                s.accuracyM,
                session.liveBearing.value.toInt()
            )
        } else {
            getString(R.string.readout_idle, satCount, realAccM)
        }
    }

    private fun animateToTarget() {
        val t = session.target.value
        suppressPanHandling = true
        binding.mapView.controller.animateTo(GeoPoint(t.lat, t.lon), ZOOM, 900L)
        binding.mapView.postDelayed({ suppressPanHandling = false }, 1000)
    }

    // ---- picks / recentre ----

    private fun pick(lat: Double, lon: Double, name: String, sub: String, pushRecent: Boolean = true) {
        val t = TargetPlace(lat, lon, name, sub)
        settingsStore.setLastTarget(lat, lon, name, sub)
        if (session.isMockingNow) {
            MockService.move(this, lat, lon, name, sub)
            say(getString(R.string.toast_moved_live))
        } else {
            session.target.value = t
            say(getString(R.string.toast_moved_idle))
        }
        if (pushRecent && name.isNotEmpty()) {
            lifecycleScope.launch { recents.push(name, lat, lon, sub) }
        }
        animateToTarget()
    }

    @SuppressLint("MissingPermission")
    private fun recentreOnRealFix() {
        if (!hasLocationPermission()) {
            locationPermLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        val last = listOf(
            LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER
        ).firstNotNullOfOrNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
        if (last == null) {
            say(getString(R.string.toast_centred))
            return
        }
        if (session.isLiveNow) {
            suppressPanHandling = true
            binding.mapView.controller.animateTo(GeoPoint(last.latitude, last.longitude), ZOOM, 900L)
            binding.mapView.postDelayed({ suppressPanHandling = false }, 1000)
            say(getString(R.string.toast_centred))
            lifecycleScope.launch {
                delay(2400)
                if (session.isLiveNow) animateToTarget()
            }
        } else {
            session.target.value = TargetPlace(
                last.latitude, last.longitude, getString(R.string.current_location), ""
            )
            settingsStore.setLastTarget(
                last.latitude, last.longitude, getString(R.string.current_location), ""
            )
            say(getString(R.string.toast_centred))
            animateToTarget()
        }
    }

    // ---- mocking ----

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startMocking() {
        if (!hasLocationPermission()) {
            pendingStartAfterPermission = true
            locationPermLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifications = true
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchService()
    }

    private fun launchService() {
        val t = session.target.value
        MockService.start(this, t.lat, t.lon, t.name, t.sub)
    }

    /**
     * Google Maps and other play-services apps read the GMS fused engine, which
     * in High-accuracy mode fuses real Wi-Fi/Bluetooth scans and can override the
     * mock. Surface a one-time hint (with a shortcut to Location settings) so the
     * user can switch to GPS-only. Honest, non-blocking, shown once.
     */
    private var gmsHintShown = false

    private fun highAccuracyLocationMode(): Boolean {
        val mode = try {
            android.provider.Settings.Secure.getInt(
                contentResolver, android.provider.Settings.Secure.LOCATION_MODE
            )
        } catch (_: Exception) {
            return false
        }
        val wifiScan = android.provider.Settings.Global.getInt(
            contentResolver, "wifi_scan_always_enabled", 0
        )
        // HIGH_ACCURACY (3) or BATTERY_SAVING (2) use network/Wi-Fi fusion.
        return mode == 3 || mode == 2 || wifiScan == 1
    }

    private fun maybeShowGmsHint() {
        if (gmsHintShown || settingsStore.value.gmsHintDismissed) return
        if (!highAccuracyLocationMode()) return
        gmsHintShown = true
        showSheetHint()
    }

    private fun showSheetHint() {
        showSheet(R.layout.sheet_gms_hint) { v, dialog ->
            v.findViewById<com.google.android.material.button.MaterialButton>(R.id.openBtn)
                .setOnClickListener {
                    settingsStore.setGmsHintDismissed(true)
                    dialog.dismiss()
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    } catch (_: Exception) {
                    }
                }
            v.findViewById<View>(R.id.dismissBtn).setOnClickListener {
                settingsStore.setGmsHintDismissed(true)
                dialog.dismiss()
            }
        }
    }

    // ---- GNSS readout ----

    @SuppressLint("MissingPermission")
    private fun registerGnss() {
        if (!hasLocationPermission() || gnssCallback != null) return
        try {
            val cb = object : android.location.GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                    satCount = status.satelliteCount
                }
            }
            @Suppress("DEPRECATION")
            lm.registerGnssStatusCallback(cb)
            gnssCallback = cb
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshRealAccuracy() {
        if (!hasLocationPermission()) return
        val last = runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
            ?: runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        if (last != null && last.hasAccuracy()) realAccM = last.accuracy.toInt().coerceAtLeast(1)
    }

    private fun unregisterGnss() {
        (gnssCallback as? android.location.GnssStatus.Callback)?.let {
            try {
                lm.unregisterGnssStatusCallback(it)
            } catch (_: Exception) {
            }
        }
        gnssCallback = null
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        registerGnss()
        renderState()
    }

    override fun onPause() {
        binding.mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        unregisterGnss()
        super.onStop()
    }

    companion object {
        const val ZOOM = 13.0
    }
}
