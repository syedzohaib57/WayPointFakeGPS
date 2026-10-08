package app.waypoint.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FavouriteDao {
    @Query("SELECT * FROM favourites ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<FavouriteEntity>>

    @Query("SELECT * FROM favourites ORDER BY sortOrder ASC, id ASC")
    suspend fun all(): List<FavouriteEntity>

    @Query("SELECT COUNT(*) FROM favourites")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM favourites")
    suspend fun maxOrder(): Int

    @Insert
    suspend fun insert(favourite: FavouriteEntity): Long

    @Update
    suspend fun update(favourite: FavouriteEntity)

    @Update
    suspend fun updateAll(favourites: List<FavouriteEntity>)

    @Delete
    suspend fun delete(favourite: FavouriteEntity)
}

@Dao
interface RouteDao {
    @Query("SELECT * FROM routes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes WHERE id = :id")
    suspend fun byId(id: Long): RouteEntity?

    @Query("SELECT * FROM routes ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(): RouteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(route: RouteEntity): Long

    @Query("DELETE FROM routes WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface RecentDao {
    @Query("SELECT * FROM recents ORDER BY usedAt DESC LIMIT 3")
    fun observeTop(): Flow<List<RecentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(recent: RecentEntity)

    @Query("DELETE FROM recents WHERE name NOT IN (SELECT name FROM recents ORDER BY usedAt DESC LIMIT 3)")
    suspend fun trim()
}
