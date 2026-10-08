package app.waypoint.data.repo

import android.graphics.Color
import app.waypoint.data.db.FavouriteDao
import app.waypoint.data.db.FavouriteEntity
import app.waypoint.data.geo.GpxParser
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

class FavouritesRepo(private val dao: FavouriteDao) {

    /** Label colours cycle: accent, pink, green, gold (labels, never state — PRD §2.1). */
    private val labelColours = intArrayOf(
        Color.parseColor("#7C6CF2"),
        Color.parseColor("#EC4899"),
        Color.parseColor("#16A34A"),
        Color.parseColor("#FFB020")
    )

    fun observe(): Flow<List<FavouriteEntity>> = dao.observeAll()

    suspend fun add(name: String, lat: Double, lon: Double): FavouriteEntity {
        val count = dao.count()
        val entity = FavouriteEntity(
            name = name,
            lat = lat,
            lon = lon,
            colour = labelColours[count % labelColours.size],
            sortOrder = dao.maxOrder() + 1
        )
        return entity.copy(id = dao.insert(entity))
    }

    suspend fun rename(favourite: FavouriteEntity, newName: String) =
        dao.update(favourite.copy(name = newName))

    suspend fun remove(favourite: FavouriteEntity) = dao.delete(favourite)

    suspend fun persistOrder(ordered: List<FavouriteEntity>) =
        dao.updateAll(ordered.mapIndexed { i, f -> f.copy(sortOrder = i) })

    suspend fun exportJson(): String {
        val arr = JSONArray()
        dao.all().forEach { f ->
            arr.put(JSONObject().apply {
                put("name", f.name)
                put("lat", f.lat)
                put("lon", f.lon)
            })
        }
        return JSONObject().put("waypoint_favourites", arr).toString(2)
    }

    /** Accepts our JSON export, a bare JSON array, or GPX. Returns imported count. */
    suspend fun import(text: String): Int {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("<") -> importPoints(
                GpxParser.parse(trimmed.byteInputStream()).map { Triple(it.name, it.lat, it.lon) }
            )
            else -> {
                val arr = if (trimmed.startsWith("[")) JSONArray(trimmed)
                else JSONObject(trimmed).optJSONArray("waypoint_favourites") ?: JSONArray()
                val list = buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(Triple(o.optString("name", "Imported"), o.getDouble("lat"), o.getDouble("lon")))
                    }
                }
                importPoints(list)
            }
        }
    }

    suspend fun importStream(input: InputStream): Int =
        import(input.readBytes().decodeToString())

    private suspend fun importPoints(points: List<Triple<String, Double, Double>>): Int {
        points.forEach { (name, lat, lon) ->
            add(name.ifBlank { "Imported place" }, lat, lon)
        }
        return points.size
    }
}
