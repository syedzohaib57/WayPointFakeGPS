package app.waypoint.data.repo

import app.waypoint.data.db.RouteDao
import app.waypoint.data.db.RouteEntity
import app.waypoint.data.geo.RouteCodec
import app.waypoint.data.geo.RoutePoint
import kotlinx.coroutines.flow.Flow

class RoutesRepo(private val dao: RouteDao) {

    fun observe(): Flow<List<RouteEntity>> = dao.observeAll()

    suspend fun latest(): RouteEntity? = dao.latest()

    suspend fun byId(id: Long): RouteEntity? = dao.byId(id)

    suspend fun save(
        id: Long = 0,
        name: String,
        points: List<RoutePoint>,
        speedKmh: Int,
        loop: Boolean,
        reverse: Boolean
    ): Long = dao.upsert(
        RouteEntity(
            id = id,
            name = name,
            pointsJson = RouteCodec.toJson(points),
            speedKmh = speedKmh,
            loop = loop,
            reverse = reverse,
            updatedAt = System.currentTimeMillis()
        )
    )

    suspend fun delete(id: Long) = dao.delete(id)

    fun points(route: RouteEntity): List<RoutePoint> = RouteCodec.fromJson(route.pointsJson)
}
