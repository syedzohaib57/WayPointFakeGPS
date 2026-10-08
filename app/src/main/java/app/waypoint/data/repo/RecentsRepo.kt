package app.waypoint.data.repo

import app.waypoint.data.db.RecentDao
import app.waypoint.data.db.RecentEntity
import kotlinx.coroutines.flow.Flow

class RecentsRepo(private val dao: RecentDao) {

    fun observe(): Flow<List<RecentEntity>> = dao.observeTop()

    suspend fun push(name: String, lat: Double, lon: Double, sub: String) {
        dao.upsert(RecentEntity(name, lat, lon, sub, System.currentTimeMillis()))
        dao.trim()
    }
}
