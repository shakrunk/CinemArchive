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
class ViewingCompoundTest {
    private lateinit var db: LibraryDatabase
    private lateinit var repo: LibraryRepository
    private val owner = ViewingProducerFixture.owner
    private val title = ViewingProducerFixture.title
    private val watch = ViewingProducerFixture.watch
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        ViewingProducerFixture.seed(db); db.viewingDao().upsert(ViewingProducerFixture.event())
        repo = ViewingProducerFixture.repository(db)
    }
    @After fun close() { db.close() }
    private fun outbox() = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
        override suspend fun push(entry: OutboxEntity) = PushResult.Retry("offline")
    }, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db))
    private suspend fun save() : OutboxEntity {
        repo.saveViewing(title, repo.prepareViewingEdit(title, watch).copy(rating = 4.5, notes = "Saved note"), false)
        return db.outboxDao().getPending().last()
    }
    private fun recovery(remote: ViewingRecoveryRemote) = ViewingRecoveryRepository(db, owner,
        { SupabaseSession("token", owner) }, outbox(), object : OutingRecoveryArchive {
            override val records = MutableStateFlow<Map<String, String>>(emptyMap())
            override suspend fun put(id: String, record: String) { records.value += id to record }
        }, remote, replayBoundary = { it() })
    private fun titleRecovery() = TitleMetadataRepository(db, outbox(), owner,
        SessionSource { SupabaseSession("token", owner) }, object : TitleMetadataRemote {
            override suspend fun current(titleId: String) = ViewingCommandFixture.titleRow().put("rating", 2.0)
            override suspend fun push(entry: OutboxEntity) = error("Use queue")
        }, synchronize = {}, replayBoundary = { it() })

    @Test fun compoundProtectsBothRowsAndLaterTitleEditsUseItsSingleReceipt() = runBlocking {
        val first = save()
        assertEquals(2, viewingCommandOperations(first).length())
        assertTrue(viewingHistoryProtectionKeys(listOf(first), owner).containsAll(setOf("title:$title", "viewing_history:$watch")))
        repo.updateTitleTags(title, listOf("Later tags"))
        val tags = db.outboxDao().getPending().last()
        assertEquals(first.id, titleMetadataOperation(tags, owner).getString("expectedOperationId"))
        repo.saveViewing(title, repo.prepareViewingEdit(title, watch).copy(rating = 3.0), false)
        val later = db.outboxDao().getPending().last()
        assertEquals(tags.id, viewingCommandOperations(later).getJSONObject(1).getString("expectedOperationId"))
        val currentTitle = ViewingCommandFixture.titleRow().put("title", "New remote catalog").put("rating", 1.0).put("updated_at", "2026-10-10T01:00:00Z")
        RoomTransactor(db).run {
            ViewingCommandApplier(db, owner).apply(first, ViewingCommandFixture.envelope(first).put("currentTitle", currentTitle))
            db.outboxDao().remove(first.id)
        }
        val projected = db.titleDao().getById(title)!!
        assertEquals("New remote catalog", projected.title); assertEquals(3.0, projected.rating!!, 0.0)
        assertEquals(listOf("Later tags"), projected.tags); assertEquals("2026-10-10T01:00:00Z", projected.updatedAt)
        assertEquals(listOf(tags, later), db.outboxDao().getPending())
    }

    @Test fun unknownTitleGuardRetainsTheWholeActionWithoutDispatchableViewing() = runBlocking {
        db.outboxDao().enqueue(OutboxEntity("old-title", "title", title, "update", "{}", 1))
        val entry = save()
        assertEquals("review", entry.operation)
        val payload = JSONObject(entry.payloadJson)
        assertFalse(payload.has(VIEWING_COMMAND_DATA)); assertTrue(payload.has(VIEWING_TITLE_EFFECT))
        assertFalse(payload.getJSONObject(VIEWING_TITLE_EFFECT).getJSONObject(TITLE_METADATA_DATA).has("operation"))
        assertEquals(4.5, db.viewingDao().getById(watch)!!.rating!!, 0.0)
    }

    @Test fun selectedNotesRecoveryDoesNotReapplyUnselectedTitleRating() = runBlocking {
        val original = save(); db.outboxDao().markForReview(original.id, "Definite conflict")
        var sent: OutboxEntity? = null
        val recovery = recovery(object : ViewingRecoveryRemote {
            override suspend fun fetch(session: SupabaseSession, viewingId: String) = ViewingCommandFixture.row().put("rating", 2.0)
            override suspend fun title(session: SupabaseSession, titleId: String) = ViewingCommandFixture.titleRow().put("rating", 2.0)
            override suspend fun confirm(entry: OutboxEntity): PushResult {
                sent = entry; return PushResult.Applied(ViewingCommandFixture.envelope(entry))
            }
        })
        val review = recovery.review(original.id)
        assertEquals(OutingRecoveryOutcome.APPLIED, recovery.apply(original.id, review.remoteVersion, setOf("notes")))
        assertEquals(1, viewingCommandOperations(sent!!).length())
        assertEquals(2.0, db.titleDao().getById(title)!!.rating!!, 0.0)
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun selectedRatingRecoveryKeepsDisplayedTitleGuardAndUnknownAttemptIdentity() = runBlocking {
        val original = save(); db.outboxDao().markForReview(original.id, "Definite conflict")
        var titleVersion = ViewingCommandFixture.baseline
        val sent = mutableListOf<OutboxEntity>()
        val recovery = recovery(object : ViewingRecoveryRemote {
            override suspend fun fetch(session: SupabaseSession, viewingId: String) = ViewingCommandFixture.row()
            override suspend fun title(session: SupabaseSession, titleId: String) = ViewingCommandFixture.titleRow().put("updated_at", titleVersion)
            override suspend fun confirm(entry: OutboxEntity): PushResult {
                sent += entry
                return if (sent.size == 1) PushResult.Retry("Lost response") else PushResult.Applied(ViewingCommandFixture.envelope(entry))
            }
        })
        val review = recovery.review(original.id)
        titleVersion = "2026-10-11T01:00:00Z"
        assertTrue(runCatching { recovery.apply(original.id, review.remoteVersion, setOf("rating")) }.isFailure)
        assertTrue(runCatching { recovery.discard(original.id) }.isFailure)
        assertEquals(OutingRecoveryOutcome.APPLIED, recovery.apply(original.id, null, emptySet()))
        assertEquals(sent.first(), sent.last())
        val ops = viewingCommandOperations(sent.first())
        assertEquals(2, ops.length()); assertEquals(ViewingCommandFixture.baseline, ops.getJSONObject(1).getString("expectedUpdatedAt"))
        assertEquals(4.5, db.titleDao().getById(title)!!.rating!!, 0.0)
    }

    @Test fun discardCannotDropCompoundProtectionWhenCurrentTitleReadFails() = runBlocking {
        val original = save(); db.outboxDao().markForReview(original.id, "Definite conflict")
        val recovery = recovery(object : ViewingRecoveryRemote {
            override suspend fun fetch(session: SupabaseSession, viewingId: String) = ViewingCommandFixture.row()
            override suspend fun title(session: SupabaseSession, titleId: String): JSONObject? = error("offline")
            override suspend fun confirm(entry: OutboxEntity) = error("No resend")
        })
        assertTrue(runCatching { recovery.discard(original.id) }.isFailure)
        assertEquals(original.payloadJson, db.outboxDao().getPending().single().payloadJson)
        assertEquals(4.5, db.titleDao().getById(title)!!.rating!!, 0.0)
    }

    @Test fun titleRecoveryResolvesFirstSegmentWithoutMergingAcrossCompoundViewing() = runBlocking {
        repo.updateTitleTags(title, listOf("First"))
        val first = db.outboxDao().getPending().single(); db.outboxDao().markForReview(first.id, "Conflict")
        val viewing = save()
        repo.updateTitleTags(title, listOf("Last"))
        val last = db.outboxDao().getPending().last()
        val recovery = titleRecovery()
        val review = recovery.compare(title)
        assertEquals(listOf(first.id), review.entries.map { it.first })
        recovery.discard(review)
        assertEquals(listOf(viewing.id, last.id), db.outboxDao().getPending().map { it.id })
        assertEquals(4.5, db.titleDao().getById(title)!!.rating!!, 0.0)
        assertEquals(listOf("Last"), db.titleDao().getById(title)!!.tags)
        assertTrue(runCatching { recovery.compare(title) }.isFailure)
    }

    @Test fun titleAckPreservesLaterCompoundAndUnsupportedIntentNeverOverwritesLocalDraft() = runBlocking {
        repo.updateTitleTags(title, listOf("First"))
        val first = db.outboxDao().getPending().single()
        save()
        val current = ViewingCommandFixture.titleRow().put("tags", org.json.JSONArray(listOf("First"))).put("rating", 1.0)
        val receipt = JSONObject().put("operationId", first.id).put("rows", org.json.JSONArray().put(JSONObject()
            .put("table", "titles").put("key", JSONObject().put("id", title)).put("row", current)))
        RoomTransactor(db).run {
            TitleMetadataApplier(db, owner).apply(first, JSONObject().put("receipt", receipt).put("current", current))
            db.outboxDao().remove(first.id)
        }
        assertEquals(4.5, db.titleDao().getById(title)!!.rating!!, 0.0)
        val compound = db.outboxDao().getPending().single()
        db.outboxDao().enqueue(OutboxEntity("unsupported", "title", title, "review", "{}", 3))
        val before = db.titleDao().getById(title)
        RoomTransactor(db).run { ViewingCommandApplier(db, owner).apply(compound, ViewingCommandFixture.envelope(compound)) }
        assertEquals(before, db.titleDao().getById(title))
    }
}
