package app.waypoint.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [FavouriteEntity::class, RouteEntity::class, RecentEntity::class],
    version = 1,
    exportSchema = false
)
abstract class WpDb : RoomDatabase() {
    abstract fun favouriteDao(): FavouriteDao
    abstract fun routeDao(): RouteDao
    abstract fun recentDao(): RecentDao
}
