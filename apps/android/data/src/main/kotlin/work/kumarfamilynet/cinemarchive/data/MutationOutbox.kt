package work.kumarfamilynet.cinemarchive.data

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxDao
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

/**
 * Queues tracking mutations durably and flushes them through a [RemoteMutationWriter].
 * Callers write to Room optimistically first, then enqueue the equivalent remote write here
 * — the two are separate steps (not one transaction) because the outbox row only needs to
 * survive process death, not be atomic with the local read-model update it describes.
 */
class MutationOutbox(
    private val outboxDao: OutboxDao,
    private val remoteWriter: RemoteMutationWriter,
    private val conflictHandler: ConflictHandler,
) {
    suspend fun enqueue(entityType: String, entityId: String, operation: String, payload: JSONObject) {
        outboxDao.enqueue(
            OutboxEntity(
                id = UUID.randomUUID().toString(),
                entityType = entityType,
                entityId = entityId,
                operation = operation,
                payloadJson = payload.toString(),
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    /** Attempts to push every pending mutation once, oldest first. Safe to call repeatedly
     *  (on launch, on reconnect, on a timer) — entries that fail simply stay queued. A
     *  [PushResult.Conflict] resolves immediately (the server payload wins by construction,
     *  see [PushResult.Conflict]'s kdoc) rather than staying queued for another retry. */
    suspend fun flush() {
        for (entry in outboxDao.getPending()) {
            when (val result = remoteWriter.push(entry)) {
                is PushResult.Success -> outboxDao.remove(entry.id)
                is PushResult.Retry -> outboxDao.recordFailure(entry.id, result.reason)
                is PushResult.Conflict -> {
                    conflictHandler.applyRemote(entry.entityType, entry.entityId, result.serverPayload)
                    outboxDao.remove(entry.id)
                }
            }
        }
    }

    fun observePendingCount(): Flow<Int> = outboxDao.observePending().map { it.size }
}
