package app.waypoint.data.geo

import java.io.InputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Minimal GPX reader: collects wpt / trkpt / rtept with optional <name>. */
object GpxParser {

    fun parse(input: InputStream): List<RoutePoint> {
        val points = mutableListOf<RoutePoint>()
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            .newPullParser()
        parser.setInput(input, null)

        var lat = 0.0
        var lon = 0.0
        var inPoint = false
        var name = ""
        var inName = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "wpt", "trkpt", "rtept" -> {
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: Double.NaN
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: Double.NaN
                        name = ""
                        inPoint = true
                    }
                    "name" -> if (inPoint) inName = true
                }
                XmlPullParser.TEXT -> if (inName) name = parser.text?.trim().orEmpty()
                XmlPullParser.END_TAG -> when (parser.name) {
                    "wpt", "trkpt", "rtept" -> {
                        if (inPoint && !lat.isNaN() && !lon.isNaN()) {
                            points.add(RoutePoint(lat, lon, name))
                        }
                        inPoint = false
                    }
                    "name" -> inName = false
                }
            }
            event = parser.next()
        }
        return points
    }
}
