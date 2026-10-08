package app.waypoint.data.geo

import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class RoutePoint(val lat: Double, val lon: Double, val name: String = "")

object Geo {
    const val R_EARTH = 6_371_000.0

    fun haversine(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val p = Math.PI / 180
        val dLat = (bLat - aLat) * p
        val dLon = (bLon - aLon) * p
        val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(aLat * p) * cos(bLat * p) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R_EARTH * asin(sqrt(s))
    }

    /** Initial bearing a→b in degrees 0..360. */
    fun bearing(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
        val p = Math.PI / 180
        val y = sin((bLon - aLon) * p) * cos(bLat * p)
        val x = cos(aLat * p) * sin(bLat * p) - sin(aLat * p) * cos(bLat * p) * cos((bLon - aLon) * p)
        val deg = atan2(y, x) / p
        return (((deg % 360) + 360) % 360).toFloat()
    }

    /** `31.5204° N   74.3587° E` — three spaces between halves (design fmtCoord). */
    fun fmtCoord(lat: Double, lon: Double, decimals: Int = 4): String {
        val ns = if (lat >= 0) "N" else "S"
        val ew = if (lon >= 0) "E" else "W"
        return String.format(
            Locale.US, "%.${decimals}f° %s   %.${decimals}f° %s",
            kotlin.math.abs(lat), ns, kotlin.math.abs(lon), ew
        )
    }

    /** `850 m` / `14.2 km` */
    fun fmtDist(metres: Double): String =
        if (metres < 1000) "${metres.toInt()} m"
        else String.format(Locale.US, "%.1f km", metres / 1000)

    /** HH:MM:SS session timer. */
    fun fmtDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    /** Web-Mercator tile maths (offline cache estimates). */
    fun tileX(lon: Double, z: Int): Double = (lon + 180) / 360 * (1 shl z)
    fun tileY(lat: Double, z: Int): Double {
        val r = lat * Math.PI / 180
        return (1 - ln(tan(r) + 1 / cos(r)) / Math.PI) / 2 * (1 shl z)
    }

    /** Parse pasted coordinates like `31.52, 74.35` / `31.52 74.35` / `-31.5;74.3`. */
    fun parseCoords(text: String): RoutePoint? {
        val m = COORD_RE.find(text.trim()) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null
        return RoutePoint(lat, lon, fmtCoord(lat, lon, 4))
    }

    private val COORD_RE =
        Regex("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*[,;\\s]\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$")
}
