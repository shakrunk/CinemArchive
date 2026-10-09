package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.*

internal object ViewingProducerFixture {
    const val owner = ViewingCommandFixture.owner
    const val title = ViewingCommandFixture.title
    const val watch = ViewingCommandFixture.viewing
    const val other = ViewingCommandFixture.nextOperation
    const val outing = OutingCommandFixture.outing
    const val revision = ViewingCommandFixture.baseline
    fun repository(db: LibraryDatabase, dao: OutboxDao = db.outboxDao(), active: () -> Boolean = { true }) = LibraryRepository(
        titleDao = db.titleDao(), seasonDao = db.seasonDao(), episodeDao = db.episodeDao(),
        watchEventDao = db.episodeWatchEventDao(), ratingDao = db.episodeRatingDao(), reviewDao = db.episodeReviewDao(),
        viewingDao = db.viewingDao(), cinemaOutingDao = db.cinemaOutingDao(), titleCastDao = db.titleCastDao(),
        titleCrewDao = db.titleCrewDao(), theaterInterestDao = db.theaterInterestDao(),
        outbox = MutationOutbox(dao, object : RemoteMutationWriter { override suspend fun push(entry: OutboxEntity) = PushResult.Success },
            TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db)),
        episodeMetadataFetcher = object : EpisodeMetadataFetcher {
            override suspend fun fetchSeasonEpisodes(tmdbId: Int, seasonNumber: Int) = emptyList<MediaEpisode>()
            override suspend fun fetchEpisodeCast(tmdbId: Int, seasonNumber: Int, episodeNumber: Int) = EpisodeCast.EMPTY
        }, personCreditsDao = db.personCreditsDao(), mutationOwnerId = owner,
        viewingAliases = db.viewingCompletionAliasDao(), isCurrentOwner = active,
    )
    suspend fun seed(db: LibraryDatabase) = db.titleDao().upsertAll(listOf(TitleMetadataFixture.entity().copy(
        id = title, status = "WATCHLIST", rating = 2.0, updatedAt = revision)))
    fun event(id: String = watch, outingId: String? = null) = ViewingEntity(id, title, "2026-01-03", 3.5, "Old notes", "Old venue", listOf("Alex"), outingId, revision)
    fun outing() = viewingLinkedOuting(outing, watch).copy(updatedAt = revision)
}

