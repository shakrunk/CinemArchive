package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus

@RunWith(RobolectricTestRunner::class)
class TitleMetadataRoomTest {
    private lateinit var db: LibraryDatabase
    private lateinit var outbox: MutationOutbox
    private var active = true
    private var failAck = false
    private var current: JSONObject? = TitleMetadataFixture.row()
    private var deliver: suspend (OutboxEntity) -> PushResult = { PushResult.Retry("offline") }
    private val pushed = mutableListOf<OutboxEntity>()
    private val session = SessionSource { if (active) SupabaseSession("token", TitleMetadataFixture.owner) else null }
    private val remote = object : TitleMetadataRemote {
        override suspend fun current(titleId: String) = current?.let { JSONObject(it.toString()) }
        override suspend fun push(entry: OutboxEntity) = error("Use the durable queue")
    }
    private fun configure() {
        outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity): PushResult { pushed += entry; return deliver(entry) }
        }, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db),
            AppliedMutationHandler { entry, envelope ->
                TitleMetadataApplier(db, TitleMetadataFixture.owner).apply(entry, envelope)
                check(!failAck) { "Disk full" }
            })
    }
    private fun recovery() = TitleMetadataRepository(db, outbox, TitleMetadataFixture.owner, session, remote,
        synchronize = outbox::flush, replayBoundary = { it() })
    private fun library() = TitleMetadataFixture.library(db, outbox)
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        configure(); db.titleDao().upsertAll(listOf(TitleMetadataFixture.entity()))
    }
    @After fun close() { db.close() }

    @Test fun tagsStatusRatingChainUsesReceiptNotOptimisticClockAndClearIsExplicit() = runBlocking {
        library().updateTitleTags(TitleMetadataFixture.title, listOf("Saved", "saved"))
        library().updateTitleStatus(TitleMetadataFixture.title, LibraryStatus.WATCHING, "2099-01-01T00:00:00Z")
        library().updateTitleRating(TitleMetadataFixture.title, 4.5, "1900-01-01T00:00:00Z")
        library().updateTitleTags(TitleMetadataFixture.title, emptyList())
        val queue = db.outboxDao().getPending()
        assertEquals(4, queue.size)
        assertEquals(TitleMetadataFixture.baseline, titleMetadataOperation(queue[0], TitleMetadataFixture.owner).getString("expectedUpdatedAt"))
        queue.drop(1).forEachIndexed { i, entry -> assertEquals(queue[i].id, titleMetadataOperation(entry, TitleMetadataFixture.owner).getString("expectedOperationId")) }
        assertEquals(0, titleMetadataPatch(queue.last(), TitleMetadataFixture.owner).getJSONArray("tags").length())
        val local = db.titleDao().getById(TitleMetadataFixture.title)!!
        assertEquals(TitleMetadataFixture.baseline, local.updatedAt)
        assertEquals("WATCHING", local.status); assertEquals(4.5, local.rating!!, 0.0)
        assertTrue(outbox.pendingEntityKeys().containsAll(setOf("title:${local.id}", "title_metadata:${local.id}")))
    }

    @Test fun enqueueFailureRollsBackOptimisticTagChange() = runBlocking {
        val failing = object : OutboxDao by db.outboxDao() { override suspend fun enqueue(entry: OutboxEntity) { error("Disk full") } }
        val box = MutationOutbox(failing, object : RemoteMutationWriter { override suspend fun push(entry: OutboxEntity) = PushResult.Success },
            TitleConflictHandler(db.titleDao()), RoomTransactor(db))
        assertTrue(runCatching { TitleMetadataFixture.library(db, box).updateTitleTags(TitleMetadataFixture.title, emptyList()) }.isFailure)
        assertEquals(listOf("Original"), db.titleDao().getById(TitleMetadataFixture.title)!!.tags)
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun legacyPendingWriteRetainsDraftWithoutGuessingBaseline() = runBlocking {
        val legacy = OutboxEntity("legacy", "title", TitleMetadataFixture.title, "update", """{"id":"${TitleMetadataFixture.title}","status":"WATCHING","updatedAt":"2099-01-01T00:00:00Z"}""", 1)
        db.outboxDao().enqueue(legacy)
        library().updateTitleTags(TitleMetadataFixture.title, listOf("Offline draft"))
        val draft = db.outboxDao().getPending().last()
        assertEquals("review", draft.operation)
        assertFalse(JSONObject(draft.payloadJson).getJSONObject(TITLE_METADATA_DATA).has("operation"))
        assertTrue(runCatching { recovery().compare(TitleMetadataFixture.title) }.isFailure)
        assertEquals(legacy, db.outboxDao().getPending().first())
        db.outboxDao().remove(legacy.id) // Legacy writer has now confirmed its own immutable write.
        val comparison = recovery().compare(TitleMetadataFixture.title)
        assertEquals(listOf("Offline draft"), comparison.saved.tags)
    }

    @Test fun oldReceiptAdoptsFreshCatalogFieldsButPreservesLaterLocalIntentAndHistory() = runBlocking {
        library().updateTitleTags(TitleMetadataFixture.title, listOf("First"))
        val first = db.outboxDao().getPending().single()
        library().updateTitleTags(TitleMetadataFixture.title, listOf("Later"))
        val later = db.outboxDao().getPending().last()
        db.viewingDao().upsert(ViewingEntity("history", TitleMetadataFixture.title, "2026-01-01", 3.5, "Keep history", null))
        deliver = { if (it.id == first.id) PushResult.Applied(TitleMetadataFixture.envelope(first,
            TitleMetadataFixture.effect(first).put("title", "Remote catalog title").put("tags", org.json.JSONArray(listOf("Remote newer"))))) else PushResult.Retry("offline") }
        outbox.flush()
        val row = db.titleDao().getById(TitleMetadataFixture.title)!!
        assertEquals("Remote catalog title", row.title); assertEquals(listOf("Later"), row.tags)
        assertEquals(later.payloadJson, db.outboxDao().getPending().single().payloadJson)
        assertEquals("Keep history", db.viewingDao().getById("history")!!.notes)
    }

    @Test fun failedLocalAckKeepsExactOperationAndRollsBackProjection() = runBlocking {
        library().updateTitleTags(TitleMetadataFixture.title, listOf("Saved"))
        val entry = db.outboxDao().getPending().single()
        deliver = { PushResult.Applied(TitleMetadataFixture.envelope(entry, TitleMetadataFixture.effect(entry).put("title", "Remote"))) }
        failAck = true; outbox.flush()
        assertEquals("Film", db.titleDao().getById(TitleMetadataFixture.title)!!.title)
        assertEquals(entry.payloadJson, db.outboxDao().getPending().single().payloadJson)
        failAck = false; outbox.flush()
        assertEquals(listOf(entry.id, entry.id), pushed.map { it.id })
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun ackRequiresTransactionAndExactUnchangedQueueHead() = runBlocking {
        library().updateTitleTags(TitleMetadataFixture.title, listOf("First"))
        val first = db.outboxDao().getPending().single()
        library().updateTitleTags(TitleMetadataFixture.title, listOf("Later"))
        val later = db.outboxDao().getPending().last()
        val applier = TitleMetadataApplier(db, TitleMetadataFixture.owner)
        assertTrue(runCatching { applier.apply(first, TitleMetadataFixture.envelope(first)) }.isFailure)
        assertTrue(runCatching { outbox.atomically { applier.apply(later, TitleMetadataFixture.envelope(later)) } }.isFailure)
        val altered = first.copy(payloadJson = first.payloadJson.replace("First", "Different"))
        assertTrue(runCatching { outbox.atomically {
            db.titleDao().upsertAll(listOf(TitleMetadataFixture.entity().copy(title = "Must roll back")))
            applier.apply(altered, TitleMetadataFixture.envelope(altered))
        } }.isFailure)
        assertEquals("Film", db.titleDao().getById(TitleMetadataFixture.title)!!.title)
        assertEquals(listOf("Later"), db.titleDao().getById(TitleMetadataFixture.title)!!.tags)
        assertEquals(listOf(first, later), db.outboxDao().getPending())
    }

    @Test fun definiteConflictKeepsChainAndExplicitApplyReplacesFirstSlotWithNewId() = runBlocking {
        library().updateTitleTags(TitleMetadataFixture.title, listOf("First"))
        val first = db.outboxDao().getPending().single()
        val unrelated = OutboxEntity("other", "episode_rating", "episode", "upsert", "{}", 1)
        db.outboxDao().enqueue(unrelated)
        library().updateTitleRating(TitleMetadataFixture.title, 4.0, "2099-01-01T00:00:00Z")
        deliver = { PushResult.Review("Compare") }; outbox.flush()
        assertEquals(listOf(first.id), pushed.map { it.id })
        val comparison = recovery().compare(TitleMetadataFixture.title)
        current = TitleMetadataFixture.row().put("updated_at", "2026-10-08T05:00:00Z") // It changes after the displayed comparison.
        recovery().applySaved(comparison)
        val queue = db.outboxDao().getPending()
        assertEquals(2, queue.size); assertEquals(unrelated, queue.last()); assertNotEquals(first.id, queue.first().id)
        assertEquals(TitleMetadataFixture.baseline, titleMetadataOperation(queue.first(), TitleMetadataFixture.owner).getString("expectedUpdatedAt"))
        assertEquals(4.0, titleMetadataPatch(queue.first(), TitleMetadataFixture.owner).getDouble("rating"), 0.0)
        assertEquals(first.payloadJson, pushed.single().payloadJson)
    }

    @Test fun changedQueueSnapshotCannotBeAppliedOrDiscarded() = runBlocking {
        library().updateTitleTags(TitleMetadataFixture.title, listOf("First"))
        deliver = { PushResult.Review("Compare") }; outbox.flush()
        val comparison = recovery().compare(TitleMetadataFixture.title)
        library().updateTitleTags(TitleMetadataFixture.title, listOf("Newest"))
        assertTrue(runCatching { recovery().applySaved(comparison) }.isFailure)
        assertTrue(runCatching { recovery().discard(comparison) }.isFailure)
        assertEquals(2, db.outboxDao().getPending().size)
        assertEquals(listOf("Newest"), db.titleDao().getById(TitleMetadataFixture.title)!!.tags)
    }

    @Test fun uncertainOutcomeCannotBeRebasedOrDiscarded() = runBlocking {
        library().updateTitleTags(TitleMetadataFixture.title, listOf("Saved"))
        val entry = db.outboxDao().getPending().single(); outbox.flush()
        assertTrue(runCatching { recovery().compare(TitleMetadataFixture.title) }.isFailure)
        assertEquals(entry.payloadJson, db.outboxDao().getPending().single().payloadJson)
        deliver = { PushResult.Applied(TitleMetadataFixture.envelope(entry)) }; recovery().retrySync()
        assertEquals(listOf(entry.id, entry.id), pushed.map { it.id })
    }

    @Test fun discardRemovesOnlyChosenMetadataAndPreservesInterleavedLegacyIntent() = runBlocking {
        library().updateTitleTags(TitleMetadataFixture.title, listOf("Discard me"))
        deliver = { PushResult.Review("Compare") }; outbox.flush()
        db.outboxDao().enqueue(OutboxEntity("legacy-later", "title", TitleMetadataFixture.title, "update",
            """{"id":"${TitleMetadataFixture.title}","rating":4.5,"updatedAt":"2099-01-01T00:00:00Z"}""", 2))
        val comparison = recovery().compare(TitleMetadataFixture.title)
        current = TitleMetadataFixture.row().put("title", "Fresh name")
        recovery().discard(comparison)
        assertEquals("legacy-later", db.outboxDao().getPending().single().id)
        val row = db.titleDao().getById(TitleMetadataFixture.title)!!
        assertEquals(listOf("Original"), row.tags); assertEquals(4.5, row.rating!!, 0.0); assertEquals("Fresh name", row.title)
    }

    @Test fun deletedLocalTitleIsNotResurrectedAndDeletedServerTitleCannotBeApplied() = runBlocking {
        library().updateTitleTags(TitleMetadataFixture.title, listOf("Saved"))
        val entry = db.outboxDao().getPending().single()
        db.titleDao().deleteById(TitleMetadataFixture.title)
        deliver = { PushResult.Applied(TitleMetadataFixture.envelope(entry)) }; outbox.flush()
        assertNull(db.titleDao().getById(TitleMetadataFixture.title))
        db.titleDao().upsertAll(listOf(TitleMetadataFixture.entity()))
        library().updateTitleTags(TitleMetadataFixture.title, listOf("Missing"))
        deliver = { PushResult.Review("Missing") }; outbox.flush(); current = null
        val comparison = recovery().compare(TitleMetadataFixture.title)
        assertNull(comparison.current); assertTrue(runCatching { recovery().applySaved(comparison) }.isFailure)
        assertEquals(1, db.outboxDao().getPending().size)
    }

    @Test fun accountSwitchCannotResolveAnotherRuntimeIntent() = runBlocking {
        library().updateTitleTags(TitleMetadataFixture.title, listOf("Saved"))
        deliver = { PushResult.Review("Compare") }; outbox.flush()
        val comparison = recovery().compare(TitleMetadataFixture.title)
        active = false
        assertTrue(runCatching { recovery().applySaved(comparison) }.isFailure)
        assertTrue(runCatching { recovery().discard(comparison) }.isFailure)
        assertEquals(1, db.outboxDao().getPending().size)
    }

    @Test fun retainedLegacyDraftAndExactCommandSurviveDatabaseReopen() = runBlocking {
        db.close()
        fun open() = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java, "tm-reopen.db")
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        db = open(); configure(); db.titleDao().upsertAll(listOf(TitleMetadataFixture.entity()))
        db.outboxDao().enqueue(OutboxEntity("legacy", "title", TitleMetadataFixture.title, "insert", "{}", 1))
        library().updateTitleTags(TitleMetadataFixture.title, listOf("Retained"))
        val before = db.outboxDao().getPending()
        db.close(); db = open(); configure()
        assertEquals(before, db.outboxDao().getPending())
        assertEquals(listOf("Retained"), db.titleDao().getById(TitleMetadataFixture.title)!!.tags)
    }
}
