package work.kumarfamilynet.cinemarchive

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.feature.library.LibraryScreen

/** Synthetic owner collection: no account, network, or existing device archive is used. */
@RunWith(AndroidJUnit4::class)
class LibraryFiltersScreenTest {
    @get:Rule val compose = createComposeRule()
    private val titles = listOf(
        title("later", "Later film", 2020, 4.5, "fr", listOf("comfort"), "Saga", "2020-01-01"),
        title("earlier", "Earlier film", 2000, 4.0, "en", emptyList(), "Saga", "2000-01-01"),
        title("other", "Other film", 2010, 3.0, "en", emptyList(), null, "2010-01-01"),
    )

    private fun render(restoration: StateRestorationTester? = null) {
        val content: @Composable () -> Unit = {
            var mode by remember { mutableStateOf(LibraryViewMode.LIST) }
            CinemArchiveTheme { LibraryScreen(titles, mode,
                { mode = if (mode == LibraryViewMode.GRID) LibraryViewMode.LIST else LibraryViewMode.GRID },
                2, {}, {}, onTitleClick = {}) }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
    }

    private fun openFilters() = compose.onNodeWithContentDescription("Filter and sort library").performClick()
    private fun choose(label: String) = compose.onNodeWithContentDescription(label).performScrollTo().performClick()
    private fun done() = compose.onNodeWithText("Done").performClick()

    @Test fun compoundFacetsHalfStarsSearchAndResetSurviveRestoration() {
        val restoration = StateRestorationTester(compose)
        render(restoration)
        compose.onNodeWithContentDescription("Search library").performTextInput("Director")
        openFilters()
        choose("Type: Movies")
        choose("Languages: fr")
        choose("Tags: comfort")
        compose.onNodeWithContentDescription("Minimum rating").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(4.5f) }
        done()
        compose.onNodeWithText("1 title on the bill").assertIsDisplayed()
        compose.onNodeWithText("Later film").assertIsDisplayed()
        compose.onNodeWithText("Earlier film").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("1 title on the bill").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search library").performTextReplacement("no match")
        compose.onNodeWithText("No titles match your filters").assertIsDisplayed()
        compose.onNodeWithText("Reset filters").performClick()
        compose.onNodeWithText("3 titles on the bill").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search library").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    }

    @Test fun franchiseGroupingKeepsReleaseOrderInListAndGridAndStatusStillWorks() {
        render()
        openFilters()
        choose("Sort by: Year")
        choose("Direction: Descending")
        choose("Group by: Franchise")
        done()
        compose.onNodeWithText("SAGA").assertIsDisplayed()
        val earlier = compose.onNodeWithText("Earlier film").fetchSemanticsNode().boundsInRoot.top
        val later = compose.onNodeWithText("Later film").fetchSemanticsNode().boundsInRoot.top
        org.junit.Assert.assertTrue(earlier < later)
        compose.onNodeWithText("OTHER TITLES").assertIsDisplayed()
        compose.onNodeWithContentDescription("Switch to grid view").performClick()
        compose.onNodeWithText("SAGA").assertIsDisplayed()
        compose.onNodeWithText("Earlier film").assertIsDisplayed()
        compose.onNodeWithText("Later film").assertIsDisplayed()
        openFilters()
        choose("Group by: Status")
        done()
        compose.onNodeWithText("WATCHED").assertIsDisplayed()
    }

    private fun title(id: String, name: String, year: Int, rating: Double, language: String, tags: List<String>, collection: String?, release: String) =
        LibraryTitle(id, name, year, null, LibraryStatus.WATCHED, MediaType.MOVIE, "Director", null, rating,
            genres = listOf("Drama"), originalLanguage = language, tags = tags, collectionId = if (collection == null) null else 7,
            collectionName = collection, releaseDate = release, addedAt = "2026-01-01T00:00:00Z", lastInteractionAt = "2026-01-01T00:00:00Z")
}
