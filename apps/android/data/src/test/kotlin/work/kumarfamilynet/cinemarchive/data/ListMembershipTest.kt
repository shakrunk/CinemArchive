package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*

internal object MembershipFixture {
    val owner = OutingCommandFixture.owner
    val title = OutingCommandFixture.title
    const val list = "70000000-0000-4000-8000-000000000001"
    const val local = "70000000-0000-4000-8000-000000000002"
    const val canonical = "70000000-0000-4000-8000-000000000003"
    const val time = "2026-10-08T12:00:00Z"
    fun command(present: Boolean = true, operationId: String = OutingCommandFixture.operation) = OutboxEntity(
        operationId, "list_item", local, MEMBERSHIP_COMMAND,
        membershipPayload(local, list, title, present, if (present) time else null).toString(), 1)
    fun row(id: String = canonical) = JSONObject().put("id", id).put("user_id", owner)
        .put("list_id", list).put("title_id", title).put("position", 5).put("added_at", time).put("updated_at", time)
    fun receipt(entry: OutboxEntity): JSONObject {
        val operation = membershipOperations(entry).getJSONObject(0)
        val result = JSONObject().put("table", "list_items").put("key", operation.getJSONObject("key"))
        if (operation.getString("action") == "delete") result.put("deleted", true) else result.put("row", row())
        return JSONObject().put("operationId", entry.id).put("rows", JSONArray().put(result))
    }
    fun envelope(entry: OutboxEntity, row: JSONObject? = row()) = JSONObject().put("receipt", receipt(entry)).put("current", row ?: JSONObject.NULL)
}

