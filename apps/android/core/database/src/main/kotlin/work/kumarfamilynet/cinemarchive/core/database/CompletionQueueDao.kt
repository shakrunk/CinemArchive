package work.kumarfamilynet.cinemarchive.core.database

import androidx.room.Dao
import androidx.room.Query

/** Rewrite only a typed intent that no remote writer can dispatch. UPDATE retains FIFO rowid. */
@Dao
interface CompletionQueueDao {
    /** Mirror the server's ON DELETE SET NULL effect without inventing a new revision. */
    @Query("UPDATE cinema_outings SET completedViewingId = NULL WHERE completedViewingId = :viewingId")
    suspend fun clearViewingLink(viewingId: String)

    @Query("UPDATE mutation_outbox SET entityId = :targetId, operation = :operation, payloadJson = :payload, lastError = :reason WHERE id = :id AND operation = 'await_completion_v1' AND attemptCount = 0 AND payloadJson = :originalPayload")
    suspend fun resolveAwaiting(id: String, originalPayload: String, targetId: String, operation: String, payload: String, reason: String?): Int
}
