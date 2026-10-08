package app.waypoint

import app.waypoint.data.geo.Geo
import app.waypoint.data.geo.RoutePoint
import app.waypoint.mock.RouteFollower
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteFollowerTest {

    // Design sample route (Gulberg loop)
    private val pts = listOf(
        RoutePoint(31.5112, 74.3437, "Liberty Market"),
        RoutePoint(31.5133, 74.3330, "Gaddafi Stadium"),
        RoutePoint(31.5035, 74.3317, "Kalma Chowk"),
        RoutePoint(31.5075, 74.3455, "Hussain Chowk")
    )

    @Test
    fun totalLengthIsSumOfSegments() {
        val f = RouteFollower(pts, 40, loop = false, reverse = false)
        var sum = 0.0
        for (i in 0 until pts.size - 1) {
            sum += Geo.haversine(pts[i].lat, pts[i].lon, pts[i + 1].lat, pts[i + 1].lon)
        }
        assertEquals(sum, f.totalLength, 0.01)
        assertTrue(f.totalLength > 3000)
    }

    @Test
    fun advancesAtRealSpeed() {
        val f = RouteFollower(pts, 36, loop = false, reverse = false) // 10 m/s
        f.advance(10.0) // 100 m
        assertEquals(100.0 / f.totalLength, f.progress, 1e-6)
        val p = f.position()
        assertEquals(100.0, Geo.haversine(pts[0].lat, pts[0].lon, p.lat, p.lon), 2.0)
    }

    @Test
    fun finishesWithoutLoop() {
        val f = RouteFollower(pts, 3600, loop = false, reverse = false) // 1000 m/s
        var playing = true
        var guard = 0
        while (playing && guard++ < 100) playing = f.advance(1.0)
        assertFalse(playing)
        assertEquals(1.0, f.progress, 1e-9)
        val end = f.position()
        assertEquals(pts.last().lat, end.lat, 1e-9)
        assertEquals(pts.last().lon, end.lon, 1e-9)
    }

    @Test
    fun loopWraps() {
        val f = RouteFollower(pts, 3600, loop = true, reverse = false)
        repeat(10) { assertTrue(f.advance(1.0)) }
        assertTrue(f.progress in 0.0..1.0)
    }

    @Test
    fun reverseRunsBackwards() {
        val f = RouteFollower(pts, 36, loop = false, reverse = true)
        assertEquals(1.0, f.progress, 1e-9)
        f.advance(10.0)
        assertTrue(f.progress < 1.0)
        val p = f.position()
        assertEquals(100.0, Geo.haversine(pts.last().lat, pts.last().lon, p.lat, p.lon), 2.0)
    }

    @Test
    fun bearingFollowsSegment() {
        val f = RouteFollower(pts, 36, loop = false, reverse = false)
        val expected = Geo.bearing(pts[0].lat, pts[0].lon, pts[1].lat, pts[1].lon)
        assertEquals(expected, f.bearing(), 0.5f)
    }
}
