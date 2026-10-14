package work.kumarfamilynet.cinemarchive

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.data.*
import work.kumarfamilynet.cinemarchive.feature.library.*

@RunWith(AndroidJUnit4::class)
class TitleMetadataScreenTest {
    @get:Rule val compose = createComposeRule()
    private val owner = "10000000-0000-4000-8000-000000000001"
    private val titleId = "20000000-0000-4000-8000-000000000001"
    private val baseline = "2026-10-08T01:00:00Z"
    private var database: LibraryDatabase? = null
    @After fun close() { database?.close() }

    @Test fun failedSaveRetainsInputAndExactCaseTagsCanBeRemovedOrExplicitlyCleared() {
        var failSave = true
        var tags by mutableStateOf(listOf("Noir"))
        compose.setContent { CinemArchiveTheme {
            Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
                TitleTagsEditor(titleId, tags) { next -> if (failSave) error("Disk full") else tags = next }
            }
        } }
        compose.onNodeWithText("New tag name").performTextInput("  noir,,")
        compose.onNodeWithText("Couldn't save tags. Your input is still here; try again.").assertIsDisplayed()
        compose.onNodeWithText("New tag name").assertTextContains("  noir,,")
        compose.runOnIdle { failSave = false }
        compose.onNodeWithText("Add tag").performClick()
        compose.onNodeWithText("Remove tag Noir").assertIsDisplayed()
        compose.onNodeWithText("Remove tag noir").assertIsDisplayed()
        compose.onNodeWithText("New tag name").performTextInput("Noir,")
        compose.runOnIdle { assertEquals(listOf("Noir", "noir"), tags) }
        compose.onNodeWithText("Remove tag noir").performClick()
        compose.onNodeWithText("Clear all tags").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(listOf("Noir"), tags) }
        compose.onNodeWithText("Clear all tags").performClick()
        compose.onNodeWithText("Clear tags").performClick()
        compose.runOnIdle { assertTrue(tags.isEmpty()) }
    }

    @Test fun switchingTitleDropsPrivateUnsavedInput() {
        var selected by mutableStateOf("first")
        compose.setContent { CinemArchiveTheme { TitleTagsEditor(selected, emptyList()) {} } }
        compose.onNodeWithText("New tag name").performTextInput("Private unfinished tag")
        compose.runOnIdle { selected = "second" }
        compose.onNodeWithText("New tag name").assertTextEquals("New tag name", "")
    }

    @Test fun retainedDraftRequiresComparisonAndExplicitDiscardKeepsServerValues() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java)
            .allowMainThreadQueries().build().also { database = it }
        val local = TitleEntity(titleId, 42, "MOVIE", "Film", 2026, null, emptyList(), null, null, null, 90,
            null, "WATCHLIST", null, "Keep notes", baseline, baseline, tags = listOf("Saved"))
        db.titleDao().upsertAll(listOf(local))
        val draft = JSONObject().put("ownerId", owner).put("titleId", titleId)
            .put("titleMetadata", JSONObject().put("version", 1).put("patch", JSONObject().put("tags", JSONArray().put("Saved"))))
        db.outboxDao().enqueue(OutboxEntity("30000000-0000-4000-8000-000000000001", "title", titleId, "review", draft.toString(), 1))
        val outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity): PushResult = error("A review draft must not be sent")
        }, TitleConflictHandler(db.titleDao()), RoomTransactor(db))
        val current = JSONObject().put("id", titleId).put("user_id", owner).put("updated_at", baseline)
            .put("title", "Film").put("tmdb_id", 42).put("type", "movie").put("added_at", baseline)
            .put("status", "watchlist").put("rating", JSONObject.NULL).put("tags", JSONArray().put("Server"))
        val source = TitleMetadataRepository(db, outbox, owner, SessionSource { SupabaseSession("token", owner) }, object : TitleMetadataRemote {
            override suspend fun current(titleId: String) = JSONObject(current.toString())
            override suspend fun push(entry: OutboxEntity): PushResult = error("Use queue")
        }, synchronize = outbox::flush, replayBoundary = { it() })
        compose.setContent { CinemArchiveTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { TitleMetadataRecoveryPanel(source, titleId) }
        } }
        compose.waitUntil { compose.onAllNodesWithText("Compare saved edits").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Compare saved edits").performClick()
        compose.waitUntil { compose.onAllNodesWithText("Tags: Server").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Tags: Server").assertIsDisplayed()
        compose.onNodeWithText("Tags: Saved").assertIsDisplayed()
        compose.onNodeWithText("Keep for later").performClick()
        assertEquals(1, db.outboxDao().getPending().size)
        compose.onNodeWithText("Compare saved edits").performClick()
        compose.waitUntil { compose.onAllNodesWithText("Discard saved edits").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Discard saved edits").performScrollTo().performClick()
        compose.onNodeWithText("Discard saved title edits?").assertIsDisplayed()
        assertEquals(1, db.outboxDao().getPending().size)
        compose.onNodeWithText("Discard changes").performClick()
        compose.waitUntil { compose.onAllNodesWithText("Saved title changes").fetchSemanticsNodes().isEmpty() }
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertEquals(listOf("Server"), db.titleDao().getById(titleId)!!.tags)
    }
}
