package app.waypoint.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import app.waypoint.data.prefs.SettingsStore
import app.waypoint.mock.MockService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Locale / Tasker action plugin (FIRE side). The EDIT side is TaskerEditActivity.
 * Wraps the same three actions as the broadcast API; gated by the same toggle.
 */
class TaskerFireReceiver : BroadcastReceiver(), KoinComponent {

    private val store: SettingsStore by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        if (!store.value.externalControl) return
        val bundle: Bundle = intent.getBundleExtra(EXTRA_BUNDLE) ?: return
        when (bundle.getString(KEY_ACTION)) {
            "set" -> {
                val lat = bundle.getDouble(KEY_LAT, Double.NaN)
                val lon = bundle.getDouble(KEY_LON, Double.NaN)
                if (!lat.isNaN() && !lon.isNaN() && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    val name = bundle.getString(KEY_NAME) ?: "Tasker"
                    runCatching { MockService.move(context, lat, lon, name, "") }
                }
            }
            "stop" -> runCatching { MockService.stop(context) }
            "route" -> {
                val id = bundle.getLong(KEY_ROUTE_ID, -1L)
                if (id > 0) runCatching {
                    val i = Intent(context, MockService::class.java)
                        .setAction(MockService.ACTION_ROUTE_PLAY)
                        .putExtra(MockService.EXTRA_ROUTE_ID, id)
                    androidx.core.content.ContextCompat.startForegroundService(context, i)
                }
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
        const val ACTION_EDIT = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
        const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
        const val EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"
        const val KEY_ACTION = "app.waypoint.action"
        const val KEY_LAT = "app.waypoint.lat"
        const val KEY_LON = "app.waypoint.lon"
        const val KEY_NAME = "app.waypoint.name"
        const val KEY_ROUTE_ID = "app.waypoint.routeId"
    }
}
