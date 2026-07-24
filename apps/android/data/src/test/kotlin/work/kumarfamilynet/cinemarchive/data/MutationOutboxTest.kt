package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.database.OutboxDao
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

/** In-memory [OutboxDao] stand-in — a real Room DAO needs an instrumented test; this
 *  module's logic (flush()'s branching on [PushResult]) doesn't touch SQL at all, so a
 *  plain fake is enough to unit-test it on the JVM. */
private class FakeOutboxDao : OutboxDao {
    private val entries = LinkedHashMap<String, OutboxEntity>()
    private val flow = MutableStateFlow<List<OutboxEntity>>(emptyList())

    private fun publish() {
        flow.value = entries.values.toList()
    }

    override suspend fun enqueue(entry: OutboxEntity) {
        entries[entry.id] = entry
        publish()
    }

    override fun observePending(): Flow<List<OutboxEntity>> = flow

    override suspend fun getPending(): List<OutboxEntity> = entries.values.toList()

    override suspend fun remove(id: String) {
        entries.remove(id)
        publish()
    }

    override suspend fun recordFailure(id: String, error: String?) {
        val existing = entries[id] ?: return
        entries[id] = existing.copy(attemptCount = existing.attemptCount + 1, lastError = error)
        publish()
    }
}

private class ScriptedRemoteMutationWriter(private val results: Map<String, PushResult>) : RemoteMutationWriter {
    override suspend fun push(entry: OutboxEntity): PushResult =
        results[entry.id] ?: PushResult.Retry("no script for ${entry.id}")
}

private class RecordingConflictHandler : ConflictHandler {
    val applied = mutableListOf<Triple<String, String, JSONObject>>()
    override suspend fun applyRemote(entityType: String, entityId: String, serverPayload: JSONObject) {
        applied += Triple(entityType, entityId, serverPayload)
    }
}

private fun titleOutboxEntry(id: String, status: String, updatedAt: String) = OutboxEntity(
    id = id,
    entityType = "title",
    entityId = "title-1",
    operation = "update",
    payloadJson = JSONObject().apply {
        put("id", "title-1")
        put("status", status)
        put("updatedAt", updatedAt)
    }.toString(),
    createdAt = 0L,
)

class MutationOutboxTest {

    @Test
    fun `success removes the entry`() = runTest {
        val dao = FakeOutboxDao()
        val entry = titleOutboxEntry("op-1", "WATCHED", "2026-07-14T00:00:00.000Z")
        dao.enqueue(entry)
        val outbox = MutationOutbox(dao, ScriptedRemoteMutationWriter(mapOf("op-1" to PushResult.Success)), RecordingConflictHandler())

        outbox.flush()

        assertTrue(dao.getPending().isEmpty())
    }

    @Test
    fun `retry keeps the entry queued and records the reason`() = runTest {
        val dao = FakeOutboxDao()
        val entry = titleOutboxEntry("op-1", "WATCHED", "2026-07-14T00:00:00.000Z")
        dao.enqueue(entry)
        val outbox = MutationOutbox(
            dao,
            ScriptedRemoteMutationWriter(mapOf("op-1" to PushResult.Retry("offline"))),
            RecordingConflictHandler(),
        )

        outbox.flush()

        val pending = dao.getPending()
        assertEquals(1, pending.size)
        assertEquals("offline", pending.single().lastError)
        assertEquals(1, pending.single().attemptCount)
    }

    @Test
    fun `conflict applies the server payload and clears the entry, not a retry`() = runTest {
        val dao = FakeOutboxDao()
        val entry = titleOutboxEntry("op-1", "WATCHED", "2026-07-14T00:00:00.000Z")
        dao.enqueue(entry)
        val serverPayload = JSONObject().apply {
            put("id", "title-1")
            put("status", "DROPPED")
            put("updatedAt", "2026-07-14T00:05:00.000Z")
        }
        val conflictHandler = RecordingConflictHandler()
        val outbox = MutationOutbox(
            dao,
            ScriptedRemoteMutationWriter(mapOf("op-1" to PushResult.Conflict(serverPayload))),
            conflictHandler,
        )

        outbox.flush()

        assertTrue("a resolved conflict must not stay queued for another retry", dao.getPending().isEmpty())
        assertEquals(1, conflictHandler.applied.size)
        val (entityType, entityId, payload) = conflictHandler.applied.single()
        assertEquals("title", entityType)
        assertEquals("title-1", entityId)
        assertEquals("DROPPED", payload.getString("status"))
    }

    @Test
    fun `flush processes multiple pending entries independently`() = runTest {
        val dao = FakeOutboxDao()
        dao.enqueue(titleOutboxEntry("op-1", "WATCHED", "2026-07-14T00:00:00.000Z"))
        dao.enqueue(titleOutboxEntry("op-2", "DROPPED", "2026-07-14T00:01:00.000Z"))
        val outbox = MutationOutbox(
            dao,
            ScriptedRemoteMutationWriter(
                mapOf(
                    "op-1" to PushResult.Success,
                    "op-2" to PushResult.Retry("offline"),
                ),
            ),
            RecordingConflictHandler(),
        )

        outbox.flush()

        val pending = dao.getPending()
        assertEquals(1, pending.size)
        assertEquals("op-2", pending.single().id)
        assertNull(dao.getPending().find { it.id == "op-1" })
    }
}
