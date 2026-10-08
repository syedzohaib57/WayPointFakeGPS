package app.waypoint

import android.app.Application
import app.waypoint.di.appModule
import java.io.File
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.osmdroid.config.Configuration

class WaypointApp : Application() {

    override fun onCreate() {
        super.onCreate()

        Configuration.getInstance().apply {
            load(this@WaypointApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = BuildConfig.APPLICATION_ID
            // Tiles live under filesDir so the OS never purges the offline cache.
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(filesDir, "osmdroid/tiles")
        }

        startKoin {
            androidContext(this@WaypointApp)
            modules(appModule)
        }
    }
}
