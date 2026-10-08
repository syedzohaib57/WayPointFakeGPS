package app.waypoint.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class MapStyle(val key: String) {
    Light("light"),
    Dark("dark"),
    DarkNoLabels("darkNoLabels"),
    Voyager("voyager");

    companion object {
        fun from(key: String?): MapStyle = entries.firstOrNull { it.key == key } ?: Light
    }
}

data class Settings(
    val setupDone: Boolean = false,
    val accuracyM: Int = 5,            // 1,5,10,25,50
    val altitudeM: Int = 217,          // -50..3000
    val intervalS: Float = 1f,         // .5,1,2,5
    val jitter: Boolean = false,
    val mapStyle: MapStyle = MapStyle.Light,
    val externalControl: Boolean = false,
    val gmsHintDismissed: Boolean = false,
    val offlineArea: String = "",
    val lastLat: Double = 31.5204,
    val lastLon: Double = 74.3587,
    val lastName: String = "Lahore, Punjab",
    val lastSub: String = "Pakistan"
)

/** SharedPreferences-backed settings exposed as a StateFlow (user pattern: shared prefs, no DataStore). */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("waypoint_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings

    val value: Settings get() = _settings.value

    private fun read(): Settings = Settings(
        setupDone = prefs.getBoolean(K_SETUP, false),
        accuracyM = prefs.getInt(K_ACC, 5),
        altitudeM = prefs.getInt(K_ALT, 217),
        intervalS = prefs.getFloat(K_INT, 1f),
        jitter = prefs.getBoolean(K_JIT, false),
        mapStyle = MapStyle.from(prefs.getString(K_STYLE, MapStyle.Light.key)),
        externalControl = prefs.getBoolean(K_EXT, false),
        gmsHintDismissed = prefs.getBoolean(K_GMSHINT, false),
        offlineArea = prefs.getString(K_OFFAREA, "") ?: "",
        lastLat = Double.fromBits(prefs.getLong(K_LAT, DEFAULT_LAT.toRawBits())),
        lastLon = Double.fromBits(prefs.getLong(K_LON, DEFAULT_LON.toRawBits())),
        lastName = prefs.getString(K_NAME, "Lahore, Punjab") ?: "Lahore, Punjab",
        lastSub = prefs.getString(K_SUB, "Pakistan") ?: "Pakistan"
    )

    private fun update(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit { block() }
        _settings.value = read()
    }

    fun setSetupDone(done: Boolean) = update { putBoolean(K_SETUP, done) }
    fun setAccuracy(m: Int) = update { putInt(K_ACC, m) }
    fun setAltitude(m: Int) = update { putInt(K_ALT, m) }
    fun setInterval(s: Float) = update { putFloat(K_INT, s) }
    fun setJitter(on: Boolean) = update { putBoolean(K_JIT, on) }
    fun setMapStyle(style: MapStyle) = update { putString(K_STYLE, style.key) }
    fun setExternalControl(on: Boolean) = update { putBoolean(K_EXT, on) }
    fun setGmsHintDismissed(done: Boolean) = update { putBoolean(K_GMSHINT, done) }
    fun setOfflineArea(area: String) = update { putString(K_OFFAREA, area) }

    fun setLastTarget(lat: Double, lon: Double, name: String, sub: String) = update {
        putLong(K_LAT, lat.toRawBits())
        putLong(K_LON, lon.toRawBits())
        putString(K_NAME, name)
        putString(K_SUB, sub)
    }

    private companion object {
        const val DEFAULT_LAT = 31.5204
        const val DEFAULT_LON = 74.3587
        const val K_SETUP = "setup_done"
        const val K_ACC = "accuracy_m"
        const val K_ALT = "altitude_m"
        const val K_INT = "interval_s"
        const val K_JIT = "jitter"
        const val K_STYLE = "map_style"
        const val K_EXT = "external_control"
        const val K_GMSHINT = "gms_hint_dismissed"
        const val K_OFFAREA = "offline_area"
        const val K_LAT = "last_lat"
        const val K_LON = "last_lon"
        const val K_NAME = "last_name"
        const val K_SUB = "last_sub"
    }
}
