package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*

@RunWith(RobolectricTestRunner::class)
class EpisodeBulkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LibraryDatabase
    private lateinit var repo: EpisodeBulkRepository
    private lateinit var outbox: MutationOutbox
    private var active = true
    private var replays = 0
    private var replaying = false
    private var syncRequests = 0
    private var remoteSnapshot = EpisodeBulkSnapshot(null, emptyList(), emptySet())
    private val owner = id(1)
    private val title = id(2)
    private val revision = "2026-10-08T12:00:00Z"
    private fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, "bulk.db").setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
    private fun configure(dao: OutboxDao = db.outboxDao()) {
        outbox = MutationOutbox(dao, object : RemoteMutationWriter { override suspend fun push(entry: OutboxEntity) = PushResult.Success },
            TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db))
        repo = EpisodeBulkRepository(db, outbox, owner, { active }, object : EpisodeBulkRemote {
            override suspend fun current(titleId: String) = remoteSnapshot
            override suspend fun push(entry: OutboxEntity) = PushResult.Success
        }, {}, { action ->
            replays++; replaying = true
            try { action() } finally { replaying = false }
        }, requestSync = {
            assertFalse(replaying); assertFalse(db.inTransaction())
            // A nested acquisition would suspend forever if recovery still held the FIFO lock.
            outbox.withFlushPaused { syncRequests++ }
        })
    }
    @Before fun setup() = runBlocking {
        context.deleteDatabase("bulk.db"); db = open(); configure()
        db.titleDao().upsertAll(listOf(TitleEntity(title, 42, "TV", "Series", 2020, null, emptyList(), null, null, null, 30, null,
            "WATCHING", 4.0, "Title notes", revision, revision, null, null, null)))
        db.seasonDao().upsertAll(listOf(SeasonEntity(id(10), title, 1, 3, 0, 2020, revision),
            SeasonEntity(id(11), title, 0, 1, 0, 2020, revision), SeasonEntity(id(12), title, 2, 5, 2, 2021, revision)))
        db.episodeDao().upsertAll(listOf(EpisodeEntity(id(20), title, id(10), 1, "One", "2099-01-01", 30),
            EpisodeEntity(id(21), title, id(10), 2, "Two", null, 30), EpisodeEntity(id(22), title, id(10), 3, "Three", null, 30),
            EpisodeEntity(id(23), title, id(11), 1, "Special", null, 30)))
        db.episodeWatchEventDao().upsertAll(listOf(EpisodeWatchEventEntity(id(30), id(20), "2026-01-01", "Keep", "bw"),
            EpisodeWatchEventEntity(id(31), id(20), null, "Rewatch", "color")))
        db.episodeRatingDao().upsertAll(listOf(EpisodeRatingEntity(id(32), id(20), 4.5, revision)))
        db.episodeReviewDao().upsertAll(listOf(EpisodeReviewEntity(id(33), id(20), "Review", revision, "bw")))
        remoteSnapshot = EpisodeBulkSnapshot(JSONObject().put("status", "watching").put("updated_at", revision),
            listOf(JSONObject().put("id", id(12)).put("episode_count", 5).put("episodes_watched", 2).put("updated_at", revision)), emptySet())
    }
    @After fun close() { db.close(); context.deleteDatabase("bulk.db") }
    private suspend fun pending() = db.outboxDao().getPending().single()
    private suspend fun watches() = db.episodeWatchEventDao().observeAllWatchEvents().first()

    @Test fun entireSeriesAddsOnlyUnwatchedUndatedEventsCompletesCoarseAndPreservesEverythingElse() = runBlocking {
        val opening = repo.prepare(title, null)
        assertEquals(2, opening.count); assertEquals(1, opening.coarseCount)
        repo.save(opening)
        assertEquals(4, watches().size)
        assertTrue(watches().filter { it.id !in setOf(id(30), id(31)) }.all { it.watchedAt == null && it.notes == null })
        assertFalse(watches().any { it.episodeId == id(23) })
        assertEquals("WATCHED", db.titleDao().getById(title)!!.status)
        assertEquals(5, db.seasonDao().observeSeasons(title).first().find { it.id == id(12) }!!.episodesWatched)
        assertEquals(listOf("bw", "color"), watches().filter { it.episodeId == id(20) }.map { it.colorMode })
        assertEquals(1, db.episodeRatingDao().observeRatings(title).first().size)
        assertEquals("Review", db.episodeReviewDao().observeReviews(title).first().single().reviewText)
        val operations = bulkOperations(pending(), owner)
        assertEquals(listOf("episode_watch_events", "episode_watch_events", "seasons", "titles"), (0 until operations.length()).map { operations.getJSONObject(it).getString("table") })
        assertEquals(revision, operations.getJSONObject(2).getString("expectedUpdatedAt"))
        assertEquals(revision, operations.getJSONObject(3).getString("expectedUpdatedAt"))
        assertTrue(outbox.pendingEntityKeys().containsAll(setOf("episode_bulk:$title", "title:$title", "season:${id(12)}")))
    }

    @Test fun selectedSpecialsDoesNotChangeSeriesStatusOrOtherSeasons() = runBlocking {
        repo.save(repo.prepare(title, 0))
        assertTrue(watches().any { it.episodeId == id(23) && it.watchedAt == null })
        assertFalse(watches().any { it.episodeId == id(21) })
        assertEquals("WATCHING", db.titleDao().getById(title)!!.status)
        assertEquals(1, bulkOperations(pending(), owner).length())
    }

    @Test fun coarseOnlySeasonAndSeriesCanCompleteOfflineWithoutFabricatedEvents() = runBlocking {
        val opening = repo.prepare(title, 2)
        assertEquals(0, opening.count); assertEquals(1, opening.coarseCount)
        repo.save(opening)
        assertEquals(2, watches().size)
        assertEquals("WATCHING", db.titleDao().getById(title)!!.status)
        assertEquals(1, bulkOperations(pending(), owner).length())
        db.outboxDao().remove(pending().id)
        db.episodeWatchEventDao().upsertAll(listOf(EpisodeWatchEventEntity(id(50), id(21), null), EpisodeWatchEventEntity(id(51), id(22), null)))
        repo.save(repo.prepare(title, null))
        assertEquals("WATCHED", db.titleDao().getById(title)!!.status)
        assertEquals(4, watches().size)
        db.outboxDao().remove(pending().id)
        try { repo.prepare(title, null); fail("Already complete") } catch (_: IllegalStateException) { }
    }

    @Test fun newerTitleEditDuringConfirmationIsNeverOverwritten() = runBlocking {
        val opening = repo.prepare(title, null)
        val original = db.titleDao().getById(title)!!
        db.titleDao().upsertAll(listOf(original.copy(status = "DROPPED")))
        try { repo.save(opening); fail("Stale capture") } catch (_: IllegalStateException) { }
        assertEquals("DROPPED", db.titleDao().getById(title)!!.status)
        assertEquals(2, watches().size); assertTrue(db.outboxDao().getPending().isEmpty())
        db.titleDao().upsertAll(listOf(original))
        outbox.enqueueTitleMetadata(original, JSONObject().put("rating", 3.0), owner)
        try { repo.save(opening); fail("New predecessor") } catch (_: IllegalStateException) { }
        assertEquals(2, watches().size); assertEquals("title", pending().entityType)
    }

    @Test fun admissionSurvivesAckRemovalReopenAndLaterDeletionWithoutResurrecting() = runBlocking {
        val opening = repo.prepare(title, 1)
        repo.save(opening)
        val inserted = watches().first { it.episodeId == id(21) }
        db.outboxDao().remove(pending().id)
        db.episodeWatchEventDao().deleteById(inserted.id)
        db.close(); db = open(); configure()
        repo.save(EpisodeBulkOpening(opening.json))
        assertFalse(watches().any { it.id == inserted.id })
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun queueFailureAndOwnerLossRollBackProjectionAndAdmission() = runBlocking {
        val opening = repo.prepare(title, null)
        configure(object : OutboxDao by db.outboxDao() {
            override suspend fun enqueue(entry: OutboxEntity) { throw IllegalStateException("Full disk") }
        })
        try { repo.save(opening); fail() } catch (_: IllegalStateException) { }
        assertEquals(2, watches().size); assertEquals("WATCHING", db.titleDao().getById(title)!!.status)
        assertNull(db.episodeBulkAdmissionDao().payload(opening.operationId))
        configure(object : OutboxDao by db.outboxDao() {
            override suspend fun enqueue(entry: OutboxEntity) { db.outboxDao().enqueue(entry); active = false }
        })
        try { repo.save(opening); fail() } catch (_: IllegalStateException) { }
        assertEquals(2, watches().size); assertTrue(db.outboxDao().getPending().isEmpty())
        assertNull(db.episodeBulkAdmissionDao().payload(opening.operationId))
    }

    @Test fun newlyWatchedEpisodeDuringConfirmationRequiresReopenInsteadOfDuplicating() = runBlocking {
        val opening = repo.prepare(title, 1)
        db.episodeWatchEventDao().upsertAll(listOf(EpisodeWatchEventEntity(id(40), id(21), "2026-01-01")))
        try { repo.save(opening); fail() } catch (_: IllegalStateException) { }
        assertEquals(3, watches().size); assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun missingCoarseRevisionRetainsOfflineReviewWithoutInventedGuard() = runBlocking {
        val coarse = db.seasonDao().observeSeasons(title).first().find { it.id == id(12) }!!
        db.seasonDao().upsertAll(listOf(coarse.copy(updatedAt = null)))
        repo.save(repo.prepare(title, null))
        assertEquals("review", pending().operation)
        assertEquals(4, watches().size)
        assertFalse(bulkOperations(pending(), owner, false).getJSONObject(2).has("expectedUpdatedAt"))
        assertTrue(repo.pending(title).first().single().needsReview)
    }

    @Test fun knownReviewComparesRemoteWatchesAndExplicitlyReplacesWithoutDuplicateHistory() = runBlocking {
        repo.save(repo.prepare(title, null))
        val old = pending(); db.outboxDao().markForReview(old.id, "Stale revision")
        remoteSnapshot = remoteSnapshot.copy(watchedEpisodeIds = setOf(id(21)))
        val comparison = repo.compare(old.id)
        assertEquals(1, comparison.replacement.count)
        repo.applyReviewed(comparison)
        assertEquals(1, replays)
        assertNotEquals(old.id, pending().id)
        assertEquals(EPISODE_BULK_COMMAND, pending().operation)
        assertEquals(3, watches().size)
        assertFalse(watches().any { it.episodeId == id(21) }) // next epoch pull restores the actual remote event, not our rejected provisional ID
        assertTrue(watches().any { it.episodeId == id(22) })
    }

    @Test fun uncertainDeliveryCannotBeRebasedOrDiscardedAndLocalEditsInvalidateComparison() = runBlocking {
        repo.save(repo.prepare(title, null))
        val saved = pending()
        try { repo.compare(saved.id); fail() } catch (_: IllegalStateException) { }
        try { repo.discard(saved.id); fail() } catch (_: IllegalStateException) { }
        assertEquals(saved, pending())
        db.outboxDao().markForReview(saved.id, "Conflict")
        val comparison = repo.compare(saved.id)
        db.episodeWatchEventDao().upsertAll(listOf(EpisodeWatchEventEntity(id(45), id(21), "2026-02-01")))
        try { repo.applyReviewed(comparison); fail() } catch (_: IllegalStateException) { }
        assertEquals(saved.id, pending().id)
    }

    @Test fun discardOnlyRemovesRejectedProvisionalEventsPreservingLaterIndependentHistory() = runBlocking {
        repo.save(repo.prepare(title, null)); val saved = pending()
        db.outboxDao().markForReview(saved.id, "Conflict")
        db.episodeWatchEventDao().upsertAll(listOf(EpisodeWatchEventEntity(id(45), id(21), "2026-02-01")))
        repo.discard(saved.id)
        assertEquals(setOf(id(30), id(31), id(45)), watches().map { it.id }.toSet())
        assertEquals("WATCHING", db.titleDao().getById(title)!!.status)
        assertEquals(2, db.seasonDao().observeSeasons(title).first().find { it.id == id(12) }!!.episodesWatched)
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertTrue(replays > 0)
    }

    @Test fun ackRequiresTransactionAndExactHeadAndNeverRecreatesDeletedHistory() = runBlocking {
        repo.save(repo.prepare(title, 1))
        val entry = pending()
        val operations = bulkOperations(entry, owner)
        val rows = JSONArray()
        for (i in 0 until operations.length()) {
            val op = operations.getJSONObject(i)
            val row = JSONObject(op.getJSONObject("values").toString()).put("user_id", owner)
            op.getJSONObject("key").keys().forEach { row.put(it, op.getJSONObject("key").get(it)) }
            rows.put(JSONObject().put("table", op.getString("table")).put("key", op.getJSONObject("key")).put("row", row))
        }
        val envelope = JSONObject().put("receipt", JSONObject().put("operationId", entry.id).put("rows", rows))
            .put("title", JSONObject.NULL).put("seasons", JSONArray())
        val applier = EpisodeBulkApplier(db, owner)
        try { applier.apply(entry, envelope); fail() } catch (_: IllegalStateException) { }
        try { outbox.atomically { applier.apply(entry.copy(payloadJson = "{}"), envelope) }; fail() } catch (_: IllegalArgumentException) { }
        val inserted = watches().first { it.episodeId == id(21) }
        db.episodeWatchEventDao().deleteById(inserted.id)
        outbox.atomically { applier.apply(entry, envelope); db.outboxDao().remove(entry.id) }
        assertFalse(watches().any { it.id == inserted.id })
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun reviewingBulkUsesOnlyEarlierPredecessorsAndPreservesLaterTitleDraft() = runBlocking {
        repo.save(repo.prepare(title, null))
        val original = pending()
        db.outboxDao().markForReview(original.id, "Conflict")
        val optimistic = db.titleDao().getById(title)!!
        outbox.enqueueTitleMetadata(optimistic, JSONObject().put("status", "dropped"), owner)
        db.titleDao().upsertAll(listOf(optimistic.copy(status = "DROPPED")))
        val later = db.outboxDao().getPending().last()
        assertEquals("review", later.operation)
        val comparison = repo.compare(original.id)
        repo.applyReviewed(comparison)
        assertEquals("DROPPED", db.titleDao().getById(title)!!.status)
        val queue = db.outboxDao().getPending()
        assertEquals(2, queue.size)
        assertEquals(EPISODE_BULK_COMMAND, queue.first().operation)
        assertEquals(later, queue.last())
        assertEquals(revision, bulkOperations(queue.first(), owner).getJSONObject(3).getString("expectedUpdatedAt"))
    }

    @Test fun removedTitleRequestCanBeDiscardedGloballyAndOriginalRemainsExportableAfterReopen() = runBlocking {
        val opening = repo.prepare(title, null)
        repo.save(opening)
        db.outboxDao().markForReview(opening.operationId, "Conflict")
        db.titleDao().deleteById(title)
        outbox.enqueue("title", title, "delete", JSONObject())
        assertEquals("Series", repo.savedRequests.first().single().titleName)
        remoteSnapshot = EpisodeBulkSnapshot(null, emptyList(), emptySet())
        kotlinx.coroutines.withTimeout(5_000) { repo.discard(opening.operationId) }
        assertEquals(1, syncRequests); assertEquals(1, replays)
        assertNull(db.titleDao().getById(title))
        assertTrue(watches().isEmpty())
        assertEquals("delete", pending().operation)
        assertTrue(repo.savedRequests.first().isEmpty())
        assertEquals(1, repo.retainedCount.first())
        db.close(); db = open(); configure()
        val exported = JSONObject(repo.exportOriginals()).getJSONArray("originalRequests").getJSONObject(0)
        assertEquals(opening.operationId, exported.getString("operationId"))
        assertEquals(opening.json, exported.getString("payloadJson"))
        assertNull(db.titleDao().getById(title))
    }

    @Test fun originalExportAndGlobalRecoveryAreFencedAfterOwnerChanges() = runBlocking {
        repo.save(repo.prepare(title, 1))
        active = false
        assertTrue(repo.savedRequests.first().isEmpty())
        assertEquals(0, repo.retainedCount.first())
        try { repo.exportOriginals(); fail("Old owner export") } catch (_: IllegalStateException) { }
    }

    companion object { private fun id(n: Int) = "10000000-0000-4000-8000-${n.toString().padStart(12, '0')}" }
}
