package io.github.chibisueru.telegramdrive.data

import io.github.chibisueru.telegramdrive.data.db.FileEntity
import io.github.chibisueru.telegramdrive.data.db.FolderEntity
import io.github.chibisueru.telegramdrive.util.extensionOf

/** Key under which a folder's sort order is stored; null = the root ("My Drive"). */
fun folderSortKey(folderId: String?) = folderId ?: "root"

private val byName = compareBy<String, String>(String.CASE_INSENSITIVE_ORDER) { it }

fun sortFolders(folders: List<FolderEntity>, mode: SortMode): List<FolderEntity> = when (mode) {
    SortMode.NEWEST -> folders.sortedByDescending { it.createdAt }
    SortMode.OLDEST -> folders.sortedBy { it.createdAt }
    else -> folders.sortedWith(compareBy(byName) { it.name })
}

fun sortFiles(files: List<FileEntity>, mode: SortMode): List<FileEntity> = when (mode) {
    SortMode.NAME -> files.sortedWith(compareBy(byName) { it.name })
    SortMode.NEWEST -> files.sortedByDescending { it.createdAt }
    SortMode.OLDEST -> files.sortedBy { it.createdAt }
    SortMode.LARGEST -> files.sortedByDescending { it.size }
    SortMode.SMALLEST -> files.sortedBy { it.size }
    SortMode.TYPE -> files.sortedWith(compareBy<FileEntity, String>(byName) { extensionOf(it.name) }.thenBy(byName) { it.name })
}
