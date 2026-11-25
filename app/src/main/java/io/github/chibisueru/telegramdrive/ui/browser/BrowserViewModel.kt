package io.github.chibisueru.telegramdrive.ui.browser

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.chibisueru.telegramdrive.AppContainer
import io.github.chibisueru.telegramdrive.data.SortMode
import io.github.chibisueru.telegramdrive.data.ViewMode
import io.github.chibisueru.telegramdrive.data.db.FileEntity
import io.github.chibisueru.telegramdrive.data.db.FolderEntity
import io.github.chibisueru.telegramdrive.data.folderSortKey
import io.github.chibisueru.telegramdrive.data.sortFiles
import io.github.chibisueru.telegramdrive.data.sortFolders
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface DriveItem {
    val id: String
    val name: String
    val starred: Boolean

    data class Folder(val folder: FolderEntity) : DriveItem {
        override val id get() = folder.id
        override val name get() = folder.name
        override val starred get() = folder.starred
    }

    data class File(val file: FileEntity) : DriveItem {
        override val id get() = file.id
        override val name get() = file.name
        override val starred get() = file.starred
    }
}

/** Views that aren't a folder. */
enum class Special(val title: String) { FAVORITES("Favorites"), RECENT("Recent"), TRASH("Trash") }

/** A snackbar message, optionally with an action such as "Undo". */
data class UiEvent(val message: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class BrowserViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.repository

    val currentFolderId = MutableStateFlow<String?>(null)
    val special = MutableStateFlow<Special?>(null)
    val query = MutableStateFlow("")
    val searchActive = MutableStateFlow(false)
    /** IDs of selected items; non-empty means selection mode. */
    val selection = MutableStateFlow<Set<String>>(emptySet())

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val settings = c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val allFolders: StateFlow<List<FolderEntity>> =
        c.dao.liveFoldersFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    private data class Params(val folder: String?, val special: Special?, val query: String, val sort: SortMode)

    /** Each folder (and Favorites, Trash, search) remembers its own sort order. */
    val sortKey: StateFlow<String> = combine(currentFolderId, special, searchActive) { folder, sp, searching ->
        when {
            searching -> "special:SEARCH"
            sp != null -> "special:${sp.name}"
            else -> folderSortKey(folder)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, folderSortKey(null))

    val currentSort: StateFlow<SortMode> = combine(sortKey, c.settings.flow) { key, s -> s.sortFor(key) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SortMode.NAME)

    val items: StateFlow<List<DriveItem>?> = combine(
        combine(currentFolderId, special) { f, s -> f to s },
        query.debounce(200), searchActive, currentSort,
    ) { (folder, sp), q, searching, sort -> Params(folder, sp, if (searching) q.trim() else "", sort) }
        .flatMapLatest { p -> source(p) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun source(p: Params): Flow<List<DriveItem>> = when {
        p.query.isNotEmpty() -> combine(c.dao.searchFolders(p.query), c.dao.searchFiles(p.query)) { fo, fi -> sort(fo, fi, p.sort) }
        p.special == Special.FAVORITES -> combine(c.dao.starredFolders(), c.dao.starredFiles()) { fo, fi -> sort(fo, fi, p.sort) }
        // Recent keeps its own order: most recently opened first.
        p.special == Special.RECENT -> c.dao.recentFiles().map { list -> list.map { DriveItem.File(it) } }
        p.special == Special.TRASH -> combine(c.dao.trashedFolders(), c.dao.trashedFiles()) { fo, fi ->
            fo.sortedByDescending { it.deletedAt }.map { DriveItem.Folder(it) } + fi.sortedByDescending { it.deletedAt }.map { DriveItem.File(it) }
        }
        else -> combine(c.dao.foldersIn(p.folder), c.dao.filesIn(p.folder)) { fo, fi -> sort(fo, fi, p.sort) }
    }

    private fun sort(folders: List<FolderEntity>, files: List<FileEntity>, mode: SortMode): List<DriveItem> =
        sortFolders(folders, mode).map { DriveItem.Folder(it) } + sortFiles(files, mode).map { DriveItem.File(it) }

    // ---- Navigation ----

    fun openFolder(id: String?) {
        currentFolderId.value = id
        special.value = null
        searchActive.value = false
        query.value = ""
        selection.value = emptySet()
    }

    fun openSpecial(s: Special) {
        special.value = s
        currentFolderId.value = null
        selection.value = emptySet()
        if (s == Special.TRASH) launchSafely { repo.purgeOldTrash() }
    }

    fun goUp() {
        if (special.value != null) openFolder(null) else openFolder(path.value.lastOrNull()?.parentId)
    }

    fun setSearchActive(active: Boolean) {
        searchActive.value = active
        if (!active) query.value = ""
    }

    fun setViewMode(mode: ViewMode) = viewModelScope.launch { c.settings.setViewMode(mode) }
    /** Sets the sort order of the folder (or view) that is open right now. */
    fun setSort(mode: SortMode) = viewModelScope.launch { c.settings.setFolderSort(sortKey.value, mode) }

    // ---- Selection ----

    fun toggleSelected(item: DriveItem) = selection.update { if (item.id in it) it - item.id else it + item.id }
    fun selectAll() { selection.value = items.value.orEmpty().map { it.id }.toSet() }
    fun clearSelection() { selection.value = emptySet() }
    fun selectedItems(): List<DriveItem> = items.value.orEmpty().filter { it.id in selection.value }

    private fun List<DriveItem>.fileIds() = filterIsInstance<DriveItem.File>().map { it.id }
    private fun List<DriveItem>.folderIds() = filterIsInstance<DriveItem.Folder>().map { it.id }
    private fun count(list: List<DriveItem>) = if (list.size == 1) "“${list[0].name}”" else "${list.size} items"

    // ---- Actions (each takes one or many items) ----

    fun upload(uris: List<Uri>) = launchSafely {
        if (uris.isEmpty()) return@launchSafely
        repo.enqueueUploads(uris, currentFolderId.value)
        send("Uploading ${uris.size} file${if (uris.size == 1) "" else "s"}. Progress is in Transfers.")
    }

    /** Uploads a whole folder (picked with the system folder picker), keeping its subfolders. */
    fun uploadFolder(tree: Uri) = launchSafely {
        val (folders, files) = repo.enqueueFolderUpload(tree, currentFolderId.value)
        send("Uploading $files file${if (files == 1) "" else "s"} in $folders folder${if (folders == 1) "" else "s"}. Progress is in Transfers.")
    }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /** Pull to refresh: checks the channel for changes made on other devices. */
    fun refresh() = viewModelScope.launch {
        _refreshing.value = true
        try {
            runCatching { c.indexSync.refreshIfChanged() }
        } finally {
            _refreshing.value = false
        }
    }

    fun download(list: List<DriveItem>) = launchSafely {
        val files = list.filterIsInstance<DriveItem.File>()
        files.forEach { repo.enqueueDownload(it.file) }
        clearSelection()
        if (files.isNotEmpty()) send("Saving ${count(files)} to Downloads/Telegram Drive")
    }

    fun createFolder(name: String) = launchSafely { repo.createFolder(currentFolderId.value, name) }

    fun rename(item: DriveItem, name: String) = launchSafely {
        when (item) {
            is DriveItem.Folder -> repo.renameFolder(item.id, name)
            is DriveItem.File -> repo.renameFile(item.id, name)
        }
    }

    fun move(list: List<DriveItem>, target: String?) = launchSafely {
        val skipped = repo.moveAll(list.fileIds(), list.folderIds(), target)
        clearSelection()
        if (skipped > 0) send("Can't move a folder into itself")
    }

    fun setStarred(list: List<DriveItem>, starred: Boolean) = launchSafely {
        repo.setStarred(list.fileIds(), list.folderIds(), starred)
        clearSelection()
        send(if (starred) "Added ${count(list)} to Favorites" else "Removed ${count(list)} from Favorites")
    }

    /** Moves items to the trash, with an Undo in the snackbar. */
    fun trash(list: List<DriveItem>) = launchSafely {
        val files = list.fileIds()
        val folders = list.folderIds()
        repo.trash(files, folders)
        clearSelection()
        _events.send(UiEvent("Moved ${count(list)} to the trash", "Undo") { launchSafely { repo.restore(files, folders) } })
    }

    fun restore(list: List<DriveItem>) = launchSafely {
        repo.restore(list.fileIds(), list.folderIds())
        clearSelection()
        send("Restored ${count(list)}")
    }

    fun deleteForever(list: List<DriveItem>) = launchSafely {
        repo.deleteForever(list.fileIds(), list.folderIds())
        clearSelection()
        send("Deleted ${count(list)} permanently")
    }

    fun emptyTrash() = launchSafely {
        repo.emptyTrash()
        send("Trash emptied")
    }

    private suspend fun send(message: String) = _events.send(UiEvent(message))

    private fun launchSafely(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: Exception) {
            send(e.message ?: "Something went wrong")
        }
    }
}
