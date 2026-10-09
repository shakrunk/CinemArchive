package work.kumarfamilynet.cinemarchive.core.database

import androidx.room.Dao
import androidx.room.Query

/** Rewrite only a typed intent that no remote writer can dispatch. UPDATE retains FIFO rowid. */
@Dao
interface CompletionQueueDao {
    /** Explicit migration of an older membership keeps the queue's original FIFO position. */
    @Query("UPDATE mutation_outbox SET id = :newId, operation = :newOperation, payloadJson = :payload, attemptCount = 0, lastError = NULL WHERE id = :id AND entityType = 'list_item' AND operation = :oldOperation AND payloadJson = :originalPayload")
    suspend fun replaceLegacyMembership(id: String, oldOperation: String, originalPayload: String, newId: String, newOperation: String, payload: String): Int

    /** Replace a definitive rejection in place; dependent FIFO rows never move ahead of it. */
    @Query("UPDATE mutation_outbox SET id = :newId, operation = :operation, payloadJson = :payload, attemptCount = 0, lastError = NULL WHERE id = :id AND entityType = :entityType AND operation = 'review' AND payloadJson = :originalPayload")
    suspend fun replaceReviewedLifecycle(id: String, entityType: String, originalPayload: String, newId: String, operation: String, payload: String): Int
    /** Mirror the server's ON DELETE SET NULL effect without inventing a new revision. */
    @Query("UPDATE cinema_outings SET completedViewingId = NULL WHERE completedViewingId = :viewingId")
    suspend fun clearViewingLink(viewingId: String)

    @Query("UPDATE mutation_outbox SET entityId = :targetId, operation = :operation, payloadJson = :payload, lastError = :reason WHERE id = :id AND operation = 'await_completion_v1' AND attemptCount = 0 AND payloadJson = :originalPayload")
    suspend fun resolveAwaiting(id: String, originalPayload: String, targetId: String, operation: String, payload: String, reason: String?): Int
}
