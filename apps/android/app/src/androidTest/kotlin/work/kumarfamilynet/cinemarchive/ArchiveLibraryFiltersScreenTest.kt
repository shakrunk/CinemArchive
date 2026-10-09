package work.kumarfamilynet.cinemarchive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.core.model.LedgerStats
import work.kumarfamilynet.cinemarchive.data.SharedLibrary
import work.kumarfamilynet.cinemarchive.data.SharedLibrarySnapshot
import work.kumarfamilynet.cinemarchive.data.SharedLibraryTitle

@RunWith(AndroidJUnit4::class)
class ArchiveLibraryFiltersScreenTest {
    @get:Rule val compose = createComposeRule()

    private val alpha = SharedLibraryTitle("a", 1, "movie", "Alpha Film", 2020, null, "watched", 4.5,
        listOf("Drama"), "2026-01-01T00:00:00Z", """{"notes":"Alpha shared notes","director":"Director A",
        "tags":["Favorite"],"network":"Net","original_language":"en","studios":["Studio A"],
        "collection_id":5,"collection_name":"Saga","release_date":"2020-01-01",
        "title_cast":[{"tmdb_person_id":1,"name":"Same Name"}],"viewings":[{"id":"v","viewed_at":"2026-02-01"}]}""")
    private val beta = SharedLibraryTitle("b", 2, "tv", "Beta Series", 1995, null, "watching", 3.0,
        listOf("Comedy"), "2026-01-02T00:00:00Z", """{"director":"Director B","tags":["Slow"],"network":"Stream",
        "original_language":"fr","studios":["Studio B"],"title_cast":[{"tmdb_person_id":2,"name":"Same Name"}]}""")
    private val gamma = SharedLibraryTitle("c", 3, "movie", "Gamma Film", 2022, null, "watchlist", null,
        listOf("Horror"), "2026-01-03T00:00:00Z", """{"collection_id":5,"collection_name":"Saga","release_date":"2022-01-01"}""")
    private fun snapshot(titles: List<SharedLibraryTitle>) = SharedLibrarySnapshot(
        SharedLibrary("friend", titles), emptyList(), LedgerStats(2, 1, 1, 4.0, 90), emptyMap())

    private fun show(titles: List<SharedLibraryTitle> = listOf(alpha, beta, gamma)) {
        compose.setContent { CinemArchiveTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f)) {
                Box(Modifier.width(360.dp)) {
                    ArchiveViewer("viewer:friend", snapshot(titles), null, {}, {}, heading = "Friend's archive")
                }
            }
        } }
    }
    private fun openFilters() = compose.onNodeWithText("Filter & sort", substring = true).performClick()
    private fun choose(description: String) = compose.onNodeWithContentDescription(description).performScrollTo().performClick()
    private fun done() { compose.onNodeWithText("Done").performClick(); compose.waitForIdle() }

    @Test fun combinedFacetsAndRatingPersistAcrossDetailAndLedgerThenReset() {
        show()
        openFilters()
        choose("Type: Movies")
        choose("Genres: Drama")
        choose("Tags: Favorite")
        choose("Languages: en")
        choose("Networks: Net")
        choose("Decades: 2020s")
        choose("Studios: Studio A")
        compose.onNodeWithContentDescription("Minimum rating").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        done()
        compose.onNodeWithText("1 of 3 titles").assertIsDisplayed()
        compose.onAllNodesWithText("Beta Series").assertCountEquals(0)
        compose.onNodeWithText("Alpha Film").performScrollTo().performClick()
        compose.onNodeWithText("Alpha shared notes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("1 of 3 titles").assertIsDisplayed()
        compose.onNodeWithText("The Ledger").performClick()
        compose.onNodeWithText("Library (3)").performClick()
        compose.onNodeWithText("1 of 3 titles").assertIsDisplayed()
        compose.onNodeWithText("Reset filters").performClick()
        compose.onNodeWithText("3 of 3 titles").assertIsDisplayed()
    }

    @Test fun sameNamePersonIdsRemainDistinctAndSortAndFranchiseControlsApply() {
        show()
        openFilters()
        compose.onNodeWithText("Choose person").performScrollTo().performClick()
        compose.onNodeWithTag("person-choice-2").performScrollTo().performClick()
        done()
        compose.onNodeWithText("1 of 3 titles").assertIsDisplayed()
        compose.onNodeWithText("Beta Series").assertIsDisplayed()
        compose.onAllNodesWithText("Alpha Film").assertCountEquals(0)
        compose.onNodeWithText("Reset filters").performClick()
        openFilters()
        choose("Type: Movies")
        choose("Sort by: Title")
        choose("Direction: Ascending")
        done()
        assertTrue(compose.onNodeWithText("Alpha Film").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithText("Gamma Film").fetchSemanticsNode().boundsInRoot.top)
        openFilters(); choose("Direction: Descending"); done()
        assertTrue(compose.onNodeWithText("Gamma Film").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithText("Alpha Film").fetchSemanticsNode().boundsInRoot.top)
        openFilters(); choose("Group by: Franchise"); done()
        compose.onNodeWithText("Saga").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("Alpha Film").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithText("Gamma Film").fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun anonymousScopedSubsetCannotGainExcludedTitlesOrFacetsAfterReset() {
        compose.setContent { CinemArchiveTheme {
            ArchiveViewer("anonymous-token", snapshot(listOf(alpha)), null, {}, {})
        } }
        compose.onNodeWithText("Read only").assertIsDisplayed()
        openFilters()
        compose.onAllNodesWithContentDescription("Genres: Horror").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Networks: Stream").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Studios: Studio B").assertCountEquals(0)
        done()
        compose.onNodeWithText("Search library").performTextInput("Gamma")
        compose.onNodeWithText("0 of 1 titles").assertIsDisplayed()
        compose.onNodeWithText("Reset filters").performClick()
        compose.onNodeWithText("1 of 1 titles").assertIsDisplayed()
        compose.onAllNodesWithText("Gamma Film").assertCountEquals(0)
        compose.onNodeWithText("Alpha Film").performClick()
        listOf("Edit", "Log viewing", "Delete", "Add to list").forEach { compose.onAllNodesWithText(it).assertCountEquals(0) }
    }

    @Test fun switchingViewerScopeClosesOldFiltersAndDropsSelections() {
        val scope = mutableStateOf("viewer-a:friend")
        val archive = mutableStateOf(snapshot(listOf(alpha)))
        compose.setContent { CinemArchiveTheme { ArchiveViewer(scope.value, archive.value, null, {}, {}) } }
        openFilters()
        compose.onNodeWithText("Choose person").performClick()
        compose.onNodeWithTag("person-choice-1").performClick()
        done()
        openFilters()
        compose.runOnIdle { scope.value = "viewer-b:other-friend"; archive.value = snapshot(listOf(beta)) }
        compose.onAllNodesWithText("Done").assertCountEquals(0)
        compose.onNodeWithText("1 of 1 titles").assertIsDisplayed()
        compose.onNodeWithText("Beta Series").assertIsDisplayed()
        compose.onAllNodesWithText("Alpha Film").assertCountEquals(0)
        compose.onAllNodesWithText("Reset filters").assertCountEquals(0)
    }
}
