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
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*

@RunWith(RobolectricTestRunner::class)
class BackupGraphSyncTest {
    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
    private val file = File.createTempFile("backup-graph", ".preferences_pb").also { it.delete(); it.deleteOnExit() }
    private val prefs = PreferenceDataStoreFactory.create(scope = scope) { file }
    private val version = intPreferencesKey("sync_schema_version")
    private val cursor = stringPreferencesKey("last_synced_at")
    private val requests = mutableListOf<JSONObject>()
    private var page = JSONArray()
    private var pending: suspend () -> Set<String> = { emptySet() }
    private var networkFails = false
    private var scriptedPages: ArrayDeque<JSONArray>? = null
    private var beforeResponse: (Int) -> Unit = {}
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        requests += JSONObject(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
        beforeResponse(requests.size)
        if (networkFails) throw java.io.IOException("Offline")
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
            .body((scriptedPages?.removeFirstOrNull() ?: page).toString().toResponseBody("application/json".toMediaType())).build()
    }.build()
    private fun repository() = LibrarySyncRepository(prefs, SupabaseRestClient("https://x.supabase.co", "anon", http),
        SessionSource { SupabaseSession("token", "owner") }, db.titleDao(), db.seasonDao(), db.episodeDao(), db.episodeWatchEventDao(),
        db.episodeRatingDao(), db.episodeReviewDao(), db.viewingDao(), db.cinemaOutingDao(), db.titleCastDao(), db.titleCrewDao(),
        db.listDao(), db.listItemDao(), db.personCreditsDao(), pendingKeys = { pending() }, transactor = RoomTransactor(db))
    @After fun close() { db.close(); scope.cancel() }
    private suspend fun oldCursor() { prefs.edit { it[version] = 10; it[cursor] = "2026-10-08T00:00:00Z" } }
    private fun row(type: String, id: String, payload: JSONObject) = JSONObject().put("entity_type", type).put("entity_id", id)
        .put("updated_at", "2026-01-01T00:00:00Z").put("payload", payload.put("id", id))
    private fun graph(capable: Boolean = true, fields: Boolean = true, clear: Boolean = false): JSONArray {
        fun profile() = if (clear) JSONObject.NULL else "https://image/person"
        fun count() = if (clear) JSONObject.NULL else 0
        val title = JSONObject().put("tmdbId", 42).put("type", "tv").put("title", "Show").put("status", "watching")
            .put("addedAt", "2026-01-01T00:00:00Z").put("updatedAt", "2026-01-01T00:00:00Z")
            .put("personCreditsVersion", 1).put("titleMetadataVersion", 1)
        if (capable) title.put("backupGraphVersion", 1).put("moviegoingPreferencesVersion", 1)
        fun credit() = JSONObject().put("titleId", "title").put("tmdbPersonId", 42).put("name", "Actor").put("castOrder", 0)
            .apply { if (fields) put("profileUrl", profile()).put("episodeCount", count()) }
        fun log() = JSONObject().put("episodeId", "episode").apply { if (fields) put("colorMode", if (clear) JSONObject.NULL else "bw") }
        return JSONArray().put(row("title", "title", title))
            .put(row("season", "season", JSONObject().put("titleId", "title").put("seasonNumber", 0).put("episodeCount", 1).put("episodesWatched", 1)))
            .put(row("episode", "episode", JSONObject().put("titleId", "title").put("seasonNumber", 0).put("episodeNumber", 1)))
            .put(row("title_cast", "cast", credit()))
            .put(row("title_crew", "crew", credit().put("job", "Director")))
            .put(row("season_cast", "season-cast", credit().put("seasonId", "season")))
            .put(row("episode_watch_event", "watch", log().put("notes", "Memory")))
            .put(row("episode_review", "review", log().put("reviewText", "Review").put("reviewedAt", "2026-01-01T00:00:00Z")))
    }
    @Test fun fullGraphBackfillsUnchangedRowsAndAcknowledgesCapability() = runBlocking {
        oldCursor(); page = graph(); repository().syncNow()
        assertEquals("1970-01-01T00:00:00Z", requests.single().getString("p_since"))
        assertEquals(12, prefs.data.first()[version])
        assertEquals("https://image/person", db.titleCastDao().observeAllCast().first().single().profileUrl)
        assertEquals(0, db.titleCastDao().observeAllCast().first().single().episodeCount)
        assertEquals("https://image/person", db.titleCrewDao().observeAllCrew().first().single().profileUrl)
        assertEquals("https://image/person", db.personCreditsDao().observeSeasonCast().first().single().profileUrl)
        assertEquals(0, db.personCreditsDao().observeSeasonCast().first().single().episodeCount)
        assertEquals("bw", db.episodeWatchEventDao().observeAllWatchEvents().first().single().colorMode)
        assertEquals("bw", db.episodeReviewDao().observeReviews("title").first().single().colorMode)
        repository().syncNow()
        assertEquals("2026-01-01T00:00:00Z", requests.last().getString("p_since"))
    }
    @Test fun emptyAndOlderServerCannotAcknowledgeBackfill() = runBlocking {
        oldCursor(); repository().syncNow(); assertEquals(10, prefs.data.first()[version])
        page = graph(capable = false, fields = false); repository().syncNow(); assertEquals(10, prefs.data.first()[version])
        page = graph(); repository().syncNow(); assertEquals(12, prefs.data.first()[version])
        assertEquals(List(3) { "1970-01-01T00:00:00Z" }, requests.map { it.getString("p_since") })
    }
    @Test fun absentFieldsRetainValuesAndExplicitNullClearsThem() = runBlocking {
        page = graph(); repository().syncNow()
        page = graph(capable = false, fields = false); repository().syncNow()
        assertEquals("https://image/person", db.titleCastDao().observeAllCast().first().single().profileUrl)
        assertEquals(0, db.titleCastDao().observeAllCast().first().single().episodeCount)
        assertEquals("https://image/person", db.titleCrewDao().observeAllCrew().first().single().profileUrl)
        assertEquals(0, db.personCreditsDao().observeSeasonCast().first().single().episodeCount)
        assertEquals("bw", db.episodeWatchEventDao().observeAllWatchEvents().first().single().colorMode)
        assertEquals("bw", db.episodeReviewDao().observeReviews("title").first().single().colorMode)
        page = graph(clear = true); repository().syncNow()
        assertNull(db.titleCastDao().observeAllCast().first().single().profileUrl)
        assertNull(db.titleCastDao().observeAllCast().first().single().episodeCount)
        assertNull(db.titleCrewDao().observeAllCrew().first().single().profileUrl)
        assertNull(db.personCreditsDao().observeSeasonCast().first().single().profileUrl)
        assertNull(db.personCreditsDao().observeSeasonCast().first().single().episodeCount)
        assertNull(db.episodeWatchEventDao().observeAllWatchEvents().first().single().colorMode)
        assertNull(db.episodeReviewDao().observeReviews("title").first().single().colorMode)
    }
    @Test fun skippedProtectedRowsKeepBackfillPendingEvenIfProtectionDisappearsLaterInSameRun() = runBlocking {
        page = graph(capable = false, fields = false); repository().syncNow(); oldCursor()
        var calls = 0
        pending = { calls++; if (calls == 2) setOf("title_cast:cast", "episode_watch_event:watch", "episode_review:review") else emptySet() }
        page = graph(); repository().syncNow()
        assertEquals(10, prefs.data.first()[version]); assertNull(db.titleCastDao().observeAllCast().first().single().profileUrl)
        assertNull(db.episodeWatchEventDao().observeAllWatchEvents().first().single().colorMode)
        assertNull(db.episodeReviewDao().observeReviews("title").first().single().colorMode)
        pending = { emptySet() }; repository().syncNow()
        assertEquals(12, prefs.data.first()[version])
        assertEquals("https://image/person", db.titleCastDao().observeAllCast().first().single().profileUrl)
        assertEquals("bw", db.episodeReviewDao().observeReviews("title").first().single().colorMode)
        assertEquals("1970-01-01T00:00:00Z", requests.last().getString("p_since"))
    }
    @Test fun interruptedBackfillRetainsOldVersionAndRestartsFromEpoch() = runBlocking {
        oldCursor(); page = graph(); networkFails = true
        try { repository().syncNow(); fail("Expected offline failure") } catch (_: java.io.IOException) { }
        assertEquals(10, prefs.data.first()[version]); networkFails = false
        repository().syncNow(); assertEquals(12, prefs.data.first()[version])
        assertEquals(List(2) { "1970-01-01T00:00:00Z" }, requests.map { it.getString("p_since") })
    }

