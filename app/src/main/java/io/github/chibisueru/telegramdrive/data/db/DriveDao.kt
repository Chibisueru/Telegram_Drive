package io.github.chibisueru.telegramdrive.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface DriveDao {
    @Query("SELECT * FROM folders WHERE parentId IS :parentId")
    fun foldersIn(parentId: String?): Flow<List<FolderEntity>>

    @Query("SELECT * FROM files WHERE folderId IS :folderId")
    fun filesIn(folderId: String?): Flow<List<FileEntity>>

    @Query("SELECT * FROM folders WHERE name LIKE '%' || :q || '%'")
    fun searchFolders(q: String): Flow<List<FolderEntity>>

    @Query("SELECT * FROM files WHERE name LIKE '%' || :q || '%'")
    fun searchFiles(q: String): Flow<List<FileEntity>>

    @Query("SELECT * FROM folders")
    fun allFoldersFlow(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM files WHERE id = :id")
    fun fileFlow(id: String): Flow<FileEntity?>

    @Query("SELECT COALESCE(SUM(size), 0) FROM files")
    fun totalSize(): Flow<Long>

    @Query("SELECT COUNT(*) FROM files")
    fun fileCount(): Flow<Int>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun folder(id: String): FolderEntity?

    @Query("SELECT * FROM files WHERE id = :id")
    suspend fun file(id: String): FileEntity?

    @Query("SELECT * FROM chunks WHERE fileId = :fileId ORDER BY idx")
    suspend fun chunks(fileId: String): List<ChunkEntity>

    @Query("SELECT * FROM folders")
    suspend fun allFolders(): List<FolderEntity>

    @Query("SELECT * FROM files")
    suspend fun allFiles(): List<FileEntity>

    @Query("SELECT * FROM chunks")
    suspend fun allChunks(): List<ChunkEntity>

    @Query("SELECT * FROM files WHERE folderId IN (:folderIds)")
    suspend fun filesInFolders(folderIds: List<String>): List<FileEntity>

    @Query("SELECT * FROM chunks WHERE fileId IN (:fileIds)")
    suspend fun chunksOf(fileIds: List<String>): List<ChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolders(folders: List<FolderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFiles(files: List<FileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<ChunkEntity>)

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun renameFolder(id: String, name: String)

    @Query("UPDATE files SET name = :name WHERE id = :id")
    suspend fun renameFile(id: String, name: String)

    @Query("UPDATE files SET folderId = :folderId WHERE id = :id")
    suspend fun moveFile(id: String, folderId: String?)

    @Query("UPDATE folders SET parentId = :parentId WHERE id = :id")
    suspend fun moveFolder(id: String, parentId: String?)

    @Query("DELETE FROM files WHERE id IN (:ids)")
    suspend fun deleteFiles(ids: List<String>)

    @Query("DELETE FROM chunks WHERE fileId IN (:ids)")
    suspend fun deleteChunks(ids: List<String>)

    @Query("DELETE FROM folders WHERE id IN (:ids)")
    suspend fun deleteFolders(ids: List<String>)

    @Query("DELETE FROM folders")
    suspend fun clearFolders()

    @Query("DELETE FROM files")
    suspend fun clearFiles()

    @Query("DELETE FROM chunks")
    suspend fun clearChunks()

    @Transaction
    suspend fun insertFileWithChunks(file: FileEntity, chunks: List<ChunkEntity>) {
        insertFiles(listOf(file))
        insertChunks(chunks)
    }

    @Transaction
    suspend fun replaceAll(folders: List<FolderEntity>, files: List<FileEntity>, chunks: List<ChunkEntity>) {
        clearChunks()
        clearFiles()
        clearFolders()
        insertFolders(folders)
        insertFiles(files)
        insertChunks(chunks)
    }

    @Transaction
    suspend fun clearAll() {
        clearChunks()
        clearFiles()
        clearFolders()
    }
}
