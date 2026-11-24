package io.github.chibisueru.telegramdrive.ui.browser

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.chibisueru.telegramdrive.AppContainer
import io.github.chibisueru.telegramdrive.data.SortMode
import io.github.chibisueru.telegramdrive.data.db.FileEntity
import io.github.chibisueru.telegramdrive.data.db.FolderEntity
import io.github.chibisueru.telegramdrive.work.TransferKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface DriveItem {
    val id: String
    val name: String

    data class Folder(val folder: FolderEntity) : DriveItem {
        override val id get() = folder.id
        override val name get() = folder.name
    }

    data class File(val file: FileEntity) : DriveItem {
        override val id get() = file.id
        override val name get() = file.name
    }
}

data class Transfer(
    val id: UUID,
    val name: String,
    val upload: Boolean,
    val state: WorkInfo.State,
    val progress: Float?,
    val error: String?,
) {
    val active get() = state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class BrowserViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.repository

    val currentFolderId = MutableStateFlow<String?>(null)
    val query = MutableStateFlow("")
    val searchActive = MutableStateFlow(false)

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val settings = c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val allFolders: StateFlow<List<FolderEntity>> =
        c.dao.allFoldersFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Breadcrumb path from the root to the current folder (root itself excluded). */
    val path: StateFlow<List<FolderEntity>> = combine(currentFolderId, allFolders) { id, folders ->
        val byId = folders.associateBy { it.id }
        val result = ArrayList<FolderEntity>()
        var cursor = id?.let { byId[it] }
        while (cursor != null && result.size < 100) {
            result.add(0, cursor)
            cursor = cursor.parentId?.let { byId[it] }
        }
        result
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val items: StateFlow<List<DriveItem>?> = combine(
        currentFolderId, query.debounce(200), searchActive, c.settings.flow.map { it.sortMode },
    ) { folder, q, searching, sort -> Params(folder, if (searching) q.trim() else "", sort) }
        .flatMapLatest { p ->
            val foldersFlow = if (p.query.isNotEmpty()) c.dao.searchFolders(p.query) else c.dao.foldersIn(p.folder)
            val filesFlow = if (p.query.isNotEmpty()) c.dao.searchFiles(p.query) else c.dao.filesIn(p.folder)
            combine(foldersFlow, filesFlow) { folders, files -> sort(folders, files, p.sort) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val transfers: StateFlow<List<Transfer>> =
        WorkManager.getInstance(c.context).getWorkInfosByTagFlow(TransferKeys.TAG)
            .map { infos -> infos.map { it.toTransfer() }.sortedBy { !it.active } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private data class Params(val folder: String?, val query: String, val sort: SortMode)

    private fun sort(folders: List<FolderEntity>, files: List<FileEntity>, mode: SortMode): List<DriveItem> {
        val sortedFolders = when (mode) {
            SortMode.NEWEST -> folders.sortedByDescending { it.createdAt }
            else -> folders.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
        val sortedFiles = when (mode) {
            SortMode.NAME -> files.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            SortMode.NEWEST -> files.sortedByDescending { it.createdAt }
            SortMode.SIZE -> files.sortedByDescending { it.size }
        }
        return sortedFolders.map { DriveItem.Folder(it) } + sortedFiles.map { DriveItem.File(it) }
    }

    private fun WorkInfo.toTransfer(): Transfer {
        val name = progress.getString(TransferKeys.NAME) ?: outputData.getString(TransferKeys.NAME)
            ?: tags.firstOrNull { it.startsWith(TransferKeys.NAME_TAG_PREFIX) }?.removePrefix(TransferKeys.NAME_TAG_PREFIX)
            ?: "File"
        val error = outputData.getString(TransferKeys.ERROR)
        val p = when (state) {
            WorkInfo.State.SUCCEEDED -> 1f
            WorkInfo.State.RUNNING -> progress.getFloat(TransferKeys.PROGRESS, -1f).takeIf { it >= 0 }
            else -> null
        }
        return Transfer(id, name, TransferKeys.TAG_UPLOAD in tags, state, p, error)
    }

    fun openFolder(id: String?) {
        currentFolderId.value = id
        searchActive.value = false
        query.value = ""
    }

    fun goUp() {
        openFolder(path.value.lastOrNull()?.parentId)
    }

    fun setSearchActive(active: Boolean) {
        searchActive.value = active
        if (!active) query.value = ""
    }

    fun setGrid(grid: Boolean) = viewModelScope.launch { c.settings.setGridView(grid) }
    fun setSort(mode: SortMode) = viewModelScope.launch { c.settings.setSortMode(mode) }

    fun upload(uris: List<Uri>) {
        val folder = currentFolderId.value
        uris.forEach { repo.enqueueUpload(it, folder) }
        if (uris.isNotEmpty()) _events.trySend("Uploading ${uris.size} file${if (uris.size == 1) "" else "s"}…")
    }

    fun download(file: FileEntity) {
        repo.enqueueDownload(file)
        _events.trySend("Saving “${file.name}” to Downloads/Telegram Drive")
    }

    fun createFolder(name: String) = launchSafely { repo.createFolder(currentFolderId.value, name) }

    fun rename(item: DriveItem, name: String) = launchSafely {
        when (item) {
            is DriveItem.Folder -> repo.renameFolder(item.id, name)
            is DriveItem.File -> repo.renameFile(item.id, name)
        }
    }

    fun move(item: DriveItem, target: String?) = launchSafely {
        when (item) {
            is DriveItem.Folder -> if (!repo.moveFolder(item.id, target)) _events.send("Can't move a folder into itself")
            is DriveItem.File -> repo.moveFile(item.id, target)
        }
    }

    fun delete(item: DriveItem) = launchSafely {
        when (item) {
            is DriveItem.Folder -> repo.deleteFolder(item.id)
            is DriveItem.File -> repo.deleteFile(item.file)
        }
        _events.send("Deleted “${item.name}”")
    }

    fun cancelTransfer(id: UUID) = repo.cancelTransfer(id)
    fun clearFinishedTransfers() = repo.clearFinishedTransfers()

    private fun launchSafely(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: Exception) {
            _events.send(e.message ?: "Something went wrong")
        }
    }
}
