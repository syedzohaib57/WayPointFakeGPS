package app.waypoint.di

import androidx.room.Room
import app.waypoint.core.session.SessionState
import app.waypoint.data.db.WpDb
import app.waypoint.data.prefs.SettingsStore
import app.waypoint.data.repo.FavouritesRepo
import app.waypoint.data.repo.RecentsRepo
import app.waypoint.data.repo.RoutesRepo
import app.waypoint.data.repo.SearchRepo
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single { SessionState() }
    single { SettingsStore(androidContext()) }
    single {
        Room.databaseBuilder(androidContext(), WpDb::class.java, "waypoint.db").build()
    }
    single { get<WpDb>().favouriteDao() }
    single { get<WpDb>().routeDao() }
    single { get<WpDb>().recentDao() }
    single { FavouritesRepo(get()) }
    single { RoutesRepo(get()) }
    single { RecentsRepo(get()) }
    single { SearchRepo(androidContext()) }
}
