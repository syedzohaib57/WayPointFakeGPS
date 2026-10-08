package app.waypoint.data.repo

import android.content.Context
import android.location.Geocoder
import android.os.Build
import android.util.LruCache
import app.waypoint.BuildConfig
// key loaded from keys.properties via BuildConfig.MAPS_KEY
import app.waypoint.data.geo.Geo
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class PlaceResult(val name: String, val sub: String, val lat: Double, val lon: Double)

/**
 * Geocoding rules (policy-reviewed):
 *  - type-ahead touches the on-device Geocoder (never Nominatim)
 *  - submit prefers the Google Geocoding API when a key is configured,
 *    then the device Geocoder, then Nominatim (≥1.1 s apart, real User-Agent)
 *  - coordinates like `31.52, 74.35` resolve locally
 */
class SearchRepo(private val context: Context) {

    private val cache = LruCache<String, List<PlaceResult>>(24)
    private var lastNominatimAt = 0L
    private val googleKey: String = BuildConfig.MAPS_KEY

    suspend fun typeahead(query: String): List<PlaceResult> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        coordinate(q)?.let { return listOf(it) }
        cache.get("g:$q")?.let { return it }
        val results = geocoder(q)
        if (results.isNotEmpty()) cache.put("g:$q", results)
        return results
    }

    suspend fun submit(query: String): Pair<List<PlaceResult>, Boolean> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList<PlaceResult>() to false
        coordinate(q)?.let { return listOf(it) to false }
        cache.get("n:$q")?.let { return it to false }

        if (googleKey.isNotBlank()) {
            val google = googleGeocode(q)
            if (google.isNotEmpty()) {
                cache.put("n:$q", google)
                return google to false
            }
        }
        val local = geocoder(q)
        if (local.size >= 3) return local to false
        val remote = nominatim(q)
        return if (remote.isNotEmpty()) {
            cache.put("n:$q", remote)
            remote to true
        } else {
            local to false
        }
    }

    /** Google Geocoding API (uses the key from keys.properties). */
    private suspend fun googleGeocode(q: String): List<PlaceResult> = withContext(Dispatchers.IO) {
        try {
            val url = URL(
                "https://maps.googleapis.com/maps/api/geocode/json?address=" +
                    URLEncoder.encode(q, "UTF-8") + "&key=" + googleKey
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val root = org.json.JSONObject(body)
            if (root.optString("status") != "OK") return@withContext emptyList()
            val arr = root.getJSONArray("results")
            buildList {
                for (i in 0 until minOf(arr.length(), 6)) {
                    val o = arr.getJSONObject(i)
                    val loc = o.getJSONObject("geometry").getJSONObject("location")
                    val formatted = o.optString("formatted_address")
                    val parts = formatted.split(", ")
                    add(
                        PlaceResult(
                            parts.firstOrNull() ?: formatted,
                            parts.drop(1).take(2).joinToString(", "),
                            loc.getDouble("lat"),
                            loc.getDouble("lng")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun coordinate(q: String): PlaceResult? =
        Geo.parseCoords(q)?.let { PlaceResult(it.name, "Coordinates", it.lat, it.lon) }

    private suspend fun geocoder(q: String): List<PlaceResult> = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext emptyList()
        try {
            val geo = Geocoder(context)
            val addresses = if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { cont ->
                    geo.getFromLocationName(q, 6) { list ->
                        if (cont.isActive) cont.resume(list)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geo.getFromLocationName(q, 6)
            } ?: emptyList()
            addresses.mapNotNull { a ->
                if (!a.hasLatitude() || !a.hasLongitude()) return@mapNotNull null
                val name = a.featureName?.takeIf { it.isNotBlank() && !it.matches(Regex("^[-0-9.,° ]+$")) }
                    ?: a.thoroughfare ?: a.locality ?: a.getAddressLine(0)?.substringBefore(",")
                    ?: return@mapNotNull null
                val sub = listOfNotNull(
                    a.thoroughfare.takeIf { it != name },
                    a.subLocality,
                    a.locality.takeIf { it != name },
                    a.adminArea
                ).distinct().take(2).joinToString(", ").ifBlank { a.countryName ?: "" }
                PlaceResult(name, sub, a.latitude, a.longitude)
            }.distinctBy { it.name + it.sub }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun nominatim(q: String): List<PlaceResult> = withContext(Dispatchers.IO) {
        try {
            val wait = 1100 - (System.currentTimeMillis() - lastNominatimAt)
            if (wait > 0) delay(wait)
            lastNominatimAt = System.currentTimeMillis()

            val url = URL(
                "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&q=" +
                    URLEncoder.encode(q, "UTF-8")
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty(
                    "User-Agent",
                    "Waypoint/${BuildConfig.VERSION_NAME} (Android; mock-location tool)"
                )
                setRequestProperty("Accept", "application/json")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val arr = JSONArray(body)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val display = o.optString("display_name", "")
                    val parts = display.split(", ")
                    val name = o.optString("name").ifBlank { parts.firstOrNull() ?: continue }
                    val sub = parts.drop(1).take(2).joinToString(", ")
                    add(
                        PlaceResult(
                            name, sub,
                            o.getString("lat").toDouble(),
                            o.getString("lon").toDouble()
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
