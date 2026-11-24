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

/** A file: one document message in the drive channel. */
@Entity(tableName = "files", indices = [Index("folderId")])
data class FileEntity(
    @PrimaryKey val id: String,
    val folderId: String?,
    val name: String,
    val mimeType: String,
    val size: Long,
    val createdAt: Long,
    /** The message holding the file in the drive channel. */
    val messageId: Long,
    /** TDLib's persistent remote file ID (valid for this Telegram account on any device). */
    val remoteId: String,
    val thumbRemoteId: String? = null,
)
