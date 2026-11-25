package io.github.chibisueru.telegramdrive.ui.viewer

import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.chibisueru.telegramdrive.AppContainer
import io.github.chibisueru.telegramdrive.data.db.FileEntity
import io.github.chibisueru.telegramdrive.player.TelegramDataSource
import io.github.chibisueru.telegramdrive.data.folderSortKey
import io.github.chibisueru.telegramdrive.data.sortFiles
import io.github.chibisueru.telegramdrive.util.FileKind
import io.github.chibisueru.telegramdrive.util.kindOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface CacheState {
    data object Idle : CacheState
    data class Loading(val progress: Float) : CacheState
    data class Ready(val file: File) : CacheState
    data class Failed(val message: String) : CacheState
}

class ViewerViewModel(private val c: AppContainer, private val fileId: String) : ViewModel() {
    val file: StateFlow<FileEntity?> = c.dao.fileFlow(fileId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        markOpened(fileId)
    }

    /** For the "Recent" view. */
    fun markOpened(id: String) = viewModelScope.launch { c.repository.markOpened(id) }

    /**
     * The images in the opened image's folder, in that folder's sort order, so the viewer can
     * swipe between them. Null until loaded (or when the opened file isn't an image).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val gallery: StateFlow<List<FileEntity>?> = file
        .map { it?.takeIf { f -> f.deletedAt == null && kindOf(f.mimeType, f.name) == FileKind.IMAGE } }
        .distinctUntilChangedBy { it?.folderId to (it == null) }
        .flatMapLatest { opened ->
            if (opened == null) flowOf(null)
            else combine(c.dao.filesIn(opened.folderId), c.settings.flow) { files, s ->
                sortFiles(files.filter { kindOf(it.mimeType, it.name) == FileKind.IMAGE }, s.sortFor(folderSortKey(opened.folderId)))
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Gets any file (e.g. the image currently on screen) as a local file, then calls [onReady]. */
    fun withLocalFile(entity: FileEntity, onReady: (File) -> Unit) = viewModelScope.launch {
        try {
            onReady(c.files.download(entity.remoteId, priority = 32, messageId = entity.messageId))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.send(e.message ?: "Download failed")
        }
    }

    private val _cache = MutableStateFlow<CacheState>(CacheState.Idle)
    val cache: StateFlow<CacheState> = _cache
    private var cacheJob: Job? = null

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Kept here (not in the composable) so playback survives rotation and fullscreen toggles. */
    private var _player: ExoPlayer? = null

    @OptIn(UnstableApi::class)
    fun player(entity: FileEntity): ExoPlayer = _player ?: ExoPlayer.Builder(c.context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(c.mediaDataSourceFactory))
        .build()
        .also { p ->
            val item = MediaItem.Builder()
                .setUri(TelegramDataSource.uriFor(entity.id))
                .setMimeType(entity.mimeType.takeIf { it.startsWith("video/") || it.startsWith("audio/") })
                .build()
            p.setMediaItem(item)
            p.prepare()
            p.playWhenReady = true
            _player = p
        }

    /** Downloads the file to the cache for viewers that need the whole file (images, PDF, text). */
    fun ensureCached(onReady: ((File) -> Unit)? = null) {
        (cache.value as? CacheState.Ready)?.let { ready ->
            if (ready.file.exists()) { onReady?.invoke(ready.file); return }
        }
        if (cacheJob?.isActive == true && onReady == null) return
        val entity = file.value ?: return
        cacheJob?.cancel()
        cacheJob = viewModelScope.launch {
            _cache.value = CacheState.Loading(0f)
            try {
                val f = c.files.download(entity.remoteId, priority = 32, messageId = entity.messageId) { _cache.value = CacheState.Loading(it) }
                _cache.value = CacheState.Ready(f)
                onReady?.invoke(f)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _cache.value = CacheState.Failed(e.message ?: "Download failed")
            }
        }
    }

    fun download(entity: FileEntity? = file.value) {
        entity ?: return
        viewModelScope.launch {
            c.repository.enqueueDownload(entity)
            _events.send("Saving to Downloads/Telegram Drive")
        }
    }

    override fun onCleared() {
        _player?.release()
        // Stop downloading what the user no longer looks at (finished files stay cached).
        val streaming = _player != null
        _player = null
        val remoteId = file.value?.remoteId
        if (remoteId != null && (streaming || cacheJob?.isActive == true)) {
            c.scope.launch { c.files.cancelDownload(remoteId) }
        }
    }
}