@RunWith(RobolectricTestRunner::class)
class ViewingHistoryTest {
    private lateinit var db: LibraryDatabase
    private lateinit var repo: LibraryRepository
    private val title = ViewingProducerFixture.title
    private val watch = ViewingProducerFixture.watch
    private val other = ViewingProducerFixture.other
    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        ViewingProducerFixture.seed(db); repo = ViewingProducerFixture.repository(db)
    }
    @After fun tearDown() { db.close() }

    @Test fun ordinaryEditKeepsIdentityOtherRewatchesAndObservedRevision() = runBlocking {
        db.viewingDao().upsertAll(listOf(ViewingProducerFixture.event(), ViewingProducerFixture.event(other)))
        val draft = repo.prepareViewingEdit(title, watch).copy(date = "2025-12-24", rating = 4.5, notes = "New notes", venue = "New venue", companions = listOf("Sam"))
        repo.saveViewing(title, draft, false)
        assertEquals(ViewingProducerFixture.event().copy(date = draft.date, rating = draft.rating, notes = draft.notes, venue = draft.venue, companions = draft.companions), db.viewingDao().getById(watch))
        assertEquals(ViewingProducerFixture.event(other), db.viewingDao().getById(other))
        assertEquals(4.5, db.titleDao().getById(title)!!.rating!!, 0.0)
        assertEquals("WATCHLIST", db.titleDao().getById(title)!!.status)
        val queued = db.outboxDao().getPending().single { it.entityType == "viewing" }
        assertEquals(VIEWING_COMMAND, queued.operation)
        assertEquals("Sam", viewingCommandOperations(queued).getJSONObject(0).getJSONObject("values").getJSONArray("companions").getJSONObject(0).getString("name"))
    }

    @Test fun notesOnlyEditKeepsOutingLinkFriendIdentityAndUnchangedTitleRating() = runBlocking {
        db.cinemaOutingDao().upsert(ViewingProducerFixture.outing())
        db.viewingDao().upsert(ViewingProducerFixture.event(outingId = ViewingProducerFixture.outing))
        repo.saveViewing(title, repo.prepareViewingEdit(title, watch).copy(notes = "Just notes"), false)
        assertEquals(ViewingProducerFixture.outing, db.viewingDao().getById(watch)!!.outingId)
        val pending = db.outboxDao().getPending().single()
        assertEquals(setOf("notes"), viewingCommandOperations(pending).getJSONObject(0).getJSONObject("values").keys().asSequence().toSet())
        assertEquals(2.0, db.titleDao().getById(title)!!.rating!!, 0.0)
        assertEquals(ViewingProducerFixture.outing(), db.cinemaOutingDao().getById(ViewingProducerFixture.outing))
    }

    @Test fun explicitClearsPersistWithoutClearingTitleRating() = runBlocking {
        db.viewingDao().upsert(ViewingProducerFixture.event())
        repo.saveViewing(title, repo.prepareViewingEdit(title, watch).copy(date = null, rating = null, notes = null, venue = null, companions = emptyList()), false)
        val saved = db.viewingDao().getById(watch)!!
        assertNull(saved.date); assertNull(saved.rating); assertNull(saved.notes); assertNull(saved.venue)
        val body = viewingCommandOperations(db.outboxDao().getPending().single()).getJSONObject(0).getJSONObject("values")
        listOf("viewed_at", "rating", "notes", "venue").forEach { assertTrue(body.has(it) && body.isNull(it)) }
        assertEquals(0, body.getJSONArray("companions").length())
        assertEquals(2.0, db.titleDao().getById(title)!!.rating!!, 0.0)
    }

    @Test fun sameDraftRetryKeepsOneCommandPerEffectAndMarksTitleWatched() = runBlocking {
        val draft = repo.prepareViewingEdit(title, null).copy(rating = 4.5, notes = "First show")
        repo.saveViewing(title, draft, true)
        val before = db.outboxDao().getPending()
        val patch = titleMetadataPatch(checkNotNull(viewingTitleEntry(before.single(), ViewingProducerFixture.owner)), ViewingProducerFixture.owner)
        assertEquals("watched", patch.getString("status")); assertEquals(4.5, patch.getDouble("rating"), 0.0)
        repo.saveViewing(title, draft, true)
        assertEquals(before, db.outboxDao().getPending())
        assertEquals(listOf(draft.id), db.viewingDao().observeViewings(title).first().map { it.id })
        assertEquals("WATCHED", db.titleDao().getById(title)!!.status)
    }

    @Test fun exactDeletionQueuesNoIndependentUnlinkAndPreservesOutingRevision() = runBlocking {
        db.cinemaOutingDao().upsert(ViewingProducerFixture.outing())
        db.viewingDao().upsertAll(listOf(ViewingProducerFixture.event(outingId = ViewingProducerFixture.outing), ViewingProducerFixture.event(other)))
        val draft = repo.prepareViewingEdit(title, watch)
        repo.deleteViewing(title, draft); repo.deleteViewing(title, draft)
        assertNull(db.viewingDao().getById(watch)); assertEquals(ViewingProducerFixture.event(other), db.viewingDao().getById(other))
        assertEquals(ViewingProducerFixture.outing().copy(completedViewingId = null), db.cinemaOutingDao().getById(ViewingProducerFixture.outing))
        val only = db.outboxDao().getPending().single()
        val op = viewingCommandOperations(only).getJSONObject(0)
        assertEquals("delete", op.getString("action")); assertEquals(ViewingProducerFixture.revision, op.getString("expectedUpdatedAt"))
        assertEquals(listOf(ViewingProducerFixture.outing), viewingLinkedOutingIds(only, ViewingProducerFixture.owner))
        assertEquals("WATCHLIST", db.titleDao().getById(title)!!.status)
    }

    @Test fun priorOutingEditNeverBecomesViewingDeleteGuard() = runBlocking {
        db.cinemaOutingDao().upsert(ViewingProducerFixture.outing()); db.viewingDao().upsert(ViewingProducerFixture.event())
        val earlier = OutingCommandFixture.patch(); db.outboxDao().enqueue(earlier)
        repo.deleteViewing(title, repo.prepareViewingEdit(title, watch))
        val queue = db.outboxDao().getPending()
        assertEquals(listOf("cinema_outing", "viewing"), queue.map { it.entityType })
        assertEquals(earlier, queue.first())
        assertEquals(ViewingProducerFixture.revision, viewingCommandOperations(queue.last()).getJSONObject(0).getString("expectedUpdatedAt"))
    }

    @Test fun enqueueFailureRollsBackEditAndDeletionIncludingOutingChanges() = runBlocking {
        db.cinemaOutingDao().upsert(ViewingProducerFixture.outing()); db.viewingDao().upsert(ViewingProducerFixture.event())
        val failing = ViewingProducerFixture.repository(db, object : OutboxDao by db.outboxDao() {
            override suspend fun enqueue(entry: OutboxEntity) { throw IllegalStateException("disk full") }
        })
        val opening = failing.prepareViewingEdit(title, watch)
        for (delete in listOf(false, true)) {
            assertTrue(runCatching { if (delete) failing.deleteViewing(title, opening) else failing.saveViewing(title, opening.copy(notes = "Uncommitted"), false) }.isFailure)
            assertEquals(ViewingProducerFixture.event(), db.viewingDao().getById(watch))
            assertEquals(ViewingProducerFixture.outing(), db.cinemaOutingDao().getById(ViewingProducerFixture.outing))
            assertTrue(db.outboxDao().getPending().isEmpty())
        }
    }

    @Test fun staleEditorCannotResurrectRemovedViewing() = runBlocking {
        db.viewingDao().upsert(ViewingProducerFixture.event())
        val opened = repo.prepareViewingEdit(title, watch); db.viewingDao().deleteById(watch)
        assertTrue(runCatching { repo.saveViewing(title, opened.copy(notes = "Stale"), false) }.isFailure)
        assertNull(db.viewingDao().getById(watch)); assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun importConvenienceQueuesCanonicalEventWithoutChangingTitle() = runBlocking {
        val before = db.titleDao().getById(title)
        repo.logViewing(title, "2025-02-03")
        assertEquals(before, db.titleDao().getById(title))
        val queued = db.outboxDao().getPending().single()
        assertEquals("insert", viewingCommandOperations(queued).getJSONObject(0).getString("action"))
        assertEquals("2025-02-03", db.viewingDao().getById(queued.entityId)!!.date)
    }

    @Test fun failedCreateCannotBeOvertakenByDeleteAndLaterResurrectViewing() = runBlocking {
        db.outboxDao().enqueue(OutboxEntity("z-create", "viewing", watch, "upsert", "{}", 10L))
        db.outboxDao().enqueue(OutboxEntity("a-delete", "viewing", watch, "delete", "{}", 10L))
        val calls = mutableListOf<String>()
        var failing = true
        val remote = mutableSetOf<String>()
        val writer = object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity): PushResult {
                calls += entry.operation
                if (failing) return PushResult.Retry("offline")
                if (entry.operation == "upsert") remote += entry.entityId else remote -= entry.entityId
                return PushResult.Success
            }
        }
        val outbox = MutationOutbox(db.outboxDao(), writer, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db))
        outbox.flush()
        assertEquals(listOf("upsert"), calls); assertEquals(2, db.outboxDao().getPending().size)
        failing = false; outbox.flush()
        assertEquals(listOf("upsert", "upsert", "delete"), calls)
        assertTrue(remote.isEmpty()); assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun conflictRebaseUsesQueueOrderEvenWhenDeviceClockMovesBackwards() = runBlocking {
        db.outboxDao().enqueue(OutboxEntity("first", "title", title, "update", "{\"rating\":2}", 100L))
        db.outboxDao().enqueue(OutboxEntity("later", "title", title, "update", "{\"rating\":4}", 50L))
        val rebased = mutableListOf<Double>()
        val handler = object : ConflictHandler {
            override suspend fun applyRemote(entityType: String, entityId: String, serverPayload: JSONObject) = Unit
            override suspend fun rebasePending(entityType: String, entityId: String, laterPending: List<JSONObject>) { rebased += laterPending.map { it.getDouble("rating") } }
        }
        val writer = object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity) = if (entry.id == "first") PushResult.Conflict(JSONObject()) else PushResult.Retry("offline")
        }
        MutationOutbox(db.outboxDao(), writer, handler, RoomTransactor(db)).flush()
        assertEquals(listOf(4.0), rebased); assertEquals(listOf("later"), db.outboxDao().getPending().map { it.id })
    }
}