@RunWith(RobolectricTestRunner::class)
class ListMembershipTest {
    private lateinit var db: LibraryDatabase
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        db.titleDao().upsertAll(listOf(TitleEntity(MembershipFixture.title, 42, "MOVIE", "Film", 2026, null,
            emptyList(), null, null, null, 90, null, "WATCHLIST", null, null, MembershipFixture.time, MembershipFixture.time)))
        db.listDao().upsert(ListEntity(MembershipFixture.list, "Films", "Preserved description", MembershipFixture.time, MembershipFixture.time))
        db.listItemDao().upsert(ListItemEntity(MembershipFixture.local, MembershipFixture.list, MembershipFixture.title, null, MembershipFixture.time, MembershipFixture.time))
    }
    @After fun cleanup() { db.close() }
    private suspend fun apply(entry: OutboxEntity, row: JSONObject? = MembershipFixture.row()) = db.withTransaction {
        ListMembershipApplier(db, MembershipFixture.owner).apply(entry, MembershipFixture.envelope(entry, row))
        db.outboxDao().remove(entry.id)
    }

    @Test fun concurrentMembershipAdoptsCanonicalIdAndPreservesServerPosition() = runBlocking {
        val entry = MembershipFixture.command(); db.outboxDao().enqueue(entry); apply(entry)
        assertNull(db.listItemDao().getById(MembershipFixture.local))
        assertEquals(5, db.listItemDao().getById(MembershipFixture.canonical)!!.position)
        assertEquals("Preserved description", db.listDao().getById(MembershipFixture.list)!!.description)
    }

    @Test fun queuedRemovalUsesNaturalKeyWithoutRewritingPayloadAfterConcurrentAdd() = runBlocking {
        val add = MembershipFixture.command()
        val remove = MembershipFixture.command(false, "70000000-0000-4000-8000-000000000004")
        db.outboxDao().enqueue(add); db.outboxDao().enqueue(remove)
        db.listItemDao().deleteByListAndTitle(MembershipFixture.list, MembershipFixture.title)
        apply(add)
        assertNull(db.listItemDao().findId(MembershipFixture.list, MembershipFixture.title))
        assertEquals(remove, db.outboxDao().getPending().single())
        val operation = membershipOperations(remove).getJSONObject(0)
        assertEquals("delete", operation.getString("action"))
        assertFalse(operation.getJSONObject("key").has("id"))
        apply(remove, null)
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun removeThenReaddKeepsLatestOptimisticMembershipUntilItsOwnAck() = runBlocking {
        val remove = MembershipFixture.command(false)
        val add = MembershipFixture.command(true, "70000000-0000-4000-8000-000000000004")
        db.outboxDao().enqueue(remove); db.outboxDao().enqueue(add)
        apply(remove, null)
        assertNotNull(db.listItemDao().getById(MembershipFixture.local))
        apply(add)
        assertEquals(MembershipFixture.canonical, db.listItemDao().findId(MembershipFixture.list, MembershipFixture.title))
    }

    @Test fun historicInsertReceiptNeverResurrectsCurrentlyDeletedMembership() = runBlocking {
        val add = MembershipFixture.command(); db.outboxDao().enqueue(add); apply(add, null)
        assertNull(db.listItemDao().findId(MembershipFixture.list, MembershipFixture.title))
    }

    @Test fun historicDeleteReceiptAdoptsLaterRemoteReadd() = runBlocking {
        val remove = MembershipFixture.command(false); db.outboxDao().enqueue(remove); apply(remove)
        assertNotNull(db.listItemDao().getById(MembershipFixture.canonical))
    }

    @Test fun deletedParentCannotBeResurrectedDuringAcknowledgment() = runBlocking {
        val add = MembershipFixture.command(); db.outboxDao().enqueue(add)
        db.listDao().deleteById(MembershipFixture.list); apply(add)
        assertNull(db.listItemDao().getById(MembershipFixture.canonical))
        assertNull(db.listDao().getById(MembershipFixture.list))
    }

    @Test fun wrongOwnerRollsBackProjectionAndKeepsExactCommand() = runBlocking {
        val add = MembershipFixture.command(); db.outboxDao().enqueue(add)
        assertTrue(runCatching { apply(add, MembershipFixture.row().put("user_id", "other")) }.isFailure)
        assertEquals(add, db.outboxDao().getPending().single())
        assertNotNull(db.listItemDao().getById(MembershipFixture.local))
    }

    @Test fun pendingNaturalKeyProtectsDifferentCanonicalUuidFromPull() {
        val add = MembershipFixture.command()
        val pending = membershipProtectionKeys(listOf(add))
        val row = JSONObject().put("entity_type", "list_item").put("entity_id", MembershipFixture.canonical)
            .put("payload", JSONObject().put("listId", MembershipFixture.list).put("titleId", MembershipFixture.title))
        assertTrue(isProtectedFromPull(row, pending))
        row.getJSONObject("payload").put("listId", "other-list")
        assertFalse(isProtectedFromPull(row, pending))
    }

    @Test fun repositoryQueuesStableNaturalCommandsAndRollsBackFailedAdmission() = runBlocking {
        val outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity) = PushResult.Retry("Offline")
        }, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db))
        val repository = ListsRepository(db.listDao(), db.listItemDao(), outbox)
        repository.removeTitleFromList(MembershipFixture.list, MembershipFixture.title)
        repository.addTitleToList(MembershipFixture.list, MembershipFixture.title)
        repository.addTitleToList(MembershipFixture.list, MembershipFixture.title)
        val pending = db.outboxDao().getPending()
        assertEquals(2, pending.size)
        assertEquals(listOf("delete", "insert"), pending.map { membershipOperations(it).getJSONObject(0).getString("action") })
        assertEquals(1, outbox.pendingEntityKeys().count { it.startsWith("list_membership:") })
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_list_queue BEFORE INSERT ON mutation_outbox BEGIN SELECT RAISE(ABORT, 'fixture failure'); END")
        assertTrue(runCatching { repository.removeTitleFromList(MembershipFixture.list, MembershipFixture.title) }.isFailure)
        assertNotNull(db.listItemDao().findId(MembershipFixture.list, MembershipFixture.title))
        assertEquals(pending, db.outboxDao().getPending())
    }

    @Test fun failedAckRetainsOriginalPayloadAndProjectionForExactRetry() = runBlocking {
        val entry = MembershipFixture.command(); db.outboxDao().enqueue(entry)
        var reject = true
        val pushed = mutableListOf<OutboxEntity>()
        val outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity): PushResult {
                pushed += entry
                return PushResult.Applied(MembershipFixture.envelope(entry))
            }
        }, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db), AppliedMutationHandler { item, envelope ->
            ListMembershipApplier(db, MembershipFixture.owner).apply(item, envelope)
            check(!reject) { "Interrupted ACK" }
        })
        outbox.flush()
        assertNotNull(db.listItemDao().getById(MembershipFixture.local))
        assertNull(db.listItemDao().getById(MembershipFixture.canonical))
        assertEquals(entry.payloadJson, db.outboxDao().getPending().single().payloadJson)
        reject = false; outbox.flush()
        assertEquals(listOf(entry.payloadJson, entry.payloadJson), pushed.map { it.payloadJson })
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertNotNull(db.listItemDao().getById(MembershipFixture.canonical))
    }
}
