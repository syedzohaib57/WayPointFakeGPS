package app.waypoint.ui.repair

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.waypoint.R
import app.waypoint.core.base.BaseActivity
import app.waypoint.core.session.MockPhase
import app.waypoint.core.util.floatLoop
import app.waypoint.core.util.pressFx
import app.waypoint.data.geo.Geo
import app.waypoint.databinding.ActivityRepairBinding
import app.waypoint.mock.MockEngine
import app.waypoint.mock.MockService
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class RepairActivity : BaseActivity<ActivityRepairBinding>() {

    private val lm by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private val engine by lazy { MockEngine(this) }
    private var running = false
    private var satCount = 0
    private var gnssCallback: android.location.GnssStatus.Callback? = null

    override fun inflateBinding(inflater: LayoutInflater): ActivityRepairBinding =
        ActivityRepairBinding.inflate(inflater)

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it }) run()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        padToInsets(binding.repairRoot, top = true, bottom = true)

        binding.backBtn.setOnClickListener { finish() }
        binding.notNowBtn.setOnClickListener { finish() }
        binding.repairBtn.pressFx()
        binding.repairBtn.setOnClickListener {
            if (running) return@setOnClickListener
            if (hasLocationPermission()) run() else permLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
        binding.warnIcon.floatLoop()

        val t = session.target.value
        binding.monoBox.text = Geo.fmtCoord(t.lat, t.lon)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun run() {
        running = true
        binding.warnIcon.visibility = View.GONE
        binding.repairRing.visibility = View.VISIBLE
        binding.antennaIcon.visibility = View.VISIBLE
        binding.notNowBtn.visibility = View.GONE
        binding.repairBtn.isEnabled = false
        binding.repairBtn.alpha = 0.55f
        binding.repairBtn.icon = null
        registerGnss()

        lifecycleScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val progressJob = launch {
                while (true) {
                    val f = ((SystemClock.elapsedRealtime() - t0) / 20000f).coerceAtMost(0.95f)
                    binding.repairRing.progress = f
                    binding.repairBtn.text = getString(R.string.repair_working, (f * 100).toInt())
                    delay(120)
                }
            }

            // 1 — clear mock provider (stop the service session + belt-and-braces local clear)
            binding.monoBox.text = getString(R.string.repair_line1)
            binding.monoBox.setTextColor(ContextCompat.getColor(this@RepairActivity, R.color.wp_text_low))
            if (session.phase.value != MockPhase.Idle || session.routeActive.value) {
                MockService.stop(this@RepairActivity)
                withTimeoutOrNull(4000) {
                    session.phase.first { it == MockPhase.Idle }
                }
            }
            engine.unregister()
            engine.flushFused()
            delay(600)

            // 2 — request fresh fix
            binding.monoBox.text = getString(R.string.repair_line2)
            delay(400)
            binding.monoBox.text = getString(R.string.repair_line3)

            val fix = withTimeoutOrNull(20000) { freshFix() }

            progressJob.cancel()
            unregisterGnss()
            running = false

            if (fix != null) {
                binding.repairRing.progress = 1f
                binding.monoBox.text = getString(R.string.repair_line4, satCount.coerceAtLeast(3))
                binding.monoBox.setTextColor(ContextCompat.getColor(this@RepairActivity, R.color.wp_green))
                delay(500)
                showDone()
            } else {
                // gold, never red (G5)
                binding.monoBox.text = getString(R.string.repair_timeout)
                binding.monoBox.setTextColor(ContextCompat.getColor(this@RepairActivity, R.color.wp_live))
                binding.repairRing.visibility = View.GONE
                binding.antennaIcon.visibility = View.GONE
                binding.warnIcon.visibility = View.VISIBLE
                binding.notNowBtn.visibility = View.VISIBLE
                binding.repairBtn.isEnabled = true
                binding.repairBtn.alpha = 1f
                binding.repairBtn.setText(R.string.repair_btn)
                binding.repairBtn.setIconResource(R.drawable.ic_refresh)
                binding.repairBtn.iconTint = ContextCompat.getColorStateList(this@RepairActivity, R.color.wp_on_accent)
            }
        }
    }

    private fun showDone() {
        binding.repairRing.visibility = View.GONE
        binding.antennaIcon.visibility = View.GONE
        binding.doneDisc.visibility = View.VISIBLE
        binding.repairTitle.setText(R.string.repair_done_title)
        binding.repairBody.setText(R.string.repair_done_body)
        binding.repairBtn.isEnabled = true
        binding.repairBtn.alpha = 1f
        binding.repairBtn.setText(R.string.repair_done_btn)
        binding.repairBtn.setIconResource(R.drawable.ic_check)
        binding.repairBtn.iconTint = ContextCompat.getColorStateList(this, R.color.wp_on_accent)
        binding.repairBtn.setOnClickListener {
            session.lastError.value = EVENT_REPAIRED
            finish()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun freshFix(): Location? {
        return if (Build.VERSION.SDK_INT >= 30) {
            suspendCancellableCoroutine { cont ->
                try {
                    lm.getCurrentLocation(
                        LocationManager.GPS_PROVIDER, null, mainExecutor
                    ) { loc -> if (cont.isActive) cont.resume(loc) }
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        } else {
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        try {
                            lm.removeUpdates(this)
                        } catch (_: Exception) {
                        }
                        if (cont.isActive) cont.resume(location)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) = Unit
                    override fun onProviderEnabled(p: String) = Unit
                    override fun onProviderDisabled(p: String) = Unit
                }
                try {
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation {
                    try {
                        lm.removeUpdates(listener)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

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

    private fun unregisterGnss() {
        gnssCallback?.let {
            try {
                lm.unregisterGnssStatusCallback(it)
            } catch (_: Exception) {
            }
        }
        gnssCallback = null
    }

    override fun onDestroy() {
        unregisterGnss()
        super.onDestroy()
    }

    companion object {
        const val EVENT_REPAIRED = "repaired"
    }
}
