package app.waypoint.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favourites")
data class FavouriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lon: Double,
    val colour: Int,
    val sortOrder: Int
)

@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val pointsJson: String,
    val speedKmh: Int,
    val loop: Boolean,
    val reverse: Boolean,
    val updatedAt: Long
)

@Entity(tableName = "recents")
data class RecentEntity(
    @PrimaryKey val name: String,
    val lat: Double,
    val lon: Double,
    val sub: String,
    val usedAt: Long
)
