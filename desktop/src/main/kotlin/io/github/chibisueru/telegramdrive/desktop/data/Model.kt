package io.github.chibisueru.telegramdrive.desktop.data

import kotlinx.serialization.Serializable

/**
 * The drive index, in the same JSON format (version 2) the Android app uses, so phones and
 * computers share one drive.
 */
@Serializable
data class DriveIndex(
    val version: Int = FORMAT_VERSION,
    val exportedAt: Long = 0,
    val folders: List<Folder> = emptyList(),
    val files: List<DriveFile> = emptyList(),
    /** IDs deleted for good, so merging another device's index can't bring them back. */
    val purged: List<String> = emptyList(),
) {
    companion object {
        const val FORMAT_VERSION = 2
        const val FILE_NAME = "tgdrive_index.json"
    }
}

@Serializable
data class Folder(
    val id: String,
    val parentId: String? = null,
    val name: String,
    val createdAt: Long = 0,
    val deletedAt: Long? = null,
    val trashedRoot: Boolean = false,
    val starred: Boolean = false,
)

@Serializable
data class DriveFile(
    val id: String,
    val folderId: String? = null,
    val name: String,
    val mimeType: String = "application/octet-stream",
    val size: Long,
    val createdAt: Long = 0,
    val messageId: Long,
    val remoteId: String,
    val thumbRemoteId: String? = null,
    val deletedAt: Long? = null,
    val trashedRoot: Boolean = false,
    val starred: Boolean = false,
)

enum class SortMode(val label: String) {
    NAME("Name"), NEWEST("Newest first"), OLDEST("Oldest first"),
    LARGEST("Largest first"), SMALLEST("Smallest first"), TYPE("Type"),
}

enum class ViewMode(val label: String) { LIST("List"), COMPACT("Compact list"), GRID("Grid"), LARGE_GRID("Large grid") }

fun folderSortKey(folderId: String?) = folderId ?: "root"

fun sortFolders(list: List<Folder>, mode: SortMode): List<Folder> = when (mode) {
    SortMode.NEWEST -> list.sortedByDescending { it.createdAt }
    SortMode.OLDEST -> list.sortedBy { it.createdAt }
    else -> list.sortedBy { it.name.lowercase() }
}

fun sortFiles(list: List<DriveFile>, mode: SortMode): List<DriveFile> = when (mode) {
    SortMode.NAME -> list.sortedBy { it.name.lowercase() }
    SortMode.NEWEST -> list.sortedByDescending { it.createdAt }
    SortMode.OLDEST -> list.sortedBy { it.createdAt }
    SortMode.LARGEST -> list.sortedByDescending { it.size }
    SortMode.SMALLEST -> list.sortedBy { it.size }
    SortMode.TYPE -> list.sortedWith(compareBy({ extensionOf(it.name) }, { it.name.lowercase() }))
}
