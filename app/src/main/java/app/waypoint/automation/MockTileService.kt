package app.waypoint.automation

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.waypoint.core.session.SessionState
import app.waypoint.data.prefs.SettingsStore
import app.waypoint.mock.MockService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Quick Settings tile: toggle mocking at the last target. */
class MockTileService : TileService(), KoinComponent {

    private val session: SessionState by inject()
    private val store: SettingsStore by inject()

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val live = session.isLiveNow
        tile.state = if (live) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (live) session.target.value.name else store.value.lastName
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (session.isLiveNow) {
            runCatching { MockService.stop(this) }
        } else {
            val s = store.value
            try {
                MockService.start(this, s.lastLat, s.lastLon, s.lastName, s.lastSub)
            } catch (_: Exception) {
                // FGS start denied from the tile — open the app instead.
                openApp()
            }
        }
        refresh()
    }

    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(
                        this, 9, launch,
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            } else {
                startActivityAndCollapse(launch)
            }
        } catch (_: Exception) {
        }
    }
}
