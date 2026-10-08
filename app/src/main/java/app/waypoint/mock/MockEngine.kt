package app.waypoint.mock

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices

/**
 * Talks to the platform: registers test providers, injects fixes, tears down.
 * Never hides the isMock flag (G7) — fixes are honest test-provider fixes.
 *
 * Every test-provider call is wrapped in try/catch(SecurityException); the mock
 * appop (not a runtime permission) is what authorises them, so the lint
 * MissingPermission check doesn't apply here.
 */
@SuppressLint("MissingPermission")
class MockEngine(private val context: Context) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fused: FusedLocationProviderClient? = try {
        LocationServices.getFusedLocationProviderClient(context)
    } catch (_: Throwable) {
        null
    }
    private var fusedMockRequested = false
    @Volatile private var fusedMockReady = false
    @Volatile private var pendingFix: Location? = null

    private fun providerNames(): List<String> {
        val names = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= 31) names.add(LocationManager.FUSED_PROVIDER)
        return names
    }

    /** True when the user picked Waypoint under Developer options → mock location app. */
    fun isMockAppSelected(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return try {
            // OPSTR_MOCK_LOCATION is @SystemApi; the literal op name is stable since M.
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                appOps.unsafeCheckOpNoThrow("android:mock_location", Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow("android:mock_location", Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    /** Registers and enables all test providers. False ⇒ Waypoint isn't the selected mock app. */
    fun register(): Boolean = try {
        providerNames().forEach { name ->
            addProvider(name)
            lm.setTestProviderEnabled(name, true)
        }
        true
    } catch (_: SecurityException) {
        unregister()
        false
    } catch (_: IllegalArgumentException) {
        // A provider name the platform refuses (e.g. fused on some OEMs) — retry without it.
        try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { name ->
                addProvider(name)
                lm.setTestProviderEnabled(name, true)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("WrongConstant")
    private fun addProvider(name: String) {
        val satellites = name == LocationManager.GPS_PROVIDER
        if (Build.VERSION.SDK_INT >= 31) {
            val props = ProviderProperties.Builder()
                .setHasSatelliteRequirement(satellites)
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .build()
            lm.addTestProvider(name, props)
        } else {
            @Suppress("DEPRECATION")
            lm.addTestProvider(
                name,
                false, satellites, false, false,
                true, true, true,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE
            )
        }
    }

    /** One honest mock fix across every provider + the fused client. */
    fun inject(
        lat: Double,
        lon: Double,
        altitude: Double,
        accuracy: Float,
        bearing: Float,
        speedMs: Float
    ) {
        providerNames().forEach { name ->
            val fix = buildFix(name, lat, lon, altitude, accuracy, bearing, speedMs)
            try {
                lm.setTestProviderLocation(name, fix)
            } catch (_: Exception) {
                // provider might have been rejected at register time — ignore
            }
        }
        pushFused(buildFix(LocationManager.GPS_PROVIDER, lat, lon, altitude, accuracy, bearing, speedMs))
    }

    private fun buildFix(
        provider: String,
        lat: Double,
        lon: Double,
        alt: Double,
        acc: Float,
        brg: Float,
        spd: Float
    ): Location = Location(provider).apply {
        latitude = lat
        longitude = lon
        altitude = alt
        accuracy = acc
        bearing = brg
        speed = spd
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        if (Build.VERSION.SDK_INT >= 26) {
            // Newer fused builds silently drop mock fixes without these.
            bearingAccuracyDegrees = 1f
            speedAccuracyMetersPerSecond = 0.5f
            verticalAccuracyMeters = 3f
        }
    }

    /**
     * GMS Fused Location Provider mock — this is what Google Maps and other
     * play-services consumers read. setMockMode() is async, so the first fix is
     * chained to its completion; later ticks push directly once mode is ready.
     */
    private fun pushFused(fix: Location) {
        val client = fused ?: run {
            Log.w(TAG, "No GMS FusedLocationProviderClient available")
            return
        }
        pendingFix = fix
        try {
            if (!fusedMockRequested) {
                fusedMockRequested = true
                client.setMockMode(true)
                    .addOnSuccessListener {
                        fusedMockReady = true
                        Log.i(TAG, "GMS setMockMode(true) OK")
                        pendingFix?.let { pushMockLocation(client, it) }
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "GMS setMockMode(true) FAILED: ${e.message}")
                    }
            } else if (fusedMockReady) {
                pushMockLocation(client, fix)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "GMS mock push threw: ${e.message}")
        }
    }

    private fun pushMockLocation(client: FusedLocationProviderClient, fix: Location) {
        client.setMockLocation(fix)
            .addOnSuccessListener { Log.i(TAG, "GMS setMockLocation OK ${fix.latitude},${fix.longitude}") }
            .addOnFailureListener { e -> Log.w(TAG, "GMS setMockLocation FAILED: ${e.message}") }
    }

    fun unregister() {
        val client = fused
        if (client != null && fusedMockRequested) {
            try {
                client.setMockMode(false)
            } catch (_: Throwable) {
            }
        }
        fusedMockRequested = false
        fusedMockReady = false
        pendingFix = null
        providerNames().forEach { name ->
            try {
                lm.setTestProviderEnabled(name, false)
            } catch (_: Exception) {
            }
            try {
                lm.removeTestProvider(name)
            } catch (_: Exception) {
            }
        }
    }

    /** Repair: also ask FLP to flush any queued fixes. */
    fun flushFused() {
        try {
            fused?.flushLocations()
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val TAG = "WpMock"
    }
}
