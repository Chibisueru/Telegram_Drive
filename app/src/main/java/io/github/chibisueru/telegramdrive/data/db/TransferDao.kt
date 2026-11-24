package io.github.chibisueru.telegramdrive.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY createdAt DESC, id DESC")
    fun all(): Flow<List<TransferEntity>>

    @Query("SELECT COUNT(*) FROM transfers WHERE state IN ('queued', 'running')")
    fun activeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM transfers WHERE state IN ('queued', 'running')")
    suspend fun pendingCount(): Int

    /** Oldest first, so files go up in the order they were picked. */
    @Query("SELECT * FROM transfers WHERE state = 'queued' ORDER BY createdAt, id LIMIT 1")
    suspend fun nextQueued(): TransferEntity?

    @Query("SELECT * FROM transfers WHERE id = :id")
    suspend fun get(id: Long): TransferEntity?

    @Insert
    suspend fun insertAll(transfers: List<TransferEntity>)

    @Query("UPDATE transfers SET state = :state, error = :error WHERE id = :id")
    suspend fun setState(id: Long, state: String, error: String? = null)

    @Query("UPDATE transfers SET progress = :progress WHERE id = :id")
    suspend fun setProgress(id: Long, progress: Float)

    /** After a crash or reboot, unfinished work goes back into the queue. */
    @Query("UPDATE transfers SET state = 'queued', progress = 0 WHERE state = 'running'")
    suspend fun requeueRunning()

    @Query("UPDATE transfers SET state = 'queued', progress = 0, error = NULL WHERE id = :id")
    suspend fun requeue(id: Long)

    @Query("UPDATE transfers SET state = 'cancelled' WHERE state IN ('queued', 'running')")
    suspend fun cancelAllPending()

    @Query("SELECT * FROM transfers WHERE state IN ('done', 'failed', 'cancelled')")
    suspend fun finished(): List<TransferEntity>

    @Query("DELETE FROM transfers WHERE state IN ('done', 'failed', 'cancelled')")
    suspend fun clearFinished()
}
