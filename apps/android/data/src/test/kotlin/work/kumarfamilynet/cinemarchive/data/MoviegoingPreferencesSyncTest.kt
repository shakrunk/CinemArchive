package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
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
class MoviegoingPreferencesSyncTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private val file = File.createTempFile("prefs-sync", ".preferences_pb").also { it.delete(); it.deleteOnExit() }
    private val prefs = PreferenceDataStoreFactory.create(scope = scope) { file }
    private var page = JSONArray()
    private var pending = emptySet<String>()
    private val since = mutableListOf<String>()
    private var failAfter: Int? = null
    private val client = SupabaseRestClient("https://x.supabase.co", "anon", OkHttpClient.Builder().addInterceptor { chain ->
        since += JSONObject(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).getString("p_since")
        if (failAfter == since.size) throw java.io.IOException("Interrupted")
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
            .body(page.toString().toResponseBody("application/json".toMediaType())).build()
    }.build())
    private val version = intPreferencesKey("sync_schema_version")
    private val cursor = stringPreferencesKey("last_synced_at")
    private val stamp = "2026-01-01T00:00:00Z"
    private fun sync() = LibrarySyncRepository(prefs, client, SessionSource { SupabaseSession("token", "owner") },
        db.titleDao(), db.seasonDao(), db.episodeDao(), db.episodeWatchEventDao(), db.episodeRatingDao(), db.episodeReviewDao(),
        db.viewingDao(), db.cinemaOutingDao(), db.titleCastDao(), db.titleCrewDao(), db.listDao(), db.listItemDao(), db.personCreditsDao(),
        pendingKeys = { pending }, transactor = RoomTransactor(db), venueNoteDao = db.venueNoteDao(), theaterInterestDao = db.theaterInterestDao())
    private fun row(type: String, id: String, p: JSONObject, time: String = stamp) = JSONObject().put("entity_type", type)
        .put("entity_id", id).put("updated_at", time).put("payload", p.put("id", id))
    private fun title(capable: Boolean = true) = row("title", "title", JSONObject().put("tmdbId", 42).put("type", "movie")
        .put("title", "Film").put("status", "watchlist").put("addedAt", stamp).put("updatedAt", stamp)
        .put("tags", JSONArray()).put("studios", JSONArray()).put("collectionId", JSONObject.NULL).put("collectionName", JSONObject.NULL)
        .put("personCreditsVersion", 1).put("titleMetadataVersion", 1).put("backupGraphVersion", 1)
        .apply { if (capable) put("moviegoingPreferencesVersion", 1) })
    private fun note(text: String = "Shared") = row("venue_note", "note", JSONObject().put("venue", "Cinema").put("notes", text).put("createdAt", stamp).put("updatedAt", stamp))
    private fun interest() = row("theater_interest", "title", JSONObject().put("titleId", "title").put("createdAt", stamp).put("updatedAt", stamp))
    private fun deleted(type: String, id: String) = row("tombstone", id, JSONObject().put("entityType", type))
    @After fun close() { db.close(); scope.cancel() }

    @Test fun upgradeReplaysUnchangedPreferencesAndOnlyAcknowledgesExplicitCapability() = runBlocking {
        prefs.edit { it[version] = 11; it[cursor] = "2026-10-09T00:00:00Z" }
        page = JSONArray().put(title(false)); sync().syncNow(); assertEquals(11, prefs.data.first()[version])
        page = JSONArray().put(title()).put(note()).put(interest()); sync().syncNow()
        assertEquals(listOf("1970-01-01T00:00:00Z", "1970-01-01T00:00:00Z"), since)
        assertEquals(12, prefs.data.first()[version]); assertEquals("note", db.venueNoteDao().get("Cinema")!!.serverId)
        assertEquals(stamp, db.theaterInterestDao().observeAll().first().single().serverUpdatedAt)
        assertEquals("WATCHLIST", db.titleDao().getById("title")!!.status)
        assertTrue(db.cinemaOutingDao().observeAllOutings().first().isEmpty())
    }
    @Test fun historicalDeleteFollowedByReadditionKeepsCurrentPreferences() = runBlocking {
        page = JSONArray().put(title()).put(deleted("venue_note", "note")).put(deleted("theater_interest", "title")).put(note("")).put(interest())
        sync().syncNow(); assertEquals("", db.venueNoteDao().get("Cinema")!!.notes)
        assertEquals(1, db.theaterInterestDao().observeAll().first().size)
        page = JSONArray().put(note()).put(interest()).put(deleted("venue_note", "note")).put(deleted("theater_interest", "title"))
        sync().syncNow(); assertNull(db.venueNoteDao().get("Cinema")); assertTrue(db.theaterInterestDao().observeAll().first().isEmpty())
    }
    @Test fun pendingNaturalKeyAndDeletesStayProtectedAndForceDurableReplay() = runBlocking {
        prefs.edit { it[version] = 12; it[cursor] = "2026-10-09T00:00:00Z" }
        db.venueNoteDao().upsert(VenueNoteEntity("Cinema", "Offline", stamp))
        pending = setOf("moviegoing:operation", "venue_note_name:Cinema", "theater_interest:title")
        page = JSONArray().put(title()).put(note()).put(interest()); sync().syncNow()
        assertEquals("1970-01-01T00:00:00Z", since.single())
        assertEquals("Offline", db.venueNoteDao().get("Cinema")!!.notes)
        assertTrue(db.theaterInterestDao().observeAll().first().isEmpty())
    }
    @Test fun companionObjectsSurvivePullAndOlderPayloadCannotEraseRetainedIdentity() = runBlocking {
        val raw = JSONArray().put(JSONObject().put("name", "Alex").put("friendUserId", "friend")).put(JSONObject().put("name", "Alex"))
        fun viewing(withCompanions: Boolean) = row("viewing", "view", JSONObject().put("titleId", "title")
            .apply { if (withCompanions) put("companions", raw) })
        page = JSONArray().put(title()).put(viewing(true)); sync().syncNow()
        assertEquals(raw.toString(), db.viewingDao().getById("view")!!.companionsJson)
        assertEquals(listOf("Alex", "Alex"), db.viewingDao().getById("view")!!.companions)
        page = JSONArray().put(viewing(false)); sync().syncNow()
        assertEquals(raw.toString(), db.viewingDao().getById("view")!!.companionsJson)
    }
    @Test fun interruptedBackfillCannotClaimCompleteAndDeletedTitleClearsItsInterest() = runBlocking {
        prefs.edit { it[version] = 11 }
        page = JSONArray().put(title()).put(note()).put(interest())
        repeat(497) { page.put(deleted("episode_rating", "unused$it")) }
        failAfter = 2
        assertTrue(runCatching { sync().syncNow() }.isFailure); assertEquals(11, prefs.data.first()[version])
        failAfter = null; page = JSONArray().put(title()).put(interest()); sync().syncNow()
        assertEquals("1970-01-01T00:00:00Z", since.last()); assertEquals(12, prefs.data.first()[version])
        page = JSONArray().put(deleted("title", "title")); sync().syncNow()
        assertTrue(db.theaterInterestDao().observeAll().first().isEmpty())
    }
}
