package io.github.chibisueru.telegramdrive

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import io.github.chibisueru.telegramdrive.backup.CameraBackup
import io.github.chibisueru.telegramdrive.data.DriveRepository
import io.github.chibisueru.telegramdrive.data.IndexSync
import io.github.chibisueru.telegramdrive.data.SettingsStore
import io.github.chibisueru.telegramdrive.data.db.AppDatabase
import io.github.chibisueru.telegramdrive.player.TelegramDataSource
import io.github.chibisueru.telegramdrive.telegram.Telegram
import io.github.chibisueru.telegramdrive.security.AppLock
import io.github.chibisueru.telegramdrive.telegram.TgFiles
import io.github.chibisueru.telegramdrive.transfer.NetworkMonitor
import io.github.chibisueru.telegramdrive.transfer.TransferManager
import io.github.chibisueru.telegramdrive.transfer.TransferNotifications
import io.github.chibisueru.telegramdrive.transfer.TransferQueue
import io.github.chibisueru.telegramdrive.transfer.TransferRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
    val db: AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "drive.db")
        .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
        .fallbackToDestructiveMigration(dropAllTables = true) // only for very old builds; the index is restored from Telegram
        .build()
    val dao = db.dao()
    val network = NetworkMonitor(context)
    val telegram = Telegram(context, scope)
    val files = TgFiles(telegram)
    val indexSync = IndexSync(context, scope, dao, telegram, files, settings)
    val transferRunner = TransferRunner(context, dao, files, settings, indexSync)
    val transfers = TransferManager(context, scope, db.transfers(), transferRunner)
    val transferQueue = TransferQueue(scope, transfers, transferRunner, settings, network, TransferNotifications(context))
    val repository = DriveRepository(context, dao, files, settings, indexSync, transfers)
    val appLock = AppLock(scope, settings)
    /** The built-in music player (playback runs in MusicService). */
    val music by lazy { io.github.chibisueru.telegramdrive.player.Music(context) }
    @Volatile private var currentChatId = 0L

    @get:OptIn(UnstableApi::class)
    val mediaDataSourceFactory: TelegramDataSource.Factory by lazy { TelegramDataSource.Factory(dao, telegram, files) }

    init {
        // Reconnect with the saved Telegram session as soon as the process starts
        // (also when WorkManager starts it for a background transfer).
        // TgFiles looks drive files up through their message in the drive channel.
        scope.launch { settings.flow.collect { s -> currentChatId = s.chatId } }
        files.chatId = { currentChatId }
        scope.launch {
            val s = settings.current()
            if (s.isConfigured) {
                telegram.resume(s.apiId, s.apiHash)
                CameraBackup.schedule(context, s)
                runCatching { repository.purgeOldTrash() }
            }
        }
    }
}
