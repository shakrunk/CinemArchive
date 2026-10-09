package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
import okio.Buffer
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
        personCreditsDao = db.personCreditsDao(),
        mutationOwnerId = "10000000-0000-4000-8000-000000000001",
    )

    private fun outbox(db: LibraryDatabase, writer: RemoteMutationWriter) =
        MutationOutbox(db.outboxDao(), writer, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db))

    /** Answers `sync_library_changes` with one scripted page of rows (empty after that). */
    private class SyncHttp(var pages: ArrayDeque<JSONArray>) {
        val requests = mutableListOf<JSONObject>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            requests += JSONObject(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
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

    private fun syncRepository(db: LibraryDatabase, outbox: MutationOutbox, http: SyncHttp, file: File,
        preferences: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>? = null,
        pushPending: suspend () -> Unit = outbox::flush,
    ) = LibrarySyncRepository(
        dataStore = preferences ?: PreferenceDataStoreFactory.create(scope = scope) { file },
        client = SupabaseRestClient("https://x.supabase.co", "anon", http.client),
        authRepository = SessionSource { SupabaseSession("tok", "user-a") },
        titleDao = db.titleDao(), seasonDao = db.seasonDao(), episodeDao = db.episodeDao(),
        watchEventDao = db.episodeWatchEventDao(), ratingDao = db.episodeRatingDao(), reviewDao = db.episodeReviewDao(),
        viewingDao = db.viewingDao(), cinemaOutingDao = db.cinemaOutingDao(), titleCastDao = db.titleCastDao(),
        titleCrewDao = db.titleCrewDao(), listDao = db.listDao(), listItemDao = db.listItemDao(),
        pushPending = pushPending, pendingKeys = outbox::pendingEntityKeys, transactor = RoomTransactor(db),
        personCreditsDao = db.personCreditsDao(),
    )

    private fun tmpFile(name: String) = File.createTempFile(name, ".preferences_pb").also { it.delete(); it.deleteOnExit() }

    private fun creditRow(type: String, id: String, payload: JSONObject) = JSONObject()
        .put("entity_type", type).put("entity_id", id).put("updated_at", "2026-01-01T00:00:00Z").put("payload", payload.put("id", id))

    @Test fun pendingCreditRefreshRewindsAndReplaysUnchangedRowsAndNullParentTombstonesAfterAck() = runBlocking {
        val db = memoryDb()
        db.titleDao().upsertAll(listOf(title("show")))
        db.titleCastDao().upsertAll(listOf(
            work.kumarfamilynet.cinemarchive.core.database.TitleCastEntity("cast", "show", 42, "Local name", null, 0),
            work.kumarfamilynet.cinemarchive.core.database.TitleCastEntity("deleted", "show", 84, "Deleted remotely", null, 1),
        ))
        val file = tmpFile("credit-replay")
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { file }
        val cursor = stringPreferencesKey("last_synced_at")
        prefs.edit { it[intPreferencesKey("sync_schema_version")] = 9; it[cursor] = "2026-10-08T00:00:00Z" }
        val writer = ScriptedWriter { PushResult.Retry("offline") }
        val queue = outbox(db, writer)
        queue.enqueue("title_credits", "show", "refresh", JSONObject().put("protectedKeys", JSONArray().put("title_cast:deleted")))
        fun page() = JSONArray()
            .put(creditRow("title_cast", "cast", JSONObject().put("titleId", "show").put("tmdbPersonId", 42).put("name", "Remote name").put("castOrder", 0)))
            .put(creditRow("tombstone", "deleted", JSONObject().put("entityType", "title_cast")).put("parent_id", JSONObject.NULL))
        val http = SyncHttp(ArrayDeque(listOf(page(), page())))
        val sync = syncRepository(db, queue, http, file, prefs)
        sync.syncNow()
        assertEquals(setOf("Local name", "Deleted remotely"), db.titleCastDao().observeAllCast().first().map { it.name }.toSet())
        assertEquals("2026-01-01T00:00:00Z", prefs.data.first()[cursor])
        writer.next = { PushResult.Success }
        sync.syncNow()
        assertEquals(listOf("Remote name"), db.titleCastDao().observeAllCast().first().map { it.name })
        assertTrue(queue.pendingEntries().isEmpty())
        assertEquals(List(2) { "1970-01-01T00:00:00Z" }, http.requests.map { it.getString("p_since") })
        assertEquals(9, prefs.data.first()[intPreferencesKey("sync_schema_version")])
    }

    @Test fun creditAckBeforeProcessInterruptionLeavesDurableEpochForNextSync() = runBlocking {
        verifyAckBeforeProcessInterruption("title_credits")
    }

    @Test fun catalogAckBeforeProcessInterruptionLeavesDurableEpochForNextSync() = runBlocking {
        verifyAckBeforeProcessInterruption("title_catalog")
    }

    @Test fun titleMetadataAckBeforeProcessInterruptionLeavesDurableEpochForNextSync() = runBlocking {
        verifyAckBeforeProcessInterruption("title_metadata")
    }

    @Test fun listMembershipAckBeforeProcessInterruptionLeavesDurableEpochForNextSync() = runBlocking {
        verifyAckBeforeProcessInterruption("list_membership")
    }

    @Test fun discardingMetadataPersistsReplayBeforeRemovingProtectionForOldTombstone() = runBlocking {
        val db = memoryDb()
        db.titleDao().upsertAll(listOf(TitleMetadataFixture.entity().copy(tags = listOf("Saved"))))
        val queue = outbox(db, ScriptedWriter { error("Never dispatch a review draft") })
        queue.enqueue("title", TitleMetadataFixture.title, "review",
            titleMetadataPayload(TitleMetadataFixture.owner, TitleMetadataFixture.title, TitleMetadataFixture.patch("Saved"), null, null))
        val file = tmpFile("discard-replay")
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { file }
        val cursor = stringPreferencesKey("last_synced_at")
        prefs.edit { it[intPreferencesKey("sync_schema_version")] = 9; it[cursor] = "2026-10-08T00:00:00Z" }
        val tombstone = creditRow("tombstone", TitleMetadataFixture.title, JSONObject().put("entityType", "title"))
        val http = SyncHttp(ArrayDeque(listOf(JSONArray().put(tombstone))))
        val sync = syncRepository(db, queue, http, file, prefs)
        val recovery = TitleMetadataRepository(db, queue, TitleMetadataFixture.owner,
            SessionSource { SupabaseSession("token", TitleMetadataFixture.owner) }, object : TitleMetadataRemote {
                override suspend fun current(titleId: String): JSONObject? = null
                override suspend fun push(entry: OutboxEntity) = error("No push")
            }, synchronize = sync::syncNow, replayBoundary = { action -> sync.withDurableReplay {
                assertEquals("1970-01-01T00:00:00Z", prefs.data.first()[cursor])
                assertEquals(1, queue.pendingEntries().size)
                action()
            } })
        recovery.discard(recovery.compare(TitleMetadataFixture.title))
        assertTrue(queue.pendingEntries().isEmpty())
        assertEquals("1970-01-01T00:00:00Z", prefs.data.first()[cursor])
        // A new sync instance after interruption still replays the previously skipped deletion.
        syncRepository(db, queue, http, file, prefs).syncNow()
        assertEquals("1970-01-01T00:00:00Z", http.requests.single().getString("p_since"))
        assertNull(db.titleDao().getById(TitleMetadataFixture.title))
    }

    @Test fun metadataRecoveryRetryUsesSerializedSyncAndRewindsBeforeNetwork() = runBlocking {
        val db = memoryDb()
        db.titleDao().upsertAll(listOf(TitleMetadataFixture.entity()))
        val file = tmpFile("metadata-retry")
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { file }
        val cursor = stringPreferencesKey("last_synced_at")
        prefs.edit { it[intPreferencesKey("sync_schema_version")] = 9; it[cursor] = "2026-10-08T00:00:00Z" }
        val queue = outbox(db, ScriptedWriter { PushResult.Success })
        TitleMetadataFixture.library(db, queue).updateTitleTags(TitleMetadataFixture.title, listOf("Saved"))
        val http = SyncHttp(ArrayDeque(listOf(JSONArray())))
        val sync = syncRepository(db, queue, http, file, prefs, pushPending = {
            assertEquals("1970-01-01T00:00:00Z", prefs.data.first()[cursor])
            queue.flush()
        })
        val recovery = TitleMetadataRepository(db, queue, TitleMetadataFixture.owner,
            SessionSource { SupabaseSession("token", TitleMetadataFixture.owner) }, object : TitleMetadataRemote {
                override suspend fun current(titleId: String) = error("Not a comparison")
                override suspend fun push(entry: OutboxEntity) = error("Use outbox")
            }, synchronize = sync::syncNow, replayBoundary = sync::withDurableReplay)
        recovery.retrySync()
        assertTrue(queue.pendingEntries().isEmpty())
        assertEquals("1970-01-01T00:00:00Z", http.requests.single().getString("p_since"))
    }

    private suspend fun verifyAckBeforeProcessInterruption(entityType: String) {
        val db = memoryDb()
        db.titleDao().upsertAll(listOf(title("show")))
        val file = tmpFile("credit-ack-crash")
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { file }
        val cursor = stringPreferencesKey("last_synced_at")
        prefs.edit { it[intPreferencesKey("sync_schema_version")] = 9; it[cursor] = "2026-10-08T00:00:00Z" }
        val queue = outbox(db, object : RemoteMutationWriter {
            override suspend fun push(entry: work.kumarfamilynet.cinemarchive.core.database.OutboxEntity): PushResult {
                assertEquals("rewind must already be durable before network", "1970-01-01T00:00:00Z", prefs.data.first()[cursor])
                return PushResult.Success
            }
        })
        when (entityType) {
            "title_metadata" -> queue.enqueue("title", "show", TITLE_METADATA_COMMAND,
                titleMetadataPayload(TitleMetadataFixture.owner, "show", TitleMetadataFixture.patch("Saved"), TitleMetadataFixture.baseline, null))
            "list_membership" -> queue.enqueue("list_item", "40000000-0000-4000-8000-000000000001", MEMBERSHIP_COMMAND,
                membershipPayload("40000000-0000-4000-8000-000000000001", "50000000-0000-4000-8000-000000000001",
                    TitleMetadataFixture.title, true, TitleMetadataFixture.baseline))
            else -> queue.enqueue(entityType, "show", if (entityType == "title_catalog") "ensure" else "refresh", JSONObject())
        }
        val http = SyncHttp(ArrayDeque(listOf(JSONArray().put(creditRow("title_cast", "cast", JSONObject()
            .put("titleId", "show").put("tmdbPersonId", 42).put("name", "Unchanged old credit").put("castOrder", 0))))))
        val interrupted = syncRepository(db, queue, http, file, prefs, pushPending = {
            queue.flush()
            throw kotlinx.coroutines.CancellationException("process interrupted after local ACK")
        })
        assertTrue(runCatching { interrupted.syncNow() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(queue.pendingEntries().isEmpty())
        assertTrue(http.requests.isEmpty())
        assertEquals("1970-01-01T00:00:00Z", prefs.data.first()[cursor])
        syncRepository(db, queue, http, file, prefs).syncNow()
        assertEquals("1970-01-01T00:00:00Z", http.requests.single().getString("p_since"))
        assertEquals("Unchanged old credit", db.titleCastDao().observeAllCast().first().single().name)
    }

    @Test fun capturedViewingAckPersistsReplayBeforeProtectionDisappearsAcrossRestart() = runBlocking {
        val db = memoryDb()
        val file = tmpFile("viewing-replay")
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { file }
        val cursor = stringPreferencesKey("last_synced_at")
        prefs.edit { it[intPreferencesKey("sync_schema_version")] = 10; it[cursor] = "2026-10-08T00:00:00Z" }
        val queue = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity): PushResult {
                assertEquals("1970-01-01T00:00:00Z", prefs.data.first()[cursor])
                return PushResult.Success
            }
        }, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db),
            pendingProjectionKeys = { viewingHistoryProtectionKeys(it, ViewingCommandFixture.owner) })
        db.outboxDao().enqueue(ViewingCommandFixture.linkedDelete())
        val http = SyncHttp(ArrayDeque(listOf(JSONArray())))
        val interrupted = syncRepository(db, queue, http, file, prefs, pushPending = {
            queue.flush()
            throw kotlinx.coroutines.CancellationException("process ended after ACK")
        })
        assertTrue(runCatching { interrupted.syncNow() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(queue.pendingEntries().isEmpty())
        assertTrue(http.requests.isEmpty())
        assertEquals("1970-01-01T00:00:00Z", prefs.data.first()[cursor])
        syncRepository(db, queue, http, file, prefs).syncNow()
        assertEquals("1970-01-01T00:00:00Z", http.requests.single().getString("p_since"))
    }

    @Test fun personCreditsResolveParentsAcrossPagesAndTombstonesDoNotResurrectDeferredRows() = runBlocking {
        val db = memoryDb()
        val seasonCredit = JSONObject().put("titleId", "show").put("seasonId", "season").put("tmdbPersonId", 42).put("name", "Same name").put("castOrder", 0)
        val episodeCredit = JSONObject().put("titleId", "show").put("episodeId", "ep").put("tmdbPersonId", 84).put("name", "Same name").put("job", "Writer")
        val early = JSONArray().apply { repeat(498) { put(creditRow("season_cast", "sc", JSONObject(seasonCredit.toString()))) }
            put(creditRow("episode_crew", "ec", episodeCredit)); put(creditRow("season_cast", "deleted", JSONObject(seasonCredit.toString()))) }
        val parent = titleRow("show", "WATCHING", "2026-01-01T00:00:00Z").apply { getJSONObject("payload")
            .put("tags", JSONArray()).put("studios", JSONArray()).put("collectionId", JSONObject.NULL).put("collectionName", JSONObject.NULL).put("personCreditsVersion", 1) }
        val late = JSONArray().put(parent)
            .put(creditRow("season", "season", JSONObject().put("titleId", "show").put("seasonNumber", 0).put("episodeCount", 1).put("episodesWatched", 0)))
            .put(creditRow("episode", "ep", JSONObject().put("titleId", "show").put("seasonNumber", 0).put("episodeNumber", 3)))
            .put(creditRow("tombstone", "deleted", JSONObject().put("entityType", "season_cast")))
        val http = SyncHttp(ArrayDeque(listOf(early, late)))
        syncRepository(db, outbox(db, ScriptedWriter { PushResult.Success }), http, tmpFile("person-sync")).syncNow()
        assertEquals(listOf("sc"), db.personCreditsDao().observeSeasonCast().first().map { it.id })
        assertEquals(listOf("ec"), db.personCreditsDao().observeEpisodeCrew().first().map { it.id })
        assertEquals(setOf(42, 84), libraryRepository(db, outbox(db, ScriptedWriter { PushResult.Success })).observeLibrary().first().single().people.map { it.tmdbPersonId }.toSet())
    }

    @Test fun personCapabilityBackfillsUnchangedCreditsAfterEmptyAndOlderBackendWithoutDiscardingPendingEdits() = runBlocking {
        val db = memoryDb()
        db.titleDao().upsertAll(listOf(title("show", "DROPPED")))
        db.seasonDao().upsertAll(listOf(work.kumarfamilynet.cinemarchive.core.database.SeasonEntity("season", "show", 1, 1, 0, null)))
        val queue = outbox(db, ScriptedWriter { PushResult.Retry("offline") })
        queue.enqueue("title", "show", "update", JSONObject().put("id", "show").put("status", "DROPPED"))
        val old = titleRow("show", "WATCHING", "2026-01-01T00:00:00Z")
        val upgraded = JSONObject(old.toString()).apply { getJSONObject("payload").put("personCreditsVersion", 1).put("titleMetadataVersion", 1) }
        val credit = creditRow("season_cast", "sc", JSONObject().put("titleId", "show").put("seasonId", "season").put("tmdbPersonId", 42).put("name", "Person"))
        val http = SyncHttp(ArrayDeque(listOf(JSONArray(), JSONArray().put(old), JSONArray().put(credit).put(upgraded), JSONArray())))
        val file = tmpFile("person-capability")
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { file }
        prefs.edit { it[intPreferencesKey("sync_schema_version")] = 8; it[stringPreferencesKey("last_synced_at")] = "2026-10-08T00:00:00Z" }
        val sync = syncRepository(db, queue, http, file, prefs)
        sync.syncNow(); sync.syncNow()
        assertEquals(8, prefs.data.first()[intPreferencesKey("sync_schema_version")])
        sync.syncNow()
        assertEquals(9, prefs.data.first()[intPreferencesKey("sync_schema_version")])
        assertTrue(http.requests.take(3).all { it.getString("p_since") == "1970-01-01T00:00:00Z" })
        assertEquals("DROPPED", db.titleDao().getById("show")!!.status)
        assertEquals(1, db.outboxDao().getPending().size)
        assertEquals(42, db.personCreditsDao().observeSeasonCast().first().single().tmdbPersonId)
        sync.syncNow()
        assertEquals("1970-01-01T00:00:00Z", http.requests.last().getString("p_since")) // Protected title keeps rich backfill unacknowledged.
    }

    @Test fun personCreditsRejectWrongTitleParentsAndApplyDirectAndParentDeletes() = runBlocking {
        val db = memoryDb()
        db.titleDao().upsertAll(listOf(title("show"), title("other")))
        db.seasonDao().upsertAll(listOf(work.kumarfamilynet.cinemarchive.core.database.SeasonEntity("season", "show", 1, 1, 0, null)))
        db.personCreditsDao().upsertSeasonCast(listOf(work.kumarfamilynet.cinemarchive.core.database.SeasonCastEntity("remove", "show", "season", 42, "Person", null, 0)))
        val wrong = creditRow("season_cast", "wrong", JSONObject().put("titleId", "other").put("seasonId", "season").put("tmdbPersonId", 84).put("name", "Wrong parent"))
        val rows = JSONArray().put(wrong).put(creditRow("tombstone", "remove", JSONObject().put("entityType", "season_cast")))
        syncRepository(db, outbox(db, ScriptedWriter { PushResult.Success }), SyncHttp(ArrayDeque(listOf(rows))), tmpFile("person-delete")).syncNow()
        assertTrue(db.personCreditsDao().observeSeasonCast().first().isEmpty())
        db.personCreditsDao().upsertSeasonCast(listOf(work.kumarfamilynet.cinemarchive.core.database.SeasonCastEntity("cascade", "show", "season", 42, "Person", null, 0)))
        db.titleDao().deleteById("show")
        assertTrue(db.personCreditsDao().observeSeasonCast().first().isEmpty())
    }

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
        val notices = mutableListOf<ConflictNotice>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { outbox.conflicts.collect { notices += it } }

        legacyTitleStatus(db, outbox, "t1", LibraryStatus.DROPPED, "2026-02-01T00:00:00Z")
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
        legacyTitleStatus(db, outbox, "t1", LibraryStatus.DROPPED, "2026-02-01T00:00:00Z")
        legacyTitleStatus(db, outbox, "t1", LibraryStatus.WATCHING, "2026-02-02T00:00:00Z")
        outbox.flush()
        assertEquals("newer offline edit stays on top of the server row", "WATCHING", db.titleDao().getById("t1")?.status)
        assertEquals("only the conflicted entry was dropped", 1, db.outboxDao().getPending().size)
    }

    /** Previously persisted LWW commands retain their original reconciliation behavior. */
    private suspend fun legacyTitleStatus(db: LibraryDatabase, outbox: MutationOutbox, id: String, status: LibraryStatus, timestamp: String) {
        outbox.atomically {
            db.titleDao().updateStatus(id, status.name, timestamp)
            outbox.enqueue("title", id, "update", JSONObject().put("id", id).put("status", status.name).put("updatedAt", timestamp))
        }
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

    @Test fun notesUpgradeResyncsFromEpochWithoutOverwritingPendingLogsOrResurrectingDeletes() = runTest {
        val db = memoryDb()
        db.titleDao().upsertAll(listOf(title("t1")))
        db.seasonDao().upsertAll(listOf(work.kumarfamilynet.cinemarchive.core.database.SeasonEntity("s", "t1", 1, 1, 0, null)))
        db.episodeDao().upsertAll(listOf(work.kumarfamilynet.cinemarchive.core.database.EpisodeEntity("ep", "t1", "s", 1, null, null, null)))
        db.episodeWatchEventDao().upsertAll(listOf(
            work.kumarfamilynet.cinemarchive.core.database.EpisodeWatchEventEntity("pending", "ep", null, "Local note"),
            work.kumarfamilynet.cinemarchive.core.database.EpisodeWatchEventEntity("deleted", "ep", null, "Delete me"),
        ))
        val outbox = outbox(db, ScriptedWriter { PushResult.Retry("offline") })
        outbox.enqueue("episode_watch_event", "pending", "upsert", JSONObject().put("id", "pending"))
        libraryRepository(db, outbox).deleteEpisodeWatchEvent("ep", "deleted")
        val page = JSONArray().put(titleRow("t1", "watchlist", "2026-01-01T00:00:00Z").apply {
            getJSONObject("payload").put("tags", JSONArray()).put("studios", JSONArray())
                .put("collectionId", JSONObject.NULL).put("collectionName", JSONObject.NULL)
        })
        for (id in listOf("existing", "pending", "deleted")) page.put(JSONObject()
            .put("entity_type", "episode_watch_event").put("entity_id", id).put("updated_at", "2026-01-01T00:00:00Z")
            .put("payload", JSONObject().put("id", id).put("episodeId", "ep").put("watchedAt", JSONObject.NULL).put("notes", "Server note")))
        val http = SyncHttp(ArrayDeque(listOf(page)))
        val file = tmpFile("notes-sync")
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { file }
        prefs.edit { it[intPreferencesKey("sync_schema_version")] = 6; it[stringPreferencesKey("last_synced_at")] = "2026-10-08T00:00:00Z" }
        syncRepository(db, outbox, http, file, prefs).syncNow()
        assertEquals("1970-01-01T00:00:00Z", http.requests.single().getString("p_since"))
        assertEquals(8, prefs.data.first()[intPreferencesKey("sync_schema_version")])
        val events = db.episodeWatchEventDao().observeAllWatchEvents().first().associateBy { it.id }
        assertEquals("Server note", events.getValue("existing").notes)
        assertEquals("Local note", events.getValue("pending").notes)
        assertTrue("pending deletion stays deleted", "deleted" !in events)
    }

    @Test fun libraryMetadataUpgradeBackfillsFromEpochAndPreservesPendingTitleEditsAndDeletes() = runTest {
        val db = memoryDb()
        db.titleDao().upsertAll(listOf(title("existing"), title("pending").copy(tags = listOf("Local tag")), title("deleted")))
        val outbox = outbox(db, ScriptedWriter { PushResult.Retry("offline") })
        val repo = libraryRepository(db, outbox)
        repo.updateTitleStatus("pending", LibraryStatus.DROPPED, "2026-10-08T00:00:00Z")
        repo.removeTitle("deleted")
        val page = JSONArray()
        for (id in listOf("existing", "pending", "deleted")) page.put(titleRow(id, "watched", "2026-01-01T00:00:00Z").apply {
            getJSONObject("payload").put("tags", JSONArray().put("Server tag")).put("studios", JSONArray().put("Studio"))
                .put("collectionId", 42).put("collectionName", "Collection")
        })
        val http = SyncHttp(ArrayDeque(listOf(page)))
        val file = tmpFile("library-metadata-sync")
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { file }
        prefs.edit { it[intPreferencesKey("sync_schema_version")] = 7; it[stringPreferencesKey("last_synced_at")] = "2026-10-08T00:00:00Z" }
        syncRepository(db, outbox, http, file, prefs).syncNow()
        assertEquals("1970-01-01T00:00:00Z", http.requests.single().getString("p_since"))
        assertEquals(8, prefs.data.first()[intPreferencesKey("sync_schema_version")])
        val added = db.titleDao().getById("existing")!!
        assertEquals(listOf("Server tag"), added.tags)
        assertEquals(listOf("Studio"), added.studios)
        assertEquals(42, added.collectionId)
        assertEquals("Collection", added.collectionName)
        assertEquals(listOf("Local tag"), db.titleDao().getById("pending")!!.tags)
        assertEquals("DROPPED", db.titleDao().getById("pending")!!.status)
        assertNull(db.titleDao().getById("deleted"))
        assertEquals(2, db.outboxDao().getPending().size)
    }

    @Test fun absentMetadataPreservesLocalValuesButExplicitNullAndEmptyArraysClearThem() = runTest {
        val db = memoryDb()
        db.titleDao().upsertAll(listOf(title("t1").copy(tags = listOf("Tag"), studios = listOf("Studio"),
            collectionId = 42, collectionName = "Collection", originalLanguage = "ja", releaseDate = "2020-01-01", imdbRating = 8.0)))
        val outbox = outbox(db, ScriptedWriter { PushResult.Success })
        val absent = SyncHttp(ArrayDeque(listOf(JSONArray().put(titleRow("t1", "watched", "2026-02-01T00:00:00Z")))))
        syncRepository(db, outbox, absent, tmpFile("metadata-absent")).syncNow()
        val retained = db.titleDao().getById("t1")!!
        assertEquals(listOf("Tag"), retained.tags); assertEquals(listOf("Studio"), retained.studios)
        assertEquals(42, retained.collectionId); assertEquals("Collection", retained.collectionName)
        assertEquals("ja", retained.originalLanguage); assertEquals("2020-01-01", retained.releaseDate)
        assertEquals(8.0, retained.imdbRating!!, 0.0)
        val clear = titleRow("t1", "watched", "2026-03-01T00:00:00Z").apply {
            getJSONObject("payload").put("tags", JSONArray()).put("studios", JSONArray())
                .put("collectionId", JSONObject.NULL).put("collectionName", JSONObject.NULL)
                .put("originalLanguage", JSONObject.NULL).put("releaseDate", JSONObject.NULL).put("imdbRating", JSONObject.NULL)
        }
        syncRepository(db, outbox, SyncHttp(ArrayDeque(listOf(JSONArray().put(clear)))), tmpFile("metadata-clear")).syncNow()
        val cleared = db.titleDao().getById("t1")!!
        assertTrue(cleared.tags.isEmpty()); assertTrue(cleared.studios.isEmpty())
        assertNull(cleared.collectionId); assertNull(cleared.collectionName)
        assertNull(cleared.originalLanguage); assertNull(cleared.releaseDate); assertNull(cleared.imdbRating)
    }

    @Test fun viewingBackfillUsesEnvelopeRevisionWithoutOverwritingPendingHistory() = runBlocking {
        val db = memoryDb()
        db.titleDao().upsertAll(listOf(title("movie")))
        fun event(id: String) = work.kumarfamilynet.cinemarchive.core.database.ViewingEntity(
            id, "movie", "2026-01-01", null, "Local note", null,
        )
        db.viewingDao().upsertAll(listOf(event("existing"), event("pending"), event("deleted")))
        val queue = outbox(db, ScriptedWriter { PushResult.Retry("offline") })
        queue.enqueue("viewing", "pending", "update", JSONObject().put("id", "pending").put("notes", "Local note"))
        queue.enqueue("viewing", "deleted", "delete", JSONObject().put("id", "deleted"))
        db.viewingDao().deleteById("deleted")
        val revision = "2026-01-02T03:04:05.123456Z"
        val page = JSONArray()
        for (id in listOf("existing", "pending", "deleted")) page.put(JSONObject()
            .put("entity_type", "viewing").put("entity_id", id).put("updated_at", revision)
            .put("payload", JSONObject().put("id", id).put("titleId", "movie").put("date", "2026-01-01")
                .put("notes", "Server note").put("updatedAt", "2099-01-01T00:00:00Z")))
        val file = tmpFile("viewing-revision")
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { file }
        prefs.edit { it[intPreferencesKey("sync_schema_version")] = 9; it[stringPreferencesKey("last_synced_at")] = "2026-10-08T00:00:00Z" }
        val http = SyncHttp(ArrayDeque(listOf(page)))
        syncRepository(db, queue, http, file, prefs).syncNow()
        assertEquals("1970-01-01T00:00:00Z", http.requests.single().getString("p_since"))
        assertEquals(revision, db.viewingDao().getById("existing")!!.updatedAt)
        assertEquals("Server note", db.viewingDao().getById("existing")!!.notes)
        assertEquals("Local note", db.viewingDao().getById("pending")!!.notes)
        assertNull("Skipped pending rows cannot borrow a server revision", db.viewingDao().getById("pending")!!.updatedAt)
        assertNull(db.viewingDao().getById("deleted"))
        assertEquals(2, db.outboxDao().getPending().size)
    }

    @Test fun emptyThenOlderRpcDoesNotAcknowledgeMetadataUpgradeBeforeUnchangedRowsCanBeBackfilled() = runTest {
        val db = memoryDb()
        db.titleDao().upsertAll(listOf(title("t1")))
        val outbox = outbox(db, ScriptedWriter { PushResult.Success })
        val oldRow = titleRow("t1", "watched", "2026-01-01T00:00:00Z")
        val upgradedRow = titleRow("t1", "watched", "2026-01-01T00:00:00Z").apply {
            getJSONObject("payload").put("tags", JSONArray().put("Backfilled"))
                .put("studios", JSONArray()).put("collectionId", JSONObject.NULL).put("collectionName", JSONObject.NULL).put("personCreditsVersion", 1).put("titleMetadataVersion", 1)
                .put("backupGraphVersion", 1)
        }
        val http = SyncHttp(ArrayDeque(listOf(JSONArray(), JSONArray().put(oldRow), JSONArray().put(upgradedRow), JSONArray())))
        val file = tmpFile("metadata-rollout")
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { file }
        prefs.edit { it[intPreferencesKey("sync_schema_version")] = 7; it[stringPreferencesKey("last_synced_at")] = "2026-10-08T00:00:00Z" }
        val sync = syncRepository(db, outbox, http, file, prefs)
        sync.syncNow()
        assertEquals("an empty response cannot prove metadata capability", 7, prefs.data.first()[intPreferencesKey("sync_schema_version")])
        sync.syncNow()
        assertEquals(7, prefs.data.first()[intPreferencesKey("sync_schema_version")])
        sync.syncNow()
        assertEquals(List(3) { "1970-01-01T00:00:00Z" }, http.requests.map { it.getString("p_since") })
        assertEquals(listOf("Backfilled"), db.titleDao().getById("t1")!!.tags)
        assertEquals(11, prefs.data.first()[intPreferencesKey("sync_schema_version")])
        sync.syncNow()
        assertEquals("2026-01-01T00:00:00Z", http.requests.last().getString("p_since"))
    }

    @Test fun richMetadataBackfillsOnlyAfterCapabilityAndPendingLegacyEditsDrain() = runTest {
        val db = memoryDb()
        db.titleDao().upsertAll(listOf(title("existing").copy(contentRating = "Local", inHomeCollection = true),
            title("pending", "DROPPED").copy(customWatchUrl = "https://local.example")))
        val writer = ScriptedWriter { PushResult.Retry("offline") }
        val queue = outbox(db, writer)
        queue.enqueue("title", "pending", "update", JSONObject().put("id", "pending").put("status", "DROPPED"))
        fun page(upgraded: Boolean, clear: Boolean = false) = JSONArray().apply {
            for (id in listOf("existing", "pending")) put(titleRow(id, "watched", "2026-01-01T00:00:00Z").apply {
                getJSONObject("payload").put("personCreditsVersion", 1).apply {
                    if (upgraded) put("titleMetadataVersion", 1).put("contentRating", if (clear) JSONObject.NULL else "PG-13")
                        .put("customWatchUrl", if (clear) JSONObject.NULL else "https://server.example/watch")
                        .put("inHomeCollection", false).put("physicalMedia", if (clear) JSONArray() else JSONArray("""[{"id":"copy","format":"DVD","notes":"Keep","unknown":{"v":1}}]"""))
                        .put("rtScore", 0).put("metacriticScore", 0).put("awardsCount", 0)
                }
            })
        }
        val http = SyncHttp(ArrayDeque(listOf(JSONArray(), page(false), page(true), page(true), page(true, true))))
        val file = tmpFile("rich-rollout")
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { file }
        val schema = intPreferencesKey("sync_schema_version")
        prefs.edit { it[schema] = 9; it[stringPreferencesKey("last_synced_at")] = "2026-10-08T00:00:00Z" }
        val sync = syncRepository(db, queue, http, file, prefs)
        sync.syncNow(); sync.syncNow()
        assertEquals(9, prefs.data.first()[schema])
        assertEquals("Local", db.titleDao().getById("existing")!!.contentRating)
        assertEquals(true, db.titleDao().getById("existing")!!.inHomeCollection)
        sync.syncNow()
        assertEquals(9, prefs.data.first()[schema]) // Pending legacy write still protects an unchanged title.
        assertEquals("PG-13", db.titleDao().getById("existing")!!.contentRating)
        assertEquals("https://local.example", db.titleDao().getById("pending")!!.customWatchUrl)
        assertEquals("DROPPED", db.titleDao().getById("pending")!!.status)
        writer.next = { PushResult.Success }; sync.syncNow()
        assertEquals(10, prefs.data.first()[schema])
        assertEquals(List(4) { "1970-01-01T00:00:00Z" }, http.requests.map { it.getString("p_since") })
        assertEquals("https://server.example/watch", db.titleDao().getById("pending")!!.customWatchUrl)
        val shelf = JSONArray(db.titleDao().getById("pending")!!.physicalMediaJson).getJSONObject(0)
        assertEquals("Keep", shelf.getString("notes")); assertEquals(1, shelf.getJSONObject("unknown").getInt("v"))
        sync.syncNow()
        val cleared = db.titleDao().getById("pending")!!
        assertNull(cleared.contentRating); assertNull(cleared.customWatchUrl)
        assertEquals(false, cleared.inHomeCollection); assertEquals("[]", cleared.physicalMediaJson); assertEquals(0, cleared.rtScore)
        // This fixture advertises rich titles but not the newer backup graph capability.
        assertEquals("1970-01-01T00:00:00Z", http.requests.last().getString("p_since"))
    }
}
