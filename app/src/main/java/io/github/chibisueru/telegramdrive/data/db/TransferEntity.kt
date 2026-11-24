package io.github.chibisueru.telegramdrive.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One queued, running or finished upload/download. Survives app restarts. */
@Entity(tableName = "transfers", indices = [Index("state")])
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val upload: Boolean,
    val name: String,
    val mimeType: String,
    /** Bytes; -1 if unknown. */
    val size: Long,
    /** Uploads: the picked content:// URI. */
    val sourceUri: String? = null,
    /** Uploads: the destination folder (null = root). */
    val folderId: String? = null,
    /** Downloads: the drive file to save. */
    val fileId: String? = null,
    val state: String = STATE_QUEUED,
    val progress: Float = 0f,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val active get() = state == STATE_QUEUED || state == STATE_RUNNING

    companion object {
        const val STATE_QUEUED = "queued"
        const val STATE_RUNNING = "running"
        const val STATE_DONE = "done"
        const val STATE_FAILED = "failed"
        const val STATE_CANCELLED = "cancelled"
    }
}
