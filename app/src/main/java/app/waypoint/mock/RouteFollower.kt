package app.waypoint.mock

import app.waypoint.data.geo.Geo
import app.waypoint.data.geo.RoutePoint

/**
 * Advances along a polyline at a real speed. Pure math — unit-tested.
 * progress is a 0..1 fraction of total length; reverse flips direction live.
 */
class RouteFollower(
    val points: List<RoutePoint>,
    var speedKmh: Int,
    var loop: Boolean,
    var reverse: Boolean
) {
    private val segLengths: DoubleArray = DoubleArray(maxOf(0, points.size - 1)) { i ->
        Geo.haversine(points[i].lat, points[i].lon, points[i + 1].lat, points[i + 1].lon)
    }
    val totalLength: Double = segLengths.sum()

    var progress: Double = if (reverse) 1.0 else 0.0
        private set

    val speedMs: Double get() = speedKmh / 3.6

    /** Returns false when a non-looping run reached its end. */
    fun advance(dtSeconds: Double): Boolean {
        if (totalLength <= 0.0 || points.size < 2) return false
        val delta = (if (reverse) -1 else 1) * speedMs * dtSeconds / totalLength
        var next = progress + delta
        if (next > 1.0 || next < 0.0) {
            if (loop) {
                next = ((next % 1.0) + 1.0) % 1.0
            } else {
                progress = next.coerceIn(0.0, 1.0)
                return false
            }
        }
        progress = next
        return true
    }

    fun position(): RoutePoint = positionAt(progress)

    fun positionAt(fraction: Double): RoutePoint {
        if (points.isEmpty()) return RoutePoint(0.0, 0.0)
        if (points.size == 1 || totalLength <= 0.0) return points.first()
        var d = fraction.coerceIn(0.0, 1.0) * totalLength
        for (i in segLengths.indices) {
            if (d <= segLengths[i] || i == segLengths.lastIndex) {
                val k = if (segLengths[i] > 0) (d / segLengths[i]).coerceIn(0.0, 1.0) else 0.0
                val a = points[i]
                val b = points[i + 1]
                return RoutePoint(
                    a.lat + (b.lat - a.lat) * k,
                    a.lon + (b.lon - a.lon) * k
                )
            }
            d -= segLengths[i]
        }
        return points.last()
    }

    /** Bearing of the current segment, honouring direction. */
    fun bearing(): Float {
        if (points.size < 2 || totalLength <= 0.0) return 0f
        var d = progress.coerceIn(0.0, 1.0) * totalLength
        var i = 0
        while (i < segLengths.lastIndex && d > segLengths[i]) {
            d -= segLengths[i]
            i++
        }
        val a = points[i]
        val b = points[i + 1]
        return if (reverse) Geo.bearing(b.lat, b.lon, a.lat, a.lon)
        else Geo.bearing(a.lat, a.lon, b.lat, b.lon)
    }
}
