package app.waypoint.automation

import android.app.Activity
import android.os.Bundle
import app.waypoint.data.geo.Geo
import app.waypoint.mock.MockService
import app.waypoint.ui.route.RouteActivity
import org.koin.core.component.KoinComponent

/**
 * Headless handler for waypoint:// deep links:
 *   waypoint://set?lat=…&lon=…&name=…
 *   waypoint://stop
 *   waypoint://route/{id}
 */
class RouterActivity : Activity(), KoinComponent {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (data != null && data.scheme == "waypoint") {
            when (data.host) {
                "set" -> {
                    val lat = data.getQueryParameter("lat")?.toDoubleOrNull()
                    val lon = data.getQueryParameter("lon")?.toDoubleOrNull()
                    if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                        val name = data.getQueryParameter("name")?.take(60) ?: Geo.fmtCoord(lat, lon)
                        runCatching { MockService.move(this, lat, lon, name, "") }
                    }
                }
                "stop" -> runCatching { MockService.stop(this) }
                "route" -> {
                    val id = data.lastPathSegment?.toLongOrNull()
                    if (id != null && id > 0) {
                        startActivity(
                            android.content.Intent(this, RouteActivity::class.java)
                                .putExtra(RouteActivity.EXTRA_ROUTE_ID, id)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        runCatching {
                            val i = android.content.Intent(this, MockService::class.java)
                                .setAction(MockService.ACTION_ROUTE_PLAY)
                                .putExtra(MockService.EXTRA_ROUTE_ID, id)
                            androidx.core.content.ContextCompat.startForegroundService(this, i)
                        }
                    }
                }
            }
        }
        finish()
    }
}
