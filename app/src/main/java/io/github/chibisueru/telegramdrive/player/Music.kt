package io.github.chibisueru.telegramdrive.player

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.chibisueru.telegramdrive.data.db.FileEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One entry of the play queue. */
data class QueueEntry(val fileId: String, val fileName: String)

/** Everything the music screens show. */
data class MusicState(
    val queue: List<QueueEntry> = emptyList(),
    val index: Int = -1,
    /** From the file's tags, falling back to its name. */
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val artwork: Bitmap? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val error: String? = null,
) {
    val current: QueueEntry? get() = queue.getOrNull(index)
    val active: Boolean get() = current != null
}

/**
 * The app's handle on [MusicService]: starts tracks, exposes [state] for the player screen and
 * the mini player. All Media3 calls happen on the main thread.
 */
class Music(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var connecting: Job? = null
    private var ticker: Job? = null
    private var artworkFor: ByteArray? = null
    private var artworkBitmap: Bitmap? = null

    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state

    private suspend fun connect(): MediaController {
        controller?.let { if (it.isConnected) return it }
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val c = suspendCancellableCoroutine { cont ->
            future.addListener({
                try { cont.resume(future.get()) } catch (e: Exception) { cont.resumeWithException(e) }
            }, ContextCompat.getMainExecutor(context))
            cont.invokeOnCancellation { MediaController.releaseFuture(future) }
        }
        c.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish()
            override fun onPlayerError(error: PlaybackException) {
                _state.value = _state.value.copy(error = error.message ?: "Can't play this file")
            }
        })
        controller = c
        publish()
        return c
    }

    /** Reconnects to music that's already playing (e.g. after the app was reopened). */
    fun attach() {
        if (controller?.isConnected == true || connecting?.isActive == true) return
        connecting = scope.launch { runCatching { connect() } }
    }

    /** Plays [tracks] (a folder's music, in its sort order), starting at [startId]. */
    fun play(tracks: List<FileEntity>, startId: String) = scope.launch {
        val c = runCatching { connect() }.getOrElse { _state.value = MusicState(error = it.message); return@launch }
        val items = tracks.map { f ->
            MediaItem.Builder()
                .setMediaId(f.id)
                .setUri(TelegramDataSource.uriFor(f.id))
                // Only a fallback name: the title, artist, album and cover come from the file's tags.
                .setMediaMetadata(MediaMetadata.Builder().setDisplayTitle(f.name.substringBeforeLast('.')).build())
                .build()
        }
        val start = tracks.indexOfFirst { it.id == startId }.coerceAtLeast(0)
        _state.value = _state.value.copy(error = null)
        c.setMediaItems(items, start, 0)
        c.prepare()
        c.play()
    }

    fun togglePlay() = withController { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0, 0); it.play() } }
    fun next() = withController { it.seekToNextMediaItem() }
    /** Restarts the track if it's past the first few seconds, else goes to the previous one. */
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun skipTo(index: Int) = withController { it.seekTo(index, 0); it.play() }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    /** Stops the music and closes the notification. */
    fun stop() = withController {
        it.stop()
        it.clearMediaItems()
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null && c.isConnected) block(c).also { publish() } else scope.launch { runCatching { block(connect()); publish() } }
    }

    private fun publish() {
        val c = controller ?: return
        val queue = (0 until c.mediaItemCount).map { i ->
            val item = c.getMediaItemAt(i)
            QueueEntry(item.mediaId, item.mediaMetadata.displayTitle?.toString() ?: "")
        }
        val meta = c.mediaMetadata
        val art = meta.artworkData
        if (!art.contentEquals(artworkFor)) {
            artworkFor = art
            artworkBitmap = art?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        }
        _state.value = _state.value.copy(
            queue = queue,
            index = if (queue.isEmpty()) -1 else c.currentMediaItemIndex,
            title = meta.title?.toString() ?: meta.displayTitle?.toString() ?: queue.getOrNull(c.currentMediaItemIndex)?.fileName.orEmpty(),
            artist = (meta.artist ?: meta.albumArtist)?.toString(),
            album = meta.albumTitle?.toString(),
            artwork = artworkBitmap,
            playing = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            repeatMode = c.repeatMode,
            shuffle = c.shuffleModeEnabled,
        )
        // Move the seek bar while playing.
        if (c.isPlaying && ticker?.isActive != true) {
            ticker = scope.launch {
                while (isActive && controller?.isPlaying == true) {
                    controller?.let { _state.value = _state.value.copy(positionMs = it.currentPosition.coerceAtLeast(0)) }
                    delay(500)
                }
            }
        }
    }
}