    private fun fullPage(rows: JSONArray): JSONArray = rows.apply {
        val stamp = getJSONObject(0).getString("updated_at")
        repeat(500 - length()) { index ->
            // Valid server rows reach the paging boundary without hundreds of unrelated
            // title insert-then-update conflicts in Robolectric's legacy SQLite driver.
            put(row("tombstone", java.util.UUID(0, index.toLong() + 1).toString(), JSONObject().put("entityType", "episode_rating"))
                .put("updated_at", stamp))
        }
    }

    @Test fun pageTwoFailureReplaysFromEpochDespitePersistedFirstPageCursor() = runBlocking {
        oldCursor(); page = fullPage(graph())
        beforeResponse = { if (it == 2) networkFails = true }
        try { repository().syncNow(); fail("Expected second-page failure") } catch (_: java.io.IOException) { }
        assertEquals("2026-01-01T00:00:00Z", prefs.data.first()[cursor])
        assertEquals(10, prefs.data.first()[version])
        assertEquals("bw", db.episodeWatchEventDao().observeAllWatchEvents().first().single().colorMode)
        networkFails = false; beforeResponse = {}; page = graph()
        repository().syncNow()
        assertEquals(12, prefs.data.first()[version])
        assertEquals(listOf("1970-01-01T00:00:00Z", "2026-01-01T00:00:00Z", "1970-01-01T00:00:00Z"), requests.map { it.getString("p_since") })
    }

