package app.waypoint.automation

import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import app.waypoint.R
import app.waypoint.data.geo.Geo
import app.waypoint.data.prefs.SettingsStore
import app.waypoint.mock.MockService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Broadcast API (PRD §6.9):
 *   am broadcast -a app.waypoint.SET --ef lat 31.5204 --ef lon 74.3587 --ei acc 5
 *   am broadcast -a app.waypoint.STOP
 *   am broadcast -a app.waypoint.ROUTE --el id <routeId>
 * Exported but inert until Settings → Automation → “Allow external control” is on.
 */
class IntentApiReceiver : BroadcastReceiver(), KoinComponent {

    private val store: SettingsStore by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (!store.value.externalControl) return
        when (intent.action) {
            ACTION_SET -> handleSet(context, intent)
            ACTION_STOP -> runCatching { MockService.stop(context) }
            ACTION_ROUTE -> handleRoute(context, intent)
        }
    }

    private fun numberExtra(intent: Intent, key: String): Double? {
        if (!intent.hasExtra(key)) return null
        intent.getDoubleExtra(key, Double.NaN).let { if (!it.isNaN()) return it }
        intent.getFloatExtra(key, Float.NaN).let { if (!it.isNaN()) return it.toDouble() }
        intent.getIntExtra(key, Int.MIN_VALUE).let { if (it != Int.MIN_VALUE) return it.toDouble() }
        intent.getStringExtra(key)?.toDoubleOrNull()?.let { return it }
        return null
    }

    private fun handleSet(context: Context, intent: Intent) {
        val lat = numberExtra(intent, "lat") ?: return
        val lon = numberExtra(intent, "lon") ?: return
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return
        numberExtra(intent, "acc")?.toInt()?.let { acc ->
            if (acc in 1..100) store.setAccuracy(acc)
        }
        val name = intent.getStringExtra("name")?.take(60) ?: Geo.fmtCoord(lat, lon)
        try {
            MockService.move(context, lat, lon, name, "")
        } catch (e: Exception) {
            // Background FGS start blocked (12+ without battery exemption) — offer a tap-to-start.
            postTrampoline(context, lat, lon, name)
        }
    }

    private fun handleRoute(context: Context, intent: Intent) {
        val id = when {
            intent.hasExtra("id") && intent.getLongExtra("id", -1L) > 0 -> intent.getLongExtra("id", -1L)
            else -> intent.getStringExtra("id")?.toLongOrNull() ?: -1L
        }
        if (id <= 0) return
        try {
            val i = Intent(context, MockService::class.java)
                .setAction(MockService.ACTION_ROUTE_PLAY)
                .putExtra(MockService.EXTRA_ROUTE_ID, id)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        } catch (_: Exception) {
        }
    }

    private fun postTrampoline(context: Context, lat: Double, lon: Double, name: String) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL, context.getString(R.string.notif_channel),
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val uri = Uri.parse("waypoint://set?lat=$lat&lon=$lon&name=${Uri.encode(name)}")
            val pi = PendingIntent.getActivity(
                context, 7,
                Intent(Intent.ACTION_VIEW, uri).setPackage(context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            nm.notify(
                42,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_location_on_fill)
                    .setContentTitle(context.getString(R.string.trampoline_title, name))
                    .setContentText(context.getString(R.string.trampoline_text))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build()
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION_SET = "app.waypoint.SET"
        const val ACTION_STOP = "app.waypoint.STOP"
        const val ACTION_ROUTE = "app.waypoint.ROUTE"
        const val CHANNEL = "control"
    }
}
