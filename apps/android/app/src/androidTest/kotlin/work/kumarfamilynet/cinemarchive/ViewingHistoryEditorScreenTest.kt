package work.kumarfamilynet.cinemarchive

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.data.*
import work.kumarfamilynet.cinemarchive.feature.library.TitleDetailScreen

@RunWith(AndroidJUnit4::class)
class ViewingHistoryEditorScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun scrollTo(text: String) = compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        .performScrollToNode(hasText(text))

    @Test fun realHistorySaveRestoresAfterLostResultAndKeepsSameCommandsThenEditsAndDeletesExactEvent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "viewing-ui-${UUID.randomUUID()}.db"
        val db = LibraryDatabase.create(context, name)
        val owner = UUID.randomUUID().toString(); val title = UUID.randomUUID().toString()
        val outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity) = PushResult.Retry("Offline fixture")
        }, TitleConflictHandler(db.titleDao()), RoomTransactor(db))
        val repo = LibraryRepository(db.titleDao(), db.seasonDao(), db.episodeDao(), db.episodeWatchEventDao(), db.episodeRatingDao(), db.episodeReviewDao(),
            db.viewingDao(), db.cinemaOutingDao(), db.titleCastDao(), db.titleCrewDao(), db.theaterInterestDao(), outbox,
            object : EpisodeMetadataFetcher {
                override suspend fun fetchSeasonEpisodes(tmdbId: Int, seasonNumber: Int) = emptyList<MediaEpisode>()
                override suspend fun fetchEpisodeCast(tmdbId: Int, seasonNumber: Int, episodeNumber: Int) = EpisodeCast.EMPTY
            }, db.personCreditsDao(), owner, db.viewingCompletionAliasDao(), { true })
        var loseResult = true
        val saved = mutableListOf<ViewingDraft>()
        try {
            runBlocking(Dispatchers.IO) { db.titleDao().upsertAll(listOf(TitleEntity(title, 42, "MOVIE", "Synthetic history", 2020, null,
                emptyList(), null, null, null, 100, null, "WATCHLIST", null, null, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"))) }
            val restoration = StateRestorationTester(compose)
            restoration.setContent { CinemArchiveTheme {
                val detail by repo.observeTitleDetail(title).collectAsState(initial = null)
                TitleDetailScreen(detail, {}, viewingOwnerId = owner, viewingTitleId = title,
                    onPrepareViewing = { repo.prepareViewingEdit(title, it) },
                    onSaveViewing = { draft, isNew ->
                        repo.saveViewing(title, draft, isNew); saved += draft
                        if (loseResult) { loseResult = false; error("Saved locally; retry confirmation") }
                    }, onDeleteViewing = { repo.deleteViewing(title, it) })
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Synthetic history").fetchSemanticsNodes().isNotEmpty() }
            scrollTo("Log a viewing"); compose.onNodeWithText("Log a viewing").performClick()
            compose.onNodeWithText("Notes").performScrollTo().performTextInput("Restored viewing note")
            compose.onNodeWithText("Save viewing").performScrollTo().performClick()
            compose.onNodeWithText("Saved locally; retry confirmation").assertExists()
            val first = runBlocking(Dispatchers.IO) { db.outboxDao().getPending() }
            assertEquals(1, first.size)
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("Notes").performScrollTo().assertTextContains("Restored viewing note")
            compose.onNodeWithText("Save viewing").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Save viewing").fetchSemanticsNodes().isEmpty() }
            assertEquals(2, saved.size); assertEquals(saved[0].openingContext, saved[1].openingContext)
            runBlocking(Dispatchers.IO) { assertEquals(first, db.outboxDao().getPending()) }
            scrollTo("Edit viewing"); compose.onNodeWithText("Edit viewing").performClick()
            compose.onNodeWithText("Notes").performScrollTo().performTextClearance()
            compose.onNodeWithText("Save viewing").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Save viewing").fetchSemanticsNodes().isEmpty() }
            runBlocking(Dispatchers.IO) {
                val row = db.viewingDao().getById(saved[0].id)!!
                assertNull(row.notes)
                val update = db.outboxDao().getPending().last()
                val op = JSONObject(update.payloadJson).getJSONObject("viewingCommand").getJSONArray("operations").getJSONObject(0)
                assertEquals(first.first().id, op.getString("expectedOperationId"))
                assertTrue(op.getJSONObject("values").isNull("notes"))
            }
            scrollTo("Delete viewing"); compose.onNodeWithText("Delete viewing").performClick()
            compose.onNode(hasText("Delete viewing") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Delete this viewing?").fetchSemanticsNodes().isEmpty() }
            runBlocking(Dispatchers.IO) {
                assertNull(db.viewingDao().getById(saved[0].id))
                val queue = db.outboxDao().getPending()
                val op = JSONObject(queue.last().payloadJson).getJSONObject("viewingCommand").getJSONArray("operations").getJSONObject(0)
                assertEquals("delete", op.getString("action")); assertEquals(queue[1].id, op.getString("expectedOperationId"))
                assertEquals("WATCHED", db.titleDao().getById(title)!!.status)
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun restoredDeleteRetainsOpeningAndOtherOwnerCannotSeeOrSubmitSavedForm() {
        val owner = mutableStateOf("owner-a")
        val viewing = Viewing("event", "2026-01-01", 4.0, "Private memory", null)
        val detail = TitleDetail("title", MediaType.MOVIE, "Synthetic owner history", 2020, null, null, null, null, null, 100,
            LibraryStatus.WATCHED, null, null, emptyList(), emptyList(), listOf(viewing))
        val deleted = mutableListOf<ViewingDraft>()
        var preparations = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CinemArchiveTheme {
            TitleDetailScreen(detail, {}, viewingOwnerId = owner.value,
                onPrepareViewing = { preparations++; ViewingDraft(viewing.id, viewing.date, viewing.rating, viewing.notes, viewing.venue, openingContext = "original-guard") },
                onDeleteViewing = { deleted += it })
        } }
        scrollTo("Delete viewing"); compose.onNodeWithText("Delete viewing").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasText("Delete viewing") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals("original-guard", deleted.single().openingContext); assertEquals(1, preparations) }
        scrollTo("Edit viewing"); compose.onNodeWithText("Edit viewing").performClick()
        compose.onNodeWithText("Notes").performScrollTo().assertTextContains("Private memory")
        compose.runOnIdle { owner.value = "owner-b" }
        restoration.emulateSavedInstanceStateRestore()
        compose.onAllNodesWithText("Save viewing").assertCountEquals(0)
        compose.runOnIdle { assertEquals(1, deleted.size) }
    }
}
