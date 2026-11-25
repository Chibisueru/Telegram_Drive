package io.github.chibisueru.telegramdrive.desktop.media

import io.github.chibisueru.telegramdrive.desktop.data.DriveFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Repeat { OFF, ALL, ONE }

data class MusicState(
    val queue: List<DriveFile> = emptyList(),
    val index: Int = -1,
    val playback: PlaybackState = PlaybackState(buffering = false),
    val shuffle: Boolean = false,
    val repeat: Repeat = Repeat.OFF,
    val volume: Float = 1f,
) {
    val current: DriveFile? get() = queue.getOrNull(index)
    val active get() = current != null
    /** Tag title, else the file name without its extension. */
    val title: String get() = playback.title ?: current?.name?.substringBeforeLast('.').orEmpty()
}

/**
 * The built-in music player: a queue (a folder's music) played one track after another, which
 * keeps going while you browse. The mini player and the full player screen both show [state].
 */
class Music(private val scope: CoroutineScope, private val stream: StreamServer) {
    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state
    private var engine: MediaEngine? = null
    private var follow: Job? = null
    /** Order tracks play in while shuffle is on. */
    private var shuffleOrder: List<Int> = emptyList()

    /** Plays [tracks] starting at [startId] (does nothing if that track is already playing). */
    fun play(tracks: List<DriveFile>, startId: String) {
        if (_state.value.current?.id == startId && engine != null) return
        val start = tracks.indexOfFirst { it.id == startId }.coerceAtLeast(0)
        _state.update { it.copy(queue = tracks, index = start) }
        reshuffle(start)
        startTrack(start)
    }

    private fun startTrack(index: Int) {
        engine?.close()
        follow?.cancel()
        val file = _state.value.queue.getOrNull(index) ?: return stop()
        val e = MediaEngine(stream.urlFor(file), withVideo = false, onEnded = { scope.launch { onTrackEnded() } })
        e.setVolume(_state.value.volume)
        engine = e
        _state.update { it.copy(index = index, playback = PlaybackState()) }
        e.play()
        follow = scope.launch { e.state.collectLatest { p -> _state.update { it.copy(playback = p) } } }
    }

    private fun onTrackEnded() {
        val s = _state.value
        when {
            s.repeat == Repeat.ONE -> { engine?.seek(0); engine?.play() }
            else -> nextIndex(wrap = s.repeat == Repeat.ALL)?.let(::startTrack)
        }
    }

    private fun nextIndex(wrap: Boolean): Int? {
        val s = _state.value
        if (s.queue.isEmpty()) return null
        return if (s.shuffle) {
            val pos = shuffleOrder.indexOf(s.index)
            shuffleOrder.getOrNull(pos + 1) ?: if (wrap) shuffleOrder.firstOrNull() else null
        } else {
            (s.index + 1).takeIf { it < s.queue.size } ?: if (wrap) 0 else null
        }
    }

    private fun previousIndex(): Int? {
        val s = _state.value
        return if (s.shuffle) shuffleOrder.getOrNull(shuffleOrder.indexOf(s.index) - 1)
        else (s.index - 1).takeIf { it >= 0 }
    }

    private fun reshuffle(first: Int) {
        val rest = _state.value.queue.indices.filter { it != first }.shuffled()
        shuffleOrder = listOf(first) + rest
    }

    fun togglePlay() { engine?.toggle() }
    fun next() { nextIndex(wrap = true)?.let(::startTrack) }
    /** Restarts the track if it's past 3 seconds, else goes to the previous one. */
    fun previous() {
        val e = engine ?: return
        if (_state.value.playback.positionMs > 3_000) { e.seek(0); return }
        previousIndex()?.let(::startTrack) ?: e.seek(0)
    }
    fun seekTo(ms: Long) { engine?.seek(ms) }
    fun skipTo(index: Int) = startTrack(index)
    fun toggleShuffle() {
        _state.update { it.copy(shuffle = !it.shuffle) }
        reshuffle(_state.value.index.coerceAtLeast(0))
    }
    fun cycleRepeat() = _state.update { it.copy(repeat = Repeat.entries[(it.repeat.ordinal + 1) % Repeat.entries.size]) }
    fun setVolume(v: Float) {
        _state.update { it.copy(volume = v) }
        engine?.setVolume(v)
    }

    /** Pauses the music (e.g. while a video plays). */
    fun pause() { engine?.pause() }

    fun stop() {
        engine?.close(); engine = null
        follow?.cancel()
        _state.update { MusicState(volume = it.volume, shuffle = it.shuffle, repeat = it.repeat) }
    }
}
