package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*

@RunWith(RobolectricTestRunner::class)
class ListMembershipRecoveryTest {
    private lateinit var db: LibraryDatabase
    private lateinit var outbox: MutationOutbox
    private var session: SupabaseSession? = SupabaseSession("token", MembershipFixture.owner)
    private var current: JSONObject? = MembershipFixture.row()
    private var old: JSONObject? = null
    private var beforeFetch: () -> Unit = {}
    private val originals = MutableStateFlow<Map<String, String>>(emptyMap())
    private var archiveFails = false
    private var replays = 0
    private var syncs = 0
    private var syncFails = false
    private val archive = object : OutingRecoveryArchive {
        override val records = originals
        override suspend fun put(id: String, record: String) {
            check(!archiveFails) { "Disk full" }
            originals.value = originals.value + (id to record)
        }
    }
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        db.titleDao().upsertAll(listOf(TitleEntity(MembershipFixture.title, 42, "MOVIE", "Film", 2026, null,
            emptyList(), null, null, null, 90, null, "WATCHLIST", null, null, MembershipFixture.time, MembershipFixture.time)))
        db.listDao().upsert(ListEntity(MembershipFixture.list, "Films", null, MembershipFixture.time, MembershipFixture.time))
        db.listItemDao().upsert(ListItemEntity(MembershipFixture.local, MembershipFixture.list, MembershipFixture.title, null,
            MembershipFixture.time, MembershipFixture.time))
        outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity) = PushResult.Retry("Offline")
        }, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db))
    }
    @After fun cleanup() { db.close() }
    private fun repository() = ListMembershipRecoveryRepository(db, MembershipFixture.owner, { session }, outbox, archive,
        object : ListMembershipRecoveryRemote {
            override suspend fun byId(id: String): JSONObject? { beforeFetch(); return old }
            override suspend fun byMembership(listId: String, titleId: String): JSONObject? { beforeFetch(); return current }
        }, replayBoundary = { action -> replays++; action() }, synchronize = {
            syncs++
            outbox.flush() // Proves synchronization runs after releasing the flush lock.
            check(!syncFails) { "Offline" }
        })
    private fun legacy(delete: Boolean = false): OutboxEntity {
        val payload = JSONObject().put("id", MembershipFixture.local)
        if (!delete) payload.put("listId", MembershipFixture.list).put("titleId", MembershipFixture.title)
            .put("addedAt", MembershipFixture.time).put("updatedAt", MembershipFixture.time).put("position", JSONObject.NULL)
        return MembershipFixture.command().copy(operation = if (delete) "delete" else "upsert", payloadJson = payload.toString(), attemptCount = 2)
    }

    @Test fun reviewedReplacementPreservesFifoDependentBytesAndOriginalAcrossRestart() = runBlocking {
        val entry = legacy()
        val later = MembershipFixture.command(false, "70000000-0000-4000-8000-000000000004")
        db.outboxDao().enqueue(entry); db.outboxDao().enqueue(later)
        db.listItemDao().deleteByListAndTitle(MembershipFixture.list, MembershipFixture.title)
        val review = repository().review(entry.id)
        db.outboxDao().recordFailure(entry.id, "Still waiting for review") // archive must tolerate later retry metadata
        assertEquals(OutingRecoveryOutcome.APPLIED, repository().apply(entry.id, review.remoteVersion, setOf("membership")))
        val pending = db.outboxDao().getPending()
        assertEquals(2, pending.size)
        assertEquals(later, pending[1]); assertNotEquals(entry.id, pending[0].id)
        assertEquals(MEMBERSHIP_COMMAND, pending[0].operation)
        assertEquals(MembershipFixture.list, membershipOperations(pending[0]).getJSONObject(0).getJSONObject("key").getString("list_id"))
        assertNull(db.listItemDao().findId(MembershipFixture.list, MembershipFixture.title))
        assertTrue(repository().items().single().resolved)
        assertEquals(entry.payloadJson, JSONObject(repository().exportOriginal(entry.id)).getJSONObject("original").getString("payloadJson"))
        assertTrue(runCatching { repository().apply(entry.id, review.remoteVersion, setOf("membership")) }.isFailure)
    }

    @Test fun changedCanonicalRowRequiresAnotherReviewWithoutReplacingQueue() = runBlocking {
        val entry = legacy(); db.outboxDao().enqueue(entry)
        val review = repository().review(entry.id)
        current = MembershipFixture.row().put("updated_at", "2026-10-09T12:00:00Z")
        assertEquals(OutingRecoveryOutcome.CHANGED, repository().apply(entry.id, review.remoteVersion, setOf("membership")))
        assertEquals(entry, db.outboxDao().getPending().single())
    }

    @Test fun discardAdoptsCurrentCanonicalMembershipAndStartsDurableReplay() = runBlocking {
        val entry = legacy(); db.outboxDao().enqueue(entry)
        repository().discard(entry.id)
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertNull(db.listItemDao().getById(MembershipFixture.local))
        assertEquals(5, db.listItemDao().getById(MembershipFixture.canonical)!!.position)
        assertEquals(1, replays)
        assertEquals(1, syncs)
        assertTrue(repository().items().single().resolved)
    }

    @Test fun unknownIdOnlyDeleteIsExportableAndDiscardableWithoutInventedMembership() = runBlocking {
        val entry = legacy(true); db.outboxDao().enqueue(entry)
        db.listItemDao().deleteById(MembershipFixture.local)
        val review = repository().review(entry.id)
        assertTrue(review.fields.isEmpty()); assertFalse(review.remoteExists)
        assertTrue(runCatching { repository().apply(entry.id, review.remoteVersion, setOf("membership")) }.isFailure)
        repository().discard(entry.id)
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertEquals(entry.payloadJson, JSONObject(repository().exportOriginal(entry.id)).getJSONObject("original").getString("payloadJson"))
    }

    @Test fun idOnlyDeleteRecoversVerifiedRemoteIdentityAndKeepsNaturalDelete() = runBlocking {
        val entry = legacy(true); db.outboxDao().enqueue(entry)
        db.listItemDao().deleteById(MembershipFixture.local)
        old = MembershipFixture.row(MembershipFixture.local)
        val review = repository().review(entry.id)
        assertEquals("Remove from list", review.fields.single().saved)
        assertEquals("Film in Films", review.title)
        assertEquals("Film in Films", repository().items().single().title)
        old = null // The captured identity survives deletion/replacement of the old surrogate row.
        repository().apply(entry.id, review.remoteVersion, setOf("membership"))
        val operation = membershipOperations(db.outboxDao().getPending().single()).getJSONObject(0)
        assertEquals("delete", operation.getString("action")); assertFalse(operation.getJSONObject("key").has("id"))
    }

    @Test fun ownerLossAfterFetchCannotReplaceOrDiscardSavedIntent() = runBlocking {
        val entry = legacy(); db.outboxDao().enqueue(entry)
        val review = repository().review(entry.id)
        beforeFetch = { session = null }
        assertTrue(runCatching { repository().apply(entry.id, review.remoteVersion, setOf("membership")) }.isFailure)
        assertEquals(entry, db.outboxDao().getPending().single())
        assertNotNull(db.listItemDao().getById(MembershipFixture.local))
    }

    @Test fun failedArchiveAndFailedRoomReplacementPreserveOriginalQueue() = runBlocking {
        val entry = legacy(); db.outboxDao().enqueue(entry)
        archiveFails = true
        assertTrue(runCatching { repository().discard(entry.id) }.isFailure)
        assertEquals(entry, db.outboxDao().getPending().single())
        archiveFails = false
        val review = repository().review(entry.id)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_list_review BEFORE UPDATE ON mutation_outbox BEGIN SELECT RAISE(ABORT, 'fixture failure'); END")
        assertTrue(runCatching { repository().apply(entry.id, review.remoteVersion, setOf("membership")) }.isFailure)
        assertEquals(entry, db.outboxDao().getPending().single())
        assertFalse(repository().items().single().resolved)
    }

    @Test fun failedImmediateSyncLeavesOneDurableReplacementAndOriginalExport() = runBlocking {
        val entry = legacy(); db.outboxDao().enqueue(entry)
        val review = repository().review(entry.id)
        syncFails = true
        assertEquals(OutingRecoveryOutcome.APPLIED,
            kotlinx.coroutines.withTimeout(2_000) { repository().apply(entry.id, review.remoteVersion, setOf("membership")) })
        val replacement = db.outboxDao().getPending().single()
        assertEquals(MEMBERSHIP_COMMAND, replacement.operation)
        assertEquals(1, replacement.attemptCount)
        assertEquals(1, syncs)
        assertTrue(repository().items().single().resolved)
        assertTrue(runCatching { repository().apply(entry.id, review.remoteVersion, setOf("membership")) }.isFailure)
        assertEquals(replacement, db.outboxDao().getPending().single())
    }

    @Test fun unknownLegacyMembershipDefersCanonicalRowsAndListTombstonesOnly() {
        val pending = membershipProtectionKeys(listOf(legacy(true)))
        assertTrue(isProtectedFromPull(JSONObject().put("entity_type", "list_item").put("entity_id", MembershipFixture.canonical), pending))
        val tombstone = JSONObject().put("entity_type", "tombstone").put("entity_id", MembershipFixture.canonical)
            .put("payload", JSONObject().put("entityType", "list_item"))
        assertTrue(isProtectedFromPull(tombstone, pending))
        tombstone.getJSONObject("payload").put("entityType", "title")
        assertFalse(isProtectedFromPull(tombstone, pending))
    }
}
