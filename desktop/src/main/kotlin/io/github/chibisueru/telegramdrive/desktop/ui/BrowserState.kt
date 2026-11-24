package io.github.chibisueru.telegramdrive.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.chibisueru.telegramdrive.desktop.AppContainer
import io.github.chibisueru.telegramdrive.desktop.data.AppSettings
import io.github.chibisueru.telegramdrive.desktop.data.DriveFile
import io.github.chibisueru.telegramdrive.desktop.data.DriveIndex
import io.github.chibisueru.telegramdrive.desktop.data.Folder
import io.github.chibisueru.telegramdrive.desktop.data.SortMode
import io.github.chibisueru.telegramdrive.desktop.data.folderSortKey
import io.github.chibisueru.telegramdrive.desktop.data.sortFiles
import io.github.chibisueru.telegramdrive.desktop.data.sortFolders
import kotlinx.coroutines.launch

sealed interface DriveItem {
    val id: String
    val name: String
    val starred: Boolean

    data class Dir(val folder: Folder) : DriveItem {
        override val id get() = folder.id
        override val name get() = folder.name
        override val starred get() = folder.starred
    }

    data class Doc(val file: DriveFile) : DriveItem {
        override val id get() = file.id
        override val name get() = file.name
        override val starred get() = file.starred
    }
}

enum class Special(val title: String) { FAVORITES("Favorites"), RECENT("Recent"), TRASH("Trash") }

