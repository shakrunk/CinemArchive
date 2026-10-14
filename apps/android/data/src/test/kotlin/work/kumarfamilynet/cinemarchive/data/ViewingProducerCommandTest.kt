package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
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
class ViewingProducerCommandTest {
    private lateinit var db: LibraryDatabase
    private val title = ViewingProducerFixture.title
    private val watch = ViewingProducerFixture.watch
    private val owner = ViewingProducerFixture.owner
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        ViewingProducerFixture.seed(db); db.viewingDao().upsert(ViewingProducerFixture.event())
    }
    @After fun close() { db.close() }

    @Test fun restoredFormAfterAckAndDatabaseReopenRetainsBothOriginalOperationIdsAndGuards() = runBlocking {
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, "view-edit.db").setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        db = open(); ViewingProducerFixture.seed(db); db.viewingDao().upsert(ViewingProducerFixture.event())
        var repo = ViewingProducerFixture.repository(db)
        val draft = repo.prepareViewingEdit(title, watch).copy(rating = 4.5, notes = "Saved form")
        repo.saveViewing(title, draft, false)
        val original = db.outboxDao().getPending()
        original.forEach { db.outboxDao().remove(it.id) } // receipt accepted, caller loses its local result
        db.viewingDao().upsert(db.viewingDao().getById(watch)!!.copy(updatedAt = "2026-10-09T01:00:00Z"))
        db.titleDao().upsertAll(listOf(db.titleDao().getById(title)!!.copy(updatedAt = "2026-10-09T01:00:00Z")))
        db.close(); db = open(); repo = ViewingProducerFixture.repository(db)
        repo.saveViewing(title, draft.copy(openingContext = JSONObject(draft.openingContext!!).toString()), false)
        val retry = db.outboxDao().getPending()
        assertEquals(original.map { it.id }, retry.map { it.id })
        assertEquals(original.map { it.payloadJson }, retry.map { it.payloadJson })
        assertEquals(ViewingProducerFixture.revision, viewingCommandOperations(retry.first()).getJSONObject(0).getString("expectedUpdatedAt"))
        assertEquals(ViewingProducerFixture.revision, viewingCommandOperations(retry.single()).getJSONObject(1).getString("expectedUpdatedAt"))
    }

    @Test fun staleOpeningChangesOnlyEditedFieldsAndCannotAdoptNewerViewingOrTitleRevision() = runBlocking {
        val repo = ViewingProducerFixture.repository(db)
        val opened = repo.prepareViewingEdit(title, watch)
        db.viewingDao().upsert(ViewingProducerFixture.event().copy(venue = "New remote venue", updatedAt = "2026-10-09T01:00:00Z"))
        db.titleDao().upsertAll(listOf(db.titleDao().getById(title)!!.copy(status = "DROPPED", updatedAt = "2026-10-09T01:00:00Z")))
        repo.saveViewing(title, opened.copy(rating = 4.5), false)
        val pending = db.outboxDao().getPending()
        val op = viewingCommandOperations(pending.first()).getJSONObject(0)
        assertEquals(setOf("rating"), op.getJSONObject("values").keys().asSequence().toSet())
        assertEquals(ViewingProducerFixture.revision, op.getString("expectedUpdatedAt"))
        assertEquals(ViewingProducerFixture.revision, viewingCommandOperations(pending.single()).getJSONObject(1).getString("expectedUpdatedAt"))
        assertEquals("New remote venue", db.viewingDao().getById(watch)!!.venue)
        assertEquals("2026-10-09T01:00:00Z", db.viewingDao().getById(watch)!!.updatedAt)
        assertEquals("DROPPED", db.titleDao().getById(title)!!.status)
    }

    @Test fun newCanonicalEditorResolvesAliasAndUsesObservedRevisionButNeverRecreatesDeletedIdentity() = runBlocking {
        val provisional = ViewingProducerFixture.other
        val alias = ViewingCompletionAliasEntity(provisional, watch, title, ViewingProducerFixture.outing,
            ViewingCommandFixture.operation, ViewingProducerFixture.revision)
        db.viewingCompletionAliasDao().insert(alias)
        val current = ViewingProducerFixture.event().copy(updatedAt = "2026-10-09T01:00:00Z")
        db.viewingDao().upsert(current)
        val repo = ViewingProducerFixture.repository(db)
        val opened = repo.prepareViewingEdit(title, provisional)
        assertEquals(watch, opened.id)
        repo.saveViewing(title, opened.copy(notes = "Canonical edit"), false)
        assertEquals(current.updatedAt, viewingCommandOperations(db.outboxDao().getPending().single()).getJSONObject(0).getString("expectedUpdatedAt"))
        db.viewingDao().deleteById(watch)
        assertTrue(runCatching { repo.prepareViewingEdit(title, provisional) }.isFailure)
        assertNull(db.viewingDao().getById(provisional))
    }

    @Test fun unknownRevisionAndLegacyPredecessorRemainNeverDispatchedReviewDrafts() = runBlocking {
        db.viewingDao().upsert(ViewingProducerFixture.event().copy(updatedAt = null))
        val repo = ViewingProducerFixture.repository(db)
        repo.saveViewing(title, repo.prepareViewingEdit(title, watch).copy(notes = "Review this"), false)
        val unknown = db.outboxDao().getPending().single()
        assertEquals("review", unknown.operation)
        assertFalse(JSONObject(unknown.payloadJson).has(VIEWING_COMMAND_DATA))
        assertEquals("Review this", JSONObject(unknown.payloadJson).getJSONObject(VIEWING_REVIEW_INTENT).getJSONObject("fields").getString("notes"))
        db.outboxDao().remove(unknown.id)
        val legacy = unknown.copy(operation = "update", payloadJson = JSONObject().put("id", watch).put("titleId", title).put("notes", "Legacy").toString())
        db.outboxDao().enqueue(legacy)
        db.viewingDao().upsert(ViewingProducerFixture.event())
        repo.saveViewing(title, repo.prepareViewingEdit(title, watch).copy(notes = "After legacy"), false)
        assertEquals("review", db.outboxDao().getPending().last().operation)
    }

    @Test fun accountSwitchDuringAdmissionRollsBackEventTitleAndQueue() = runBlocking {
        var active = true
        val dao = object : OutboxDao by db.outboxDao() {
            override suspend fun enqueue(entry: OutboxEntity) { db.outboxDao().enqueue(entry); active = false }
        }
        val repo = ViewingProducerFixture.repository(db, dao) { active }
        val opened = repo.prepareViewingEdit(title, watch)
        val originalTitle = db.titleDao().getById(title)
        assertTrue(runCatching { repo.saveViewing(title, opened.copy(rating = 5.0), false) }.isFailure)
        assertEquals(ViewingProducerFixture.event(), db.viewingDao().getById(watch))
        assertEquals(originalTitle, db.titleDao().getById(title)); assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun deleteConflictDiscardRequiresOwnedOutingRefreshAndRestoresAuthoritativeLinkRevision() = runBlocking {
        db.cinemaOutingDao().upsert(ViewingProducerFixture.outing())
        val repo = ViewingProducerFixture.repository(db)
        repo.deleteViewing(title, repo.prepareViewingEdit(title, watch))
        val entry = db.outboxDao().getPending().single()
        db.outboxDao().markForReview(entry.id, "Viewing changed")
        var failRead = true
        val currentOuting = ViewingCommandFixture.linkedOuting().put("completed_viewing_id", watch)
        val recovery = recovery(object : ViewingRecoveryRemote {
            override suspend fun fetch(session: SupabaseSession, viewingId: String) = ViewingCommandFixture.row()
            override suspend fun confirm(entry: OutboxEntity) = error("Discard does not resend a confirmed rejection")
            override suspend fun linkedOutings(session: SupabaseSession, ids: List<String>): JSONObject {
                assertEquals(owner, session.userId); assertEquals(listOf(ViewingProducerFixture.outing), ids)
                if (failRead) error("Offline")
                return JSONObject().put(ViewingProducerFixture.outing, currentOuting)
            }
        })
        assertTrue(runCatching { recovery.discard(entry.id) }.isFailure)
        assertEquals(entry.payloadJson, db.outboxDao().getPending().single().payloadJson)
        assertNull(db.cinemaOutingDao().getById(ViewingProducerFixture.outing)!!.completedViewingId)
        failRead = false; recovery.discard(entry.id)
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertEquals(currentOuting.toRecoveryOuting(), db.cinemaOutingDao().getById(ViewingProducerFixture.outing))
        assertNotNull(db.viewingDao().getById(watch))
    }

    @Test fun unknownDeleteRecoveryConfirmsOriginalIdentityBeforeUsingCurrentOutingProjection() = runBlocking {
        db.cinemaOutingDao().upsert(ViewingProducerFixture.outing())
        val repo = ViewingProducerFixture.repository(db)
        repo.deleteViewing(title, repo.prepareViewingEdit(title, watch))
        val entry = db.outboxDao().getPending().single()
        db.outboxDao().recordFailure(entry.id, "Unknown response")
        var sent: OutboxEntity? = null
        val current = ViewingCommandFixture.linkedOuting()
        val recovery = recovery(object : ViewingRecoveryRemote {
            override suspend fun fetch(session: SupabaseSession, viewingId: String): JSONObject? = null
            override suspend fun confirm(entry: OutboxEntity): PushResult {
                sent = entry
                return PushResult.Applied(ViewingCommandFixture.envelope(entry, null)
                    .put("currentOutings", JSONObject().put(ViewingProducerFixture.outing, current)))
            }
        })
        assertEquals(OutingRecoveryOutcome.CONFIRMED, recovery.apply(entry.id, null, emptySet()))
        assertEquals(entry.id, sent!!.id); assertEquals(entry.payloadJson, sent!!.payloadJson)
        assertEquals(current.toRecoveryOuting(), db.cinemaOutingDao().getById(ViewingProducerFixture.outing))
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    private fun recovery(remote: ViewingRecoveryRemote): ViewingRecoveryRepository {
        val outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter { override suspend fun push(entry: OutboxEntity) = PushResult.Retry("Offline") },
            TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db))
        val archive = object : OutingRecoveryArchive {
            override val records = MutableStateFlow<Map<String, String>>(emptyMap())
            override suspend fun put(id: String, record: String) { records.value += id to record }
        }
        return ViewingRecoveryRepository(db, owner, { SupabaseSession("token", owner) }, outbox, archive, remote, replayBoundary = { it() })
    }
}
