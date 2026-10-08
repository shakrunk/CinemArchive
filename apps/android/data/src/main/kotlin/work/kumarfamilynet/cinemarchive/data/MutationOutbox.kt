package work.kumarfamilynet.cinemarchive.data

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LocalTransactor
import work.kumarfamilynet.cinemarchive.core.database.OutboxDao
import work.kumarfamilynet.cinemarchive.core.database.PassthroughTransactor
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

/**
 * Queues tracking mutations durably and flushes them through a [RemoteMutationWriter].
 * Callers write to Room optimistically and enqueue the equivalent remote write in ONE
 * transaction ([atomically]): the queue entry can never be missing for an applied edit, nor
 * present for one that failed — which is also what lets the sync pull trust [pendingEntityKeys].
 */
class MutationOutbox(
    private val outboxDao: OutboxDao,
    private val remoteWriter: RemoteMutationWriter,
    private val conflictHandler: ConflictHandler,
    private val transactor: LocalTransactor = PassthroughTransactor,
) {
    // One flush at a time: startup, resume and pull-to-refresh can all ask for one, and two
    // concurrent passes would push the same entry twice.
    private val flushMutex = Mutex()

    private val _conflicts = MutableSharedFlow<ConflictNotice>(extraBufferCapacity = 16)

    /** Edits the server rejected because a newer version existed. The local projection has
     *  already been reconciled to the server's row; the UI tells the user their edit was replaced. */
    val conflicts: SharedFlow<ConflictNotice> = _conflicts

    /** Runs [block] (a local write plus its [enqueue]) as one transaction, so the queue entry
     *  can never be missing for an applied edit, nor present for one that failed. */
    suspend fun <T> atomically(block: suspend () -> T): T = transactor.run(block)

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
    suspend fun flush() = flushMutex.withLock {
        val queue = outboxDao.getPending()
        for (entry in queue) {
            when (val result = remoteWriter.push(entry)) {
                is PushResult.Success -> outboxDao.remove(entry.id)
                is PushResult.Retry -> outboxDao.recordFailure(entry.id, result.reason)
                is PushResult.Conflict -> {
                    // Reconcile the projection and drop the entry atomically: the rejected edit
                    // is replaced by the server's row, never left half-applied or silently retried.
                    transactor.run {
                        conflictHandler.applyRemote(entry.entityType, entry.entityId, result.serverPayload)
                        // A later offline edit to the same entity is still queued: put it back on
                        // top of the server row so it isn't rolled back out of the projection.
                        val key = pendingKey(entry.entityType, entry.entityId)
                        val later = queue.filter {
                            it.id != entry.id && it.createdAt >= entry.createdAt && pendingKey(it.entityType, it.entityId) == key
                        }
                        if (later.isNotEmpty()) {
                            conflictHandler.rebasePending(entry.entityType, entry.entityId, later.map { JSONObject(it.payloadJson) })
                        }
                        outboxDao.remove(entry.id)
                    }
                    _conflicts.tryEmit(ConflictNotice(entry.entityType, entry.entityId, entry.payloadJson))
                }
            }
        }
    }

    /** Keys (`type:id`, see [pendingKey]) of every entity with a queued, not-yet-pushed mutation —
     *  upserts AND deletes. The pull side must not overwrite or resurrect these rows: the local
     *  copy is the user's newer intent and the server hasn't seen it yet. */
    suspend fun pendingEntityKeys(): Set<String> =
        outboxDao.getPending().mapTo(HashSet()) { pendingKey(it.entityType, it.entityId) }

    fun observePendingCount(): Flow<Int> = outboxDao.observePending().map { it.size }
}

/** Outbox entity types and sync-pull entity types differ only for episode metadata patches,
 *  which target an `episode` row. */
internal fun pendingKey(entityType: String, entityId: String): String =
    (if (entityType == "episode_metadata") "episode" else entityType) + ":" + entityId

/** A queued edit the server refused because it already had a newer version of the entity. */
data class ConflictNotice(val entityType: String, val entityId: String, val rejectedPayloadJson: String)
