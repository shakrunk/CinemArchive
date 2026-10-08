package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxDao
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.database.RoomTransactor
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.core.model.EpisodeCast
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaEpisode

/**
 * Real Room (Robolectric) coverage for the account-isolation / offline-queue guarantees that
 * fakes can't prove: per-account storage retention, transactional write+enqueue, and the sync
 * pull never clobbering or resurrecting a pending local edit.
 */
@RunWith(RobolectricTestRunner::class)
class TransactionalRuntimeTest {
    private lateinit var context: Context
    private val opened = mutableListOf<LibraryDatabase>()
    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    @Before fun setUp() { context = ApplicationProvider.getApplicationContext() }

    @After fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        scope.cancel()
    }

    private fun memoryDb(): LibraryDatabase =
        Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build().also { opened += it }

    private fun fileDb(userId: String): LibraryDatabase =
        LibraryDatabase.create(context, OwnerNamespace.databaseName(userId)).also { opened += it }

    private fun title(id: String, status: String = "WATCHLIST", rating: Double? = null) = TitleEntity(
        id = id, tmdbId = id.hashCode(), type = "MOVIE", title = "Title $id", year = 2020, director = null,
        genres = emptyList(), posterUrl = null, backdropUrl = null, synopsis = null, runtime = null, network = null,
        status = status, rating = rating, notes = null, addedAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
        imdbRating = null, originalLanguage = null, releaseDate = null,
    )

    private object NoMetadata : EpisodeMetadataFetcher {
        override suspend fun fetchSeasonEpisodes(tmdbId: Int, seasonNumber: Int) = emptyList<MediaEpisode>()
        override suspend fun fetchEpisodeCast(tmdbId: Int, seasonNumber: Int, episodeNumber: Int) = EpisodeCast.EMPTY
    }

    private class ScriptedWriter(var next: () -> PushResult) : RemoteMutationWriter {
        override suspend fun push(entry: OutboxEntity): PushResult = next()
    }

    private fun libraryRepository(db: LibraryDatabase, outbox: MutationOutbox) = LibraryRepository(
        titleDao = db.titleDao(), seasonDao = db.seasonDao(), episodeDao = db.episodeDao(),
        watchEventDao = db.episodeWatchEventDao(), ratingDao = db.episodeRatingDao(), reviewDao = db.episodeReviewDao(),
        viewingDao = db.viewingDao(), cinemaOutingDao = db.cinemaOutingDao(), titleCastDao = db.titleCastDao(),
        titleCrewDao = db.titleCrewDao(), theaterInterestDao = db.theaterInterestDao(), outbox = outbox,
        episodeMetadataFetcher = NoMetadata,
    )

    private fun outbox(db: LibraryDatabase, writer: RemoteMutationWriter) =
        MutationOutbox(db.outboxDao(), writer, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db))

    /** Answers `sync_library_changes` with one scripted page of rows (empty after that). */
    private class SyncHttp(var pages: ArrayDeque<JSONArray>) {
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val body = (pages.removeFirstOrNull() ?: JSONArray()).toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
    }

    private fun titleRow(id: String, status: String, updatedAt: String) = JSONObject()
        .put("entity_type", "title").put("entity_id", id).put("updated_at", updatedAt)
        .put(
            "payload",
            JSONObject().put("id", id).put("tmdbId", id.hashCode()).put("type", "movie").put("title", "Title $id")
                .put("status", status).put("addedAt", "2026-01-01T00:00:00Z").put("updatedAt", updatedAt),
        )

    private fun syncRepository(db: LibraryDatabase, outbox: MutationOutbox, http: SyncHttp, file: File) = LibrarySyncRepository(
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file },
        client = SupabaseRestClient("https://x.supabase.co", "anon", http.client),
        authRepository = SessionSource { SupabaseSession("tok", "user-a") },
        titleDao = db.titleDao(), seasonDao = db.seasonDao(), episodeDao = db.episodeDao(),
        watchEventDao = db.episodeWatchEventDao(), ratingDao = db.episodeRatingDao(), reviewDao = db.episodeReviewDao(),
        viewingDao = db.viewingDao(), cinemaOutingDao = db.cinemaOutingDao(), titleCastDao = db.titleCastDao(),
        titleCrewDao = db.titleCrewDao(), listDao = db.listDao(), listItemDao = db.listItemDao(),
        pushPending = outbox::flush, pendingKeys = outbox::pendingEntityKeys, transactor = RoomTransactor(db),
    )

    private fun tmpFile(name: String) = File.createTempFile(name, ".preferences_pb").also { it.delete(); it.deleteOnExit() }

    @Test fun accountsNeverShareStorageAndEachRetainsItsOwnQueueAcrossSwitches() = runBlocking {
        val a = fileDb("user-a")
        a.titleDao().upsertAll(listOf(title("t-a", "DROPPED")))
        outbox(a, ScriptedWriter { PushResult.Retry("offline") }).enqueue("title", "t-a", "update", JSONObject().put("id", "t-a"))
        a.close()

        val b = fileDb("user-b") // B signs in on the same device
        assertEquals("B starts empty", 0, b.titleDao().count())
        assertTrue("B sees none of A's queue", b.outboxDao().getPending().isEmpty())
        b.titleDao().upsertAll(listOf(title("t-b")))
        b.close()

        val a2 = fileDb("user-a") // A signs back in
        assertEquals(listOf("t-a"), a2.titleDao().observeAllTitles().first().map { it.id })
        assertEquals("A's offline queue retained for A", listOf("t-a"), a2.outboxDao().getPending().map { it.entityId })
        assertEquals("DROPPED", a2.titleDao().getById("t-a")?.status)
    }

    @Test fun localWriteAndQueueEntryCommitOrRollBackTogether() = runTest {
        val db = memoryDb()
        db.titleDao().upsertAll(listOf(title("t1")))
        val failing = object : OutboxDao by db.outboxDao() {
            override suspend fun enqueue(entry: OutboxEntity) = throw IllegalStateException("disk full")
        }
        val repo = libraryRepository(db, MutationOutbox(failing, ScriptedWriter { PushResult.Success }, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db)))
        try {
            repo.updateTitleStatus("t1", LibraryStatus.DROPPED, "2026-02-01T00:00:00Z")
            fail("enqueue failure must surface")
        } catch (_: IllegalStateException) {
        }
        assertEquals("local write rolled back with the failed enqueue", "WATCHLIST", db.titleDao().getById("t1")?.status)
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun pullNeverOverwritesPendingEditUntilItIsPushed() = runTest {
        val db = memoryDb()
        db.titleDao().upsertAll(listOf(title("t1", "WATCHLIST")))
        val writer = ScriptedWriter { PushResult.Retry("offline") }
        val outbox = outbox(db, writer)
        libraryRepository(db, outbox).updateTitleStatus("t1", LibraryStatus.DROPPED, "2026-02-01T00:00:00Z")

        // Server (stale) still says WATCHED; the device is offline so the push can't land.
        val http = SyncHttp(ArrayDeque(listOf(JSONArray().put(titleRow("t1", "watched", "2026-01-15T00:00:00Z")))))
        syncRepository(db, outbox, http, tmpFile("sync1")).syncNow()
        assertEquals("pending offline edit survives the pull", "DROPPED", db.titleDao().getById("t1")?.status)
        assertEquals(1, db.outboxDao().getPending().size)

        // Connectivity returns: push succeeds, queue drains, and the next pull may update normally.
        writer.next = { PushResult.Success }
        val http2 = SyncHttp(ArrayDeque(listOf(JSONArray().put(titleRow("t1", "watched", "2026-03-01T00:00:00Z")))))
        syncRepository(db, outbox, http2, tmpFile("sync2")).syncNow()
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertEquals("once pushed, the server version is applied", "WATCHED", db.titleDao().getById("t1")?.status)
    }

    @Test fun pullDoesNotResurrectAPendingOfflineDelete() = runTest {
        val db = memoryDb()
        db.titleDao().upsertAll(listOf(title("t1")))
        val outbox = outbox(db, ScriptedWriter { PushResult.Retry("offline") })
        libraryRepository(db, outbox).removeTitle("t1")
        assertNull(db.titleDao().getById("t1"))

        val http = SyncHttp(ArrayDeque(listOf(JSONArray().put(titleRow("t1", "watchlist", "2026-01-15T00:00:00Z")))))
        syncRepository(db, outbox, http, tmpFile("sync3")).syncNow()
        assertNull("remote upsert must not bring back an unpushed delete", db.titleDao().getById("t1"))
        assertEquals("delete", db.outboxDao().getPending().single().operation)
    }

    @Test fun serverRejectedEditReconcilesProjectionAndIsReportedNotDropped() = runTest {
        val db = memoryDb()
        db.titleDao().upsertAll(listOf(title("t1", "WATCHLIST")))
        val conflict = JSONObject().put("id", "t1").put("status", "watched").put("rating", 4.5).put("updatedAt", "2026-03-01T00:00:00Z")
        val outbox = outbox(db, ScriptedWriter { PushResult.Conflict(conflict) })
        val repo = libraryRepository(db, outbox)
        val notices = mutableListOf<ConflictNotice>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { outbox.conflicts.collect { notices += it } }

        repo.updateTitleStatus("t1", LibraryStatus.DROPPED, "2026-02-01T00:00:00Z")
        outbox.flush()

        val row = db.titleDao().getById("t1")!!
        assertEquals("WATCHED", row.status)
        assertEquals(4.5, row.rating!!, 0.0)
        assertTrue("entry removed with the reconcile", db.outboxDao().getPending().isEmpty())
        assertEquals("t1", notices.single().entityId)
        collector.cancel()
    }

    @Test fun conflictedEditDoesNotRollBackALaterOfflineEditToTheSameTitle() = runTest {
        val db = memoryDb()
        db.titleDao().upsertAll(listOf(title("t1", "WATCHLIST")))
        val conflict = JSONObject().put("id", "t1").put("status", "watched").put("rating", JSONObject.NULL).put("updatedAt", "2026-03-01T00:00:00Z")
        var calls = 0
        val writer = ScriptedWriter { if (calls++ == 0) PushResult.Conflict(conflict) else PushResult.Retry("offline") }
        val outbox = outbox(db, writer)
        val repo = libraryRepository(db, outbox)
        repo.updateTitleStatus("t1", LibraryStatus.DROPPED, "2026-02-01T00:00:00Z")   // will conflict
        repo.updateTitleStatus("t1", LibraryStatus.WATCHING, "2026-02-02T00:00:00Z")  // newer offline edit
        outbox.flush()
        assertEquals("newer offline edit stays on top of the server row", "WATCHING", db.titleDao().getById("t1")?.status)
        assertEquals("only the conflicted entry was dropped", 1, db.outboxDao().getPending().size)
    }

    @Test fun serverRowWithNoRatingClearsTheLocalRatingButAbsentFieldLeavesItAlone() = runTest {
        val db = memoryDb()
        db.titleDao().upsertAll(listOf(title("t1", "WATCHLIST", rating = 3.5), title("t2", "WATCHLIST", rating = 2.0)))
        val handler = TitleConflictHandler(db.titleDao(), db.titleReconcileDao())

        handler.applyRemote("title", "t1", JSONObject().put("status", "watched").put("rating", JSONObject.NULL).put("updatedAt", "2026-03-01T00:00:00Z"))
        assertNull("explicit null clears the rating", db.titleDao().getById("t1")?.rating)
        assertEquals("WATCHED", db.titleDao().getById("t1")?.status)

        handler.applyRemote("title", "t2", JSONObject().put("status", "dropped").put("updatedAt", "2026-03-01T00:00:00Z"))
        assertEquals("absent key says nothing about rating", 2.0, db.titleDao().getById("t2")?.rating!!, 0.0)
    }
}
