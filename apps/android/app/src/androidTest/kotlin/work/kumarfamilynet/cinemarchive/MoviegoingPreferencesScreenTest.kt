package work.kumarfamilynet.cinemarchive

import android.content.Context
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.data.*
import work.kumarfamilynet.cinemarchive.feature.library.VenueNoteEditor
import work.kumarfamilynet.cinemarchive.feature.settings.MoviegoingPreferencesPanel

@RunWith(AndroidJUnit4::class)
class MoviegoingPreferencesScreenTest {
    @get:Rule val compose = createComposeRule()
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val noteId = "22222222-2222-4222-8222-222222222222"
    private val stamp = "2026-10-08T00:00:00Z"
    private lateinit var db: LibraryDatabase
    private lateinit var box: MutationOutbox
    private lateinit var repo: MoviegoingPreferencesRepository
    private val originals = MutableStateFlow<Map<String, String>>(emptyMap())
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        box = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity) = PushResult.Review("Another device saved a newer note. Compare in Profile.")
        }, TitleConflictHandler(db.titleDao()), RoomTransactor(db))
        val row = JSONObject().put("id", noteId).put("user_id", owner).put("venue", "Cinema")
            .put("notes", "Current account note").put("created_at", stamp).put("updated_at", "2026-10-09T00:00:00Z")
        val client = SupabaseRestClient("https://fixture.supabase.co", "anon", OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(JSONArray().put(row).toString().toResponseBody("application/json".toMediaType())).build()
        }.build())
        val archive = object : OutingRecoveryArchive {
            override val records = originals
            override suspend fun put(id: String, record: String) { originals.value += id to record }
        }
        repo = MoviegoingPreferencesRepository(db, box, owner, { true }, client, { SupabaseSession("fixture", owner) }, archive, box::flush, { it() })
        db.venueNoteDao().upsert(VenueNoteEntity("Cinema", "Original parking note", stamp, noteId, stamp, stamp))
    }
    @After fun close() { db.close() }
    @Test fun venueEditorQueuesEmptyNoteSeparatelyFromExplicitRemoval() {
        compose.setContent { CinemArchiveTheme {
            var editing by remember { mutableStateOf(true) }
            if (editing) VenueNoteEditor(repo, "Cinema") { editing = false }
            else Button(onClick = { editing = true }) { Text("Edit again") }
        } }
        awaitEditor()
        compose.onNode(hasSetTextAction()).assertTextContains("Original parking note")
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithText("Save note").performClick()
        compose.waitUntil(5_000) { runBlocking { db.outboxDao().getPending().size == 1 } }
        runBlocking { assertEquals("", db.venueNoteDao().get("Cinema")!!.notes) }
        compose.onNodeWithText("Edit again").performClick()
        awaitEditor()
        compose.onNodeWithText("Remove saved note").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { db.outboxDao().getPending().size == 2 } }
        runBlocking {
            val entries = db.outboxDao().getPending()
            assertEquals("update", JSONObject(entries.first().payloadJson).getJSONObject("operation").getString("action"))
            assertEquals("delete", JSONObject(entries.last().payloadJson).getJSONObject("operation").getString("action"))
            assertEquals(entries.first().id, JSONObject(entries.last().payloadJson).getJSONObject("operation").getString("expectedOperationId"))
            assertNull(db.venueNoteDao().get("Cinema"))
        }
    }
    @Test fun profileComparesRejectedNoteAndExplicitlyDiscardsWithoutLosingOriginal() {
        runBlocking { repo.saveVenue(repo.captureVenue("Cinema"), "Saved offline note"); box.flush() }
        compose.setContent { CinemArchiveTheme { MoviegoingPreferencesPanel(repo) { venue, dismiss -> VenueNoteEditor(repo, venue, dismiss) } } }
        compose.onNodeWithText("Venue notes and theater interest", substring = true).performClick()
        compose.onNodeWithText("Compare saved change").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Current account: Current account note").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Current account: Current account note").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Saved: Saved offline note").assertExists()
        compose.onNodeWithText("Copy preserved original changes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Discard saved change").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { db.outboxDao().getPending().isEmpty() } }
        runBlocking { assertEquals("Current account note", db.venueNoteDao().get("Cinema")!!.notes) }
        assertTrue(originals.value.values.single().contains("Saved offline note"))
    }
    private fun awaitEditor() {
        try {
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasText("Save note") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            throw AssertionError(compose.onRoot(useUnmergedTree = true).printToString(), error)
        }
    }
}
