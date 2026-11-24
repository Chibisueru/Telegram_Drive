package io.github.chibisueru.telegramdrive

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.room.Room
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import io.github.chibisueru.telegramdrive.data.DriveRepository
import io.github.chibisueru.telegramdrive.data.IndexSync
import io.github.chibisueru.telegramdrive.data.SettingsStore
import io.github.chibisueru.telegramdrive.data.db.AppDatabase
import io.github.chibisueru.telegramdrive.player.TelegramDataSource
import io.github.chibisueru.telegramdrive.telegram.BotApi
import io.github.chibisueru.telegramdrive.telegram.TelegramFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class TgDriveApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(TRANSFER_CHANNEL, "Uploads & downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(AnimatedImageDecoder.Factory()) }
            .build()

    companion object {
        const val TRANSFER_CHANNEL = "transfers"
    }
}

val Context.container: AppContainer get() = (applicationContext as TgDriveApp).container

/** Hand-rolled dependency container: one instance of each service for the whole process. */
class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context)
    val db: AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "drive.db").build()
    val dao = db.dao()
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()
    val bot = BotApi(http) { settings.current().token }
    val files = TelegramFiles(context, bot, dao, http)
    val indexSync = IndexSync(scope, dao, bot, files, settings)
    val repository = DriveRepository(context, dao, bot, files, settings, indexSync)

    /** Disk cache for streamed audio/video, so seeking back doesn't re-download. */
    @get:OptIn(UnstableApi::class)
    val mediaCache: SimpleCache by lazy {
        SimpleCache(
            File(context.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(1024L * 1024 * 1024),
            StandaloneDatabaseProvider(context),
        )
    }

    @get:OptIn(UnstableApi::class)
    val mediaDataSourceFactory: CacheDataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(mediaCache)
            .setUpstreamDataSourceFactory(TelegramDataSource.Factory(dao, files))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
