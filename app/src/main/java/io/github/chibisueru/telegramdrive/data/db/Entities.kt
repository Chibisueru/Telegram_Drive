package io.github.chibisueru.telegramdrive.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A folder. `parentId == null` means it lives in the root ("My Drive"). */
@Entity(tableName = "folders", indices = [Index("parentId")])
data class FolderEntity(
    @PrimaryKey val id: String,
    val parentId: String?,
    val name: String,
    val createdAt: Long,
)

/** A file. Its bytes live in Telegram as one or more [ChunkEntity] documents. */
@Entity(tableName = "files", indices = [Index("folderId")])
data class FileEntity(
    @PrimaryKey val id: String,
    val folderId: String?,
    val name: String,
    val mimeType: String,
    val size: Long,
    val createdAt: Long,
    /** Size of every chunk except possibly the last one. */
    val chunkSize: Long,
    val thumbFileId: String? = null,
    val thumbMessageId: Long? = null,
)

/** One Telegram document holding bytes [idx * chunkSize, idx * chunkSize + size) of a file. */
@Entity(tableName = "chunks", primaryKeys = ["fileId", "idx"])
data class ChunkEntity(
    val fileId: String,
    val idx: Int,
    val tgFileId: String,
    val messageId: Long,
    val size: Long,
)
