package app.waypoint.mock

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import app.waypoint.data.geo.Geo
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Setup step 3: register a test provider, inject one fix (matching the last real
 * fix so nothing visibly moves), read it back, clean up. Lines appear ≥700 ms apart.
 */
class SelfCheck(private val context: Context) {

    sealed class Result {
        data class Ok(val lines: List<String>) : Result()
        object NotSelected : Result()
        data class Failed(val reason: String) : Result()
    }

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val engine = MockEngine(context)

    /** onLine(index, text) fires as each line completes (0-based). */
    @SuppressLint("MissingPermission")
    suspend fun run(onLine: suspend (Int, String) -> Unit): Result {
        if (!engine.isMockAppSelected()) return Result.NotSelected

        val t0 = SystemClock.elapsedRealtime()

        // Line 1 — provider registered
        if (!engine.register()) return Result.NotSelected
        try {
            pace(t0, 700)
            onLine(0, "Provider registered")

            // Line 2 — inject the current (or canned) fix
            val base = lastRealFix()
            val lat = base?.latitude ?: 31.5204
            val lon = base?.longitude ?: 74.3587
            engine.inject(lat, lon, base?.altitude ?: 217.0, 5f, 0f, 0f)
            pace(t0, 1400)
            onLine(1, "Test fix injected · ${Geo.fmtCoord(lat, lon)}")

            // Line 3 — read back
            engine.inject(lat, lon, base?.altitude ?: 217.0, 5f, 0f, 0f)
            val read = withTimeoutOrNull(6000) { readBack() }
            pace(t0, 2100)
            val matches = read != null &&
                Geo.haversine(read.latitude, read.longitude, lat, lon) < 150.0
            return if (matches) {
                onLine(2, "Read back OK · fused matches mock")
                Result.Ok(emptyList())
            } else {
                Result.Failed("read_back")
            }
        } catch (se: SecurityException) {
            return Result.Failed("permission")
        } finally {
            engine.unregister()
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastRealFix(): Location? = try {
        listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).firstNotNullOfOrNull { p ->
            try {
                lm.getLastKnownLocation(p)
            } catch (_: Exception) {
                null
            }
        }
    } catch (_: SecurityException) {
        null
    }

    @SuppressLint("MissingPermission")
    private suspend fun readBack(): Location? {
        // Prefer an active read so we see the just-injected fix.
        val provider = LocationManager.GPS_PROVIDER
        return if (Build.VERSION.SDK_INT >= 30) {
            suspendCancellableCoroutine { cont ->
                try {
                    lm.getCurrentLocation(provider, null, context.mainExecutor) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            } ?: lm.getLastKnownLocation(provider)
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
                    override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit
                    override fun onProviderEnabled(p: String) = Unit
                    override fun onProviderDisabled(p: String) = Unit
                }
                try {
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation {
                    try {
                        lm.removeUpdates(listener)
                    } catch (_: Exception) {
                    }
                }
            } ?: lm.getLastKnownLocation(provider)
        }
    }

    private suspend fun pace(t0: Long, target: Long) {
        val left = target - (SystemClock.elapsedRealtime() - t0)
        if (left > 0) delay(left)
    }
}
