package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
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

@RunWith(RobolectricTestRunner::class)
class ViewingRecoveryRepositoryTest {
    private lateinit var db: LibraryDatabase
    private lateinit var outbox: MutationOutbox
    private lateinit var repository: ViewingRecoveryRepository
    private val records = MutableStateFlow<Map<String, String>>(emptyMap())
    private val archive = object : OutingRecoveryArchive {
        override val records = this@ViewingRecoveryRepositoryTest.records
        override suspend fun put(id: String, record: String) { records.value = records.value + (id to record) }
    }
    private var session: SupabaseSession? = SupabaseSession("owner-token", ViewingCommandFixture.owner)
    private var current: JSONObject? = ViewingCommandFixture.row().put("notes", "Remote note").put("rating", 4)
    private val attempted = mutableListOf<OutboxEntity>()
    private val boundary = mutableListOf<String>()
    private var nextResult: PushResult? = null
    private var afterFetch: () -> Unit = {}
    private var afterConfirm: () -> Unit = {}
    private val remote = object : ViewingRecoveryRemote {
        override suspend fun fetch(session: SupabaseSession, viewingId: String): JSONObject? {
            boundary += "fetch"
            require(session.userId == ViewingCommandFixture.owner)
            afterFetch()
            return current?.let { require(it.getString("id") == viewingId); JSONObject(it.toString()) }
        }
        override suspend fun confirm(entry: OutboxEntity): PushResult {
            boundary += "confirm"; attempted += entry
            nextResult?.let { nextResult = null; return it }
            val operations = viewingCommandOperations(entry)
            val operation = operations.getJSONObject(0)
            val row = current ?: return PushResult.Review("Missing")
            if (operation.has("expectedUpdatedAt") && operation.getString("expectedUpdatedAt") != row.getString("updated_at"))
                return PushResult.Review("Changed")
            val effect = JSONObject(row.toString())
            operation.optJSONObject("values")?.let { values -> values.keys().forEach { effect.put(it, values.get(it)) } }
            effect.put("updated_at", "2026-10-08T12:00:00Z")
            val result = JSONObject().put("table", "viewings").put("key", operation.getJSONObject("key"))
            if (operation.getString("action") == "delete") { result.put("deleted", true); current = null }
            else { result.put("row", effect); current = effect }
            afterConfirm()
            return PushResult.Applied(JSONObject().put("receipt", JSONObject().put("operationId", entry.id)
                .put("rows", JSONArray().put(result))).put("current", current ?: JSONObject.NULL))
        }
    }
    private fun source() = ViewingRecoveryRepository(db, ViewingCommandFixture.owner, { session }, outbox, archive, remote) { action ->
        boundary += "replay"; action()
    }
    private fun saved(action: String = "update") = ViewingCommandFixture.entry(
        action, if (action == "delete") JSONObject() else JSONObject().put("notes", "Saved note").put("rating", 2)
    ).copy(operation = "review")
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity) = PushResult.Retry("fixture")
        }, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db))
        db.titleDao().upsertAll(listOf(TitleEntity(ViewingCommandFixture.title, 42, "MOVIE", "Film", 2026, null,
            emptyList(), null, null, null, 90, null, "WATCHED", null, null,
            ViewingCommandFixture.baseline, ViewingCommandFixture.baseline)))
        db.viewingDao().upsert(ViewingCommandFixture.applied(saved().copy(operation = VIEWING_COMMAND)).toCompletionViewing())
        db.outboxDao().enqueue(saved())
        repository = source()
    }
    @After fun close() { db.close() }

    @Test fun explicitSelectedFieldsPreserveOtherCurrentValuesAndOriginalExport() = runBlocking {
        val review = repository.review(saved().id)
        assertEquals(setOf("rating", "notes"), review.fields.map { it.key }.toSet())
        assertEquals("Remote note", review.fields.single { it.key == "notes" }.current)
        boundary.clear()
        assertEquals(OutingRecoveryOutcome.APPLIED, repository.apply(saved().id, review.remoteVersion, setOf("notes")))
        assertEquals(listOf("replay", "fetch", "confirm"), boundary)
        val local = db.viewingDao().getById(ViewingCommandFixture.viewing)!!
        assertEquals("Saved note", local.notes); assertEquals(4.0, local.rating!!, 0.0)
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertEquals(saved().payloadJson, JSONObject(repository.exportOriginal(saved().id)).getJSONObject("original").getString("payloadJson"))
        assertTrue(repository.items().single().resolved)
    }

    @Test fun staleReviewDoesNotRebaseAndCanBeExplicitlyReviewedAgain() = runBlocking {
        val review = repository.review(saved().id)
        current!!.put("updated_at", "2026-10-08T11:00:00Z")
        assertEquals(OutingRecoveryOutcome.CHANGED, repository.apply(saved().id, review.remoteVersion, setOf("notes")))
        assertFalse(repository.pendingAttempt(saved().id))
        assertEquals("Remote note", current!!.getString("notes"))
        val refreshed = repository.review(saved().id)
        assertEquals(OutingRecoveryOutcome.APPLIED, repository.apply(saved().id, refreshed.remoteVersion, setOf("notes")))
        assertNotEquals(attempted[0].id, attempted[1].id)
    }

    @Test fun unknownResolutionSurvivesRecreationAndDisablesDiscardOrChangedIntent() = runBlocking {
        val review = repository.review(saved().id)
        nextResult = PushResult.Retry("Unknown outcome")
        assertTrue(runCatching { repository.apply(saved().id, review.remoteVersion, setOf("notes")) }.isFailure)
        assertTrue(repository.pendingAttempt(saved().id))
        assertTrue(runCatching { repository.discard(saved().id) }.isFailure)
        repository = source()
        assertEquals(OutingRecoveryOutcome.APPLIED, repository.apply(saved().id, null, setOf("rating")))
        assertEquals(attempted[0].id, attempted[1].id)
        assertEquals(attempted[0].payloadJson, attempted[1].payloadJson)
        assertEquals(4.0, db.viewingDao().getById(ViewingCommandFixture.viewing)!!.rating!!, 0.0)
    }

    @Test fun corruptedResolutionCannotChangeTargetOrRetainedFields() = runBlocking {
        val review = repository.review(saved().id)
        nextResult = PushResult.Retry("Unknown")
        runCatching { repository.apply(saved().id, review.remoteVersion, setOf("notes")) }
        val record = JSONObject(records.value.getValue(saved().id))
        val attempt = record.getJSONObject("attempt")
        val replacement = ViewingCommandFixture.entry(fields = JSONObject().put("notes", "Unapproved replacement"), id = attempt.getString("id"))
        attempt.put("payloadJson", replacement.payloadJson)
        archive.put(saved().id, record.toString())
        assertTrue(runCatching { repository.apply(saved().id, null, emptySet()) }.isFailure)
        assertEquals(1, attempted.size)
        assertTrue(repository.pendingAttempt(saved().id))
        assertTrue(db.outboxDao().getPending().isNotEmpty())
    }

    @Test fun exactDeletePreservesOtherHistoryAndTitleMetadata() = runBlocking {
        db.outboxDao().enqueue(saved("delete"))
        val linked = viewingLinkedOuting("linked", ViewingCommandFixture.viewing)
        val otherLink = viewingLinkedOuting("other", ViewingCommandFixture.nextOperation)
        db.cinemaOutingDao().upsertAll(listOf(linked, otherLink))
        val other = db.viewingDao().getById(ViewingCommandFixture.viewing)!!.copy(id = ViewingCommandFixture.nextOperation)
        db.viewingDao().upsert(other)
        val title = db.titleDao().getById(ViewingCommandFixture.title)
        val review = repository.review(saved().id)
        assertEquals(listOf("delete"), review.fields.filter { it.selectable }.map { it.key })
        assertEquals(OutingRecoveryOutcome.APPLIED, repository.apply(saved().id, review.remoteVersion, setOf("delete")))
        assertNull(db.viewingDao().getById(ViewingCommandFixture.viewing))
        assertEquals(other, db.viewingDao().getById(other.id))
        assertEquals(title, db.titleDao().getById(ViewingCommandFixture.title))
        assertEquals(linked.copy(completedViewingId = null), db.cinemaOutingDao().getById(linked.id))
        assertEquals(otherLink, db.cinemaOutingDao().getById(otherLink.id))
    }

    @Test fun missingViewingCannotBeRecreatedAndDiscardPreservesLaterIntent() = runBlocking {
        val later = ViewingCommandFixture.entry(fields = JSONObject().put("venue", "Later draft"),
            predecessor = saved().id, id = ViewingCommandFixture.nextOperation)
        db.outboxDao().enqueue(later)
        current = null
        val review = repository.review(saved().id)
        assertFalse(review.remoteExists)
        assertEquals(OutingRecoveryOutcome.MISSING, repository.apply(saved().id, ViewingCommandFixture.baseline, setOf("notes")))
        assertTrue(attempted.isEmpty())
        repository.discard(saved().id)
        assertNull(db.viewingDao().getById(ViewingCommandFixture.viewing))
        assertEquals(later, db.outboxDao().getPending().single())
        assertTrue(repository.exportOriginal(saved().id).contains("Saved note"))
    }

    @Test fun originalUnknownCommandRequiresSameOperationConfirmationBeforeDiscard() = runBlocking {
        val original = saved().copy(operation = VIEWING_COMMAND, attemptCount = 1)
        db.outboxDao().enqueue(original)
        val review = repository.review(original.id)
        assertTrue(review.pendingAttempt)
        assertTrue(runCatching { repository.discard(original.id) }.isFailure)
        assertEquals(OutingRecoveryOutcome.CONFIRMED, repository.apply(original.id, null, emptySet()))
        assertEquals(original.id, attempted.single().id)
        assertEquals(original.payloadJson, attempted.single().payloadJson)
    }

    @Test fun unknownCommandCannotOvertakeEarlierPendingWork() = runBlocking {
        db.outboxDao().remove(saved().id)
        val earlier = OutboxEntity(ViewingCommandFixture.nextOperation, "title", ViewingCommandFixture.title, "update", "{}", 1)
        db.outboxDao().enqueue(earlier)
        val original = saved().copy(operation = VIEWING_COMMAND, attemptCount = 1)
        db.outboxDao().enqueue(original)
        assertTrue(runCatching { repository.apply(original.id, null, emptySet()) }.isFailure)
        assertTrue(attempted.isEmpty())
        assertEquals(listOf(earlier, original), db.outboxDao().getPending())
    }

    @Test fun accountSwitchAfterReadOrWritePublishesNoOldOwnerProjection() = runBlocking {
        afterFetch = { session = null }
        assertTrue(runCatching { repository.review(saved().id) }.isFailure)
        session = SupabaseSession("owner-token", ViewingCommandFixture.owner); afterFetch = {}
        val review = repository.review(saved().id)
        val local = db.viewingDao().getById(ViewingCommandFixture.viewing)
        afterConfirm = { session = null }
        assertTrue(runCatching { repository.apply(saved().id, review.remoteVersion, setOf("notes")) }.isFailure)
        assertEquals(local, db.viewingDao().getById(ViewingCommandFixture.viewing))
        assertTrue(db.outboxDao().getPending().isNotEmpty())
        assertTrue(JSONObject(records.value.getValue(saved().id)).has("attempt"))
    }

    @Test fun unreadableArchiveIsIndividuallyExportableAndDoesNotHideOtherCards() = runBlocking {
        archive.put("bad", "{preserved raw bytes")
        val cards = repository.items()
        assertEquals(2, cards.size)
        assertNotNull(cards.single { it.id == "bad" }.error)
        assertEquals("{preserved raw bytes", repository.exportOriginal("bad"))
        assertTrue(runCatching { repository.discard("bad") }.isFailure)
        assertNotNull(repository.review(saved().id))
    }

    @Test fun canonicalAliasTargetsOnlyItsProvenEventAndRemovesOnlyBoundDraft() = runBlocking {
        val canonical = "66666666-6666-4666-8666-666666666666"
        db.viewingCompletionAliasDao().insert(ViewingCompletionAliasEntity(ViewingCommandFixture.viewing, canonical,
            ViewingCommandFixture.title, ViewingCommandFixture.nextOperation, ViewingCommandFixture.operation, null))
        current!!.put("id", canonical)
        db.viewingDao().upsert(current!!.toCompletionViewing())
        val review = repository.review(saved().id)
        assertEquals(OutingRecoveryOutcome.APPLIED, repository.apply(saved().id, review.remoteVersion, setOf("notes")))
        assertEquals(canonical, attempted.single().entityId)
        assertNull(db.viewingDao().getById(ViewingCommandFixture.viewing))
        assertEquals("Saved note", db.viewingDao().getById(canonical)!!.notes)
    }
}
