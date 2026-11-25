package io.github.chibisueru.telegramdrive.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.MediaMetadata
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import io.github.chibisueru.telegramdrive.R
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.chibisueru.telegramdrive.MainActivity
import io.github.chibisueru.telegramdrive.container

/**
 * Plays music from the drive in the background, streamed straight from Telegram. Media3 shows
 * the media notification and lock screen controls; the app talks to it through a MediaController.
 */
@OptIn(UnstableApi::class)
class MusicService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(applicationContext.container.mediaDataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_MUSIC, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Tracks without tags show their file name in the notification and on the lock screen.
        setMediaNotificationProvider(object : DefaultMediaNotificationProvider(this) {
            override fun getNotificationContentTitle(metadata: MediaMetadata): CharSequence? = metadata.title ?: metadata.displayTitle
            override fun getNotificationContentText(metadata: MediaMetadata): CharSequence? =
                metadata.artist ?: metadata.albumArtist ?: metadata.albumTitle
        }.apply { setSmallIcon(R.drawable.ic_stat_cloud) })
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                // Controllers don't pass URIs along; rebuild each track's stream address from its ID.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.map { it.buildUpon().setUri(TelegramDataSource.uriFor(it.mediaId)).build() }.toMutableList()
                )
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away stops the music unless it's playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