/** A snackbar message, optionally with an action such as "Undo". */
data class UiMessage(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/** What the file browser shows. Lives as long as the window, so switching tabs keeps your place. */
class BrowserState(private val c: AppContainer) {
    var folderId by mutableStateOf<String?>(null)
        private set
    var special by mutableStateOf<Special?>(null)
        private set
    var query by mutableStateOf("")
    var selection by mutableStateOf<Set<String>>(emptySet())
    /** Anchor for shift-click range selection. */
    var anchor by mutableStateOf<String?>(null)
    var message by mutableStateOf<UiMessage?>(null)
    /** The file open in the viewer, plus the files you can step through with ←/→. */
    var viewing by mutableStateOf<Pair<DriveFile, List<DriveFile>>?>(null)

    val searching get() = query.isNotBlank()

    val sortKey: String
        get() = when {
            searching -> "special:SEARCH"
            special != null -> "special:${special!!.name}"
            else -> folderSortKey(folderId)
        }

    fun sortOf(s: AppSettings): SortMode = s.sortFor(sortKey)

    fun items(idx: DriveIndex, s: AppSettings): List<DriveItem> {
        val sort = sortOf(s)
        val liveFolders = idx.folders.filter { it.deletedAt == null }
        val liveFiles = idx.files.filter { it.deletedAt == null }
        fun sorted(fo: List<Folder>, fi: List<DriveFile>) =
            sortFolders(fo, sort).map { DriveItem.Dir(it) } + sortFiles(fi, sort).map { DriveItem.Doc(it) }
        val q = query.trim()
        return when {
            q.isNotEmpty() -> sorted(liveFolders.filter { it.name.contains(q, true) }, liveFiles.filter { it.name.contains(q, true) })
            special == Special.FAVORITES -> sorted(liveFolders.filter { it.starred }, liveFiles.filter { it.starred })
            special == Special.RECENT -> {
                val byId = liveFiles.associateBy { it.id }
                s.recent.entries.sortedByDescending { it.value }.mapNotNull { byId[it.key] }.map { DriveItem.Doc(it) }
            }
            special == Special.TRASH ->
                idx.folders.filter { it.trashedRoot && it.deletedAt != null }.sortedByDescending { it.deletedAt }.map { DriveItem.Dir(it) } +
                    idx.files.filter { it.trashedRoot && it.deletedAt != null }.sortedByDescending { it.deletedAt }.map { DriveItem.Doc(it) }
            else -> sorted(liveFolders.filter { it.parentId == folderId }, liveFiles.filter { it.folderId == folderId })
        }
    }

    /** Breadcrumbs from the top level to the open folder. */
    fun path(idx: DriveIndex): List<Folder> {
        val byId = idx.folders.associateBy { it.id }
        val out = ArrayList<Folder>()
        var cur = folderId?.let { byId[it] }
        while (cur != null && out.size < 100) {
            out.add(0, cur); cur = cur.parentId?.let { byId[it] }
        }
        return out
    }

    fun openFolder(id: String?) {
        folderId = id; special = null; query = ""; selection = emptySet(); anchor = null
    }

    fun openSpecial(s: Special) {
        special = s; folderId = null; query = ""; selection = emptySet(); anchor = null
        if (s == Special.TRASH) c.scope.launch { runCatching { c.drive.purgeOldTrash() } }
    }

    fun goUp(idx: DriveIndex) {
        when {
            searching -> query = ""
            special != null -> openFolder(null)
            else -> openFolder(path(idx).lastOrNull()?.parentId)
        }
    }

    fun setSort(mode: SortMode) = c.settings.update { it.copy(folderSorts = it.folderSorts + (sortKey to mode)) }

    // ---- Actions ----

    private fun count(list: List<DriveItem>) = if (list.size == 1) "“${list[0].name}”" else "${list.size} items"
    private fun List<DriveItem>.fileIds() = filterIsInstance<DriveItem.Doc>().map { it.id }
    private fun List<DriveItem>.folderIds() = filterIsInstance<DriveItem.Dir>().map { it.id }

    fun open(item: DriveItem, all: List<DriveItem>) {
        when (item) {
            is DriveItem.Dir -> if (special != Special.TRASH) openFolder(item.id)
            is DriveItem.Doc -> {
                if (special == Special.TRASH) return
                c.drive.markOpened(item.id)
                viewing = item.file to all.filterIsInstance<DriveItem.Doc>().map { it.file }
            }
        }
    }

    fun download(list: List<DriveItem>) {
        val ids = list.fileIds()
        if (ids.isEmpty()) return
        c.transfers.download(ids)
        selection = emptySet()
        message = UiMessage("Saving ${count(list.filterIsInstance<DriveItem.Doc>())} to ${c.settings.current.downloadFolder.name}. Progress is in Transfers.")
    }

    fun move(list: List<DriveItem>, target: String?) {
        val skipped = c.drive.move(list.fileIds(), list.folderIds(), target)
        selection = emptySet()
        if (skipped > 0) message = UiMessage("Can't move a folder into itself")
    }

    fun setStarred(list: List<DriveItem>, starred: Boolean) {
        c.drive.setStarred(list.map { it.id }, starred)
        selection = emptySet()
        message = UiMessage(if (starred) "Added ${count(list)} to Favorites" else "Removed ${count(list)} from Favorites")
    }

    fun trash(list: List<DriveItem>) {
        val files = list.fileIds(); val folders = list.folderIds()
        c.drive.trash(files, folders)
        selection = emptySet()
        message = UiMessage("Moved ${count(list)} to the trash", "Undo") { c.drive.restore(files, folders) }
    }

    fun restore(list: List<DriveItem>) {
        c.drive.restore(list.fileIds(), list.folderIds())
        selection = emptySet()
        message = UiMessage("Restored ${count(list)}")
    }

    fun deleteForever(list: List<DriveItem>) = c.scope.launch {
        val text = count(list)
        try {
            c.drive.deleteForever(list.fileIds(), list.folderIds())
            message = UiMessage("Deleted $text permanently")
        } catch (e: Exception) {
            message = UiMessage(e.message ?: "Couldn't delete")
        }
        selection = emptySet()
    }

    fun emptyTrash() = c.scope.launch {
        runCatching { c.drive.emptyTrash() }.onFailure { message = UiMessage(it.message ?: "Couldn't empty the trash") }
            .onSuccess { message = UiMessage("Trash emptied") }
    }
}
