package app.waypoint.data.geo

import org.json.JSONArray
import org.json.JSONObject

/** RouteEntity.pointsJson ⇄ List<RoutePoint> via the in-platform org.json. */
object RouteCodec {

    fun toJson(points: List<RoutePoint>): String {
        val arr = JSONArray()
        points.forEach { p ->
            arr.put(JSONObject().apply {
                put("lat", p.lat)
                put("lon", p.lon)
                if (p.name.isNotEmpty()) put("name", p.name)
            })
        }
        return arr.toString()
    }

    fun fromJson(json: String): List<RoutePoint> = try {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(RoutePoint(o.getDouble("lat"), o.getDouble("lon"), o.optString("name", "")))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}
