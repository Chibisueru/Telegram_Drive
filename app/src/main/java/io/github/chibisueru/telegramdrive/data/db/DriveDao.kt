package io.github.chibisueru.telegramdrive.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface DriveDao {
    // ---- Browsing (trash excluded) ----

    @Query("SELECT * FROM folders WHERE parentId IS :parentId AND deletedAt IS NULL")
    fun foldersIn(parentId: String?): Flow<List<FolderEntity>>

    @Query("SELECT * FROM files WHERE folderId IS :folderId AND deletedAt IS NULL")
    fun filesIn(folderId: String?): Flow<List<FileEntity>>

    @Query("SELECT * FROM folders WHERE name LIKE '%' || :q || '%' AND deletedAt IS NULL")
    fun searchFolders(q: String): Flow<List<FolderEntity>>

    @Query("SELECT * FROM files WHERE name LIKE '%' || :q || '%' AND deletedAt IS NULL")
    fun searchFiles(q: String): Flow<List<FileEntity>>

    @Query("SELECT * FROM folders WHERE deletedAt IS NULL")
    fun liveFoldersFlow(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE starred = 1 AND deletedAt IS NULL")
    fun starredFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM files WHERE starred = 1 AND deletedAt IS NULL")
    fun starredFiles(): Flow<List<FileEntity>>

    @Query("SELECT * FROM files WHERE lastOpenedAt IS NOT NULL AND deletedAt IS NULL ORDER BY lastOpenedAt DESC LIMIT 60")
    fun recentFiles(): Flow<List<FileEntity>>

    @Query("SELECT * FROM folders WHERE trashedRoot = 1 AND deletedAt IS NOT NULL")
    fun trashedFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM files WHERE trashedRoot = 1 AND deletedAt IS NOT NULL")
    fun trashedFiles(): Flow<List<FileEntity>>

    @Query("SELECT * FROM files WHERE id = :id")
    fun fileFlow(id: String): Flow<FileEntity?>

    @Query("SELECT COALESCE(SUM(size), 0) FROM files")
    fun totalSize(): Flow<Long>

    @Query("SELECT COUNT(*) FROM files WHERE deletedAt IS NULL")
    fun fileCount(): Flow<Int>

    // ---- Lookups ----

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun folder(id: String): FolderEntity?

    @Query("SELECT * FROM files WHERE id = :id")
    suspend fun file(id: String): FileEntity?

    @Query("SELECT * FROM folders")
    suspend fun allFolders(): List<FolderEntity>

    @Query("SELECT * FROM files")
    suspend fun allFiles(): List<FileEntity>

    @Query("SELECT * FROM files WHERE folderId IN (:folderIds)")
    suspend fun filesInFolders(folderIds: List<String>): List<FileEntity>

    @Query("SELECT * FROM folders WHERE trashedRoot = 1 AND deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun trashedFoldersBefore(before: Long): List<FolderEntity>

    @Query("SELECT * FROM files WHERE trashedRoot = 1 AND deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun trashedFilesBefore(before: Long): List<FileEntity>

    @Query("SELECT * FROM folders WHERE parentId IS NULL AND name = :name AND deletedAt IS NULL LIMIT 1")
    suspend fun rootFolderNamed(name: String): FolderEntity?

    // ---- Changes ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolders(folders: List<FolderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFiles(files: List<FileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: FileEntity)

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun renameFolder(id: String, name: String)

    @Query("UPDATE files SET name = :name WHERE id = :id")
    suspend fun renameFile(id: String, name: String)

    @Query("UPDATE files SET folderId = :folderId WHERE id = :id")
    suspend fun moveFile(id: String, folderId: String?)

    @Query("UPDATE folders SET parentId = :parentId WHERE id = :id")
    suspend fun moveFolder(id: String, parentId: String?)

    @Query("UPDATE folders SET starred = :starred WHERE id IN (:ids)")
    suspend fun starFolders(ids: List<String>, starred: Boolean)

    @Query("UPDATE files SET starred = :starred WHERE id IN (:ids)")
    suspend fun starFiles(ids: List<String>, starred: Boolean)

    @Query("UPDATE files SET lastOpenedAt = :at WHERE id = :id")
    suspend fun markOpened(id: String, at: Long)

    @Query("UPDATE folders SET deletedAt = :at, trashedRoot = (id IN (:roots)) WHERE id IN (:ids)")
    suspend fun trashFolders(ids: List<String>, roots: List<String>, at: Long)

    @Query("UPDATE files SET deletedAt = :at, trashedRoot = (id IN (:roots)) WHERE id IN (:ids)")
    suspend fun trashFiles(ids: List<String>, roots: List<String>, at: Long)

    @Query("UPDATE folders SET deletedAt = NULL, trashedRoot = 0 WHERE id IN (:ids)")
    suspend fun untrashFolders(ids: List<String>)

    @Query("UPDATE files SET deletedAt = NULL, trashedRoot = 0 WHERE id IN (:ids)")
    suspend fun untrashFiles(ids: List<String>)

    @Query("DELETE FROM files WHERE id IN (:ids)")
    suspend fun deleteFiles(ids: List<String>)

    @Query("DELETE FROM folders WHERE id IN (:ids)")
    suspend fun deleteFolders(ids: List<String>)

    @Query("DELETE FROM folders")
    suspend fun clearFolders()

    @Query("DELETE FROM files")
    suspend fun clearFiles()

    @Transaction
    suspend fun replaceAll(folders: List<FolderEntity>, files: List<FileEntity>) {
        clearFiles()
        clearFolders()
        insertFolders(folders)
        insertFiles(files)
    }

    @Transaction
    suspend fun clearAll() {
        clearFiles()
        clearFolders()
    }
}
