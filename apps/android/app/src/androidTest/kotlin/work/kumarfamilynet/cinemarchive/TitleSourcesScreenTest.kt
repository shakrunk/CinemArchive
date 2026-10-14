package work.kumarfamilynet.cinemarchive

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.data.*
import work.kumarfamilynet.cinemarchive.feature.library.TitleSourcesEditor

@RunWith(AndroidJUnit4::class)
class TitleSourcesScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: LibraryDatabase
    private lateinit var library: LibraryRepository
    private val titleId = "20000000-0000-4000-8000-000000000001"
    private val owner = "10000000-0000-4000-8000-000000000001"
    private val stamp = "2026-10-08T01:00:00Z"
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        val outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity) = PushResult.Retry("Offline")
        }, TitleConflictHandler(db.titleDao()), RoomTransactor(db))
        db.titleDao().upsertAll(listOf(TitleEntity(titleId, 42, "MOVIE", "Film", 2026, null, emptyList(),
            null, null, null, 90, null, "WATCHLIST", null, null, stamp, stamp,
            customWatchUrl = "https://example.org/watch", inHomeCollection = true,
            physicalMediaJson = """[{"id":"old","format":"DVD","edition":"Original","notes":"Region 2","opaque":9007199254740993}]""")))
        library = LibraryRepository(db.titleDao(), db.seasonDao(), db.episodeDao(), db.episodeWatchEventDao(),
            db.episodeRatingDao(), db.episodeReviewDao(), db.viewingDao(), db.cinemaOutingDao(), db.titleCastDao(),
            db.titleCrewDao(), db.theaterInterestDao(), outbox, object : EpisodeMetadataFetcher {
                override suspend fun fetchSeasonEpisodes(tmdbId: Int, seasonNumber: Int) = emptyList<MediaEpisode>()
                override suspend fun fetchEpisodeCast(tmdbId: Int, seasonNumber: Int, episodeNumber: Int) = EpisodeCast.EMPTY
            }, db.personCreditsDao(), mutationOwnerId = owner)
    }
    @After fun close() { db.close() }
    private fun show(failFirst: Boolean = false) {
        var fail = failFirst
        compose.setContent { CinemArchiveTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f)) {
                Box(Modifier.width(360.dp).statusBarsPadding()) {
                    TitleSourcesEditor(titleId, { library.prepareTitleSources(titleId) }) { opening, values ->
                        if (fail) { fail = false; error("Storage unavailable; draft retained") }
                        library.saveTitleSources(titleId, opening, values)
                    }
                }
            }
        } }
    }
    private fun open() {
        // Admission becomes visible in Room just before the save coroutine dismisses its dialog.
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Save collection").fetchSemanticsNodes().isEmpty() }
        val edit = compose.onNodeWithText("Edit watch link and collection").assertIsDisplayed()
        edit.performClick()
        try {
            compose.waitUntil(15_000) { compose.onAllNodes(hasText("Save collection") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            val buttons = compose.onAllNodesWithText("Save collection").fetchSemanticsNodes().map { it.config.toString() }
            throw AssertionError("Editor bounds: ${edit.fetchSemanticsNode().boundsInRoot}; save states: $buttons\n" +
                compose.onAllNodes(isRoot(), useUnmergedTree = true)[0].printToString(), failure)
        }
    }

    @Test fun realOwnerEditorAddsCopyAndLinkThroughOneDurableCommand() {
        show(); open()
        compose.onNodeWithText("Watch link for friends").performTextReplacement("https://example.org/new")
        compose.onNodeWithText("Catalog a copy").performScrollTo().performClick()
        compose.onNodeWithText("Edition or packaging").performScrollTo().performTextInput("Steelbook")
        compose.onNodeWithText("Add physical copy").performScrollTo().performClick()
        compose.onNodeWithText("Save collection").performClick()
        compose.waitUntil(15_000) { runBlocking { db.outboxDao().getPending().size == 1 } }
        runBlocking {
            val row = db.titleDao().getById(titleId)!!
            assertEquals("https://example.org/new", row.customWatchUrl)
            assertTrue(row.physicalMediaJson!!.contains("9007199254740993"))
            assertTrue(row.physicalMediaJson!!.contains("Steelbook"))
            assertEquals(stamp, row.updatedAt)
        }
        open()
        compose.onNodeWithText("Blu-ray · Steelbook").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Region 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, runBlocking { db.outboxDao().getPending().size })
    }

    @Test fun failedSaveKeepsClearedDraftForRetryAndCancelDoesNotSave() {
        show(failFirst = true); open()
        compose.onNodeWithText("Watch link for friends").performTextClearance()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Remove DVD (Original)").performScrollTo().performClick()
        compose.onNodeWithText("Save collection").performClick()
        compose.onNodeWithText("Storage unavailable; draft retained").performScrollTo().assertIsDisplayed()
        assertTrue(runBlocking { db.outboxDao().getPending().isEmpty() })
        compose.onNodeWithText("Save collection").performClick()
        compose.waitUntil(15_000) { runBlocking { db.outboxDao().getPending().size == 1 } }
        runBlocking {
            val row = db.titleDao().getById(titleId)!!
            assertNull(row.customWatchUrl); assertEquals(false, row.inHomeCollection); assertEquals("[]", row.physicalMediaJson)
            val patch = JSONObject(db.outboxDao().getPending().single().payloadJson).getJSONObject("titleMetadata").getJSONObject("patch")
            assertEquals(0, patch.getJSONArray("physical_media").length())
        }
        open()
        compose.onNodeWithText("Watch link for friends").performTextInput("https://example.org/cancel")
        compose.onNodeWithText("Cancel").performClick()
        assertNull(runBlocking { db.titleDao().getById(titleId)!!.customWatchUrl })
        assertEquals(1, runBlocking { db.outboxDao().getPending().size })
    }
}