    @Test fun deferredCreditCannotOverwriteAnEditQueuedAfterItsParentArrives() = runBlocking {
        oldCursor()
        val source = graph()
        val child = source.getJSONObject(3)
        val parent = source.getJSONObject(0).apply {
            put("updated_at", "2026-01-02T00:00:00Z")
            getJSONObject("payload").put("updatedAt", "2026-01-02T00:00:00Z")
        }
        scriptedPages = ArrayDeque(listOf(fullPage(JSONArray().put(child)), fullPage(JSONArray().put(parent)), JSONArray()))
        val waiting = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        beforeResponse = { if (it == 3) { waiting.countDown(); check(release.await(20, java.util.concurrent.TimeUnit.SECONDS)) } }
        val box = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity) = PushResult.Retry("Offline")
        }, TitleConflictHandler(db.titleDao()), RoomTransactor(db))
        pending = box::pendingEntityKeys
        val running = async(Dispatchers.IO) { repository().syncNow() }
        try {
            withTimeout(20_000) { withContext(Dispatchers.IO) {
                check(waiting.await(15, java.util.concurrent.TimeUnit.SECONDS)) { "Third request not reached; observed ${requests.size} requests" }
            } }
            assertNotNull(db.titleDao().getById("title"))
            box.atomically {
                db.titleCastDao().upsertAll(listOf(TitleCastEntity("new-cast", "title", 42, "Fresh local name", "New role", 0, "https://image/local", 8)))
                // The production credit command protects the entire title's graph.
                box.enqueue("title_credits", "title", "review", JSONObject().put("titleId", "title").put("immutable", true))
            }
        } finally { release.countDown() }
        running.await()
        val retained = db.titleCastDao().observeAllCast().first().single()
        assertEquals("new-cast", retained.id); assertEquals("Fresh local name", retained.name)
        assertEquals("https://image/local", retained.profileUrl); assertEquals(8, retained.episodeCount)
        assertTrue(JSONObject(db.outboxDao().getPending().single().payloadJson).getBoolean("immutable"))
        assertEquals(10, prefs.data.first()[version])
        // Explicit recovery clears only after arranging a replay; stale skipped rows must be retried.
        db.outboxDao().getPending().forEach { db.outboxDao().remove(it.id) }
        beforeResponse = {}; scriptedPages = null; page = graph()
        repository().syncNow()
        assertEquals("1970-01-01T00:00:00Z", requests.last().getString("p_since"))
        assertEquals(12, prefs.data.first()[version])
        assertEquals("https://image/person", db.titleCastDao().observeAllCast().first().single { it.id == "cast" }.profileUrl)
    }
}
