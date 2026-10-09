package work.kumarfamilynet.cinemarchive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
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
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.feature.library.TitleDetailScreen

@RunWith(AndroidJUnit4::class)
class CatalogDetailsScreenTest {
    @get:Rule val compose=createComposeRule()
    private fun detail()=TitleDetail("film",MediaType.MOVIE,"Film",2020,null,null,null,null,null,null,
        LibraryStatus.WATCHLIST,null,null,emptyList(),emptyList(),emptyList())
    private fun scrollTo(text:String)=compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        .performScrollToNode(hasText(text))

    @Test fun storedDetailsWrapAtNarrowLargeTextAndNeverExposeEditingControls() {
        compose.setContent { CinemArchiveTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,fontScale=1.3f)) {
                Box(Modifier.width(280.dp).testTag("narrow-details-root")) {
                    TitleDetailScreen(detail().copy(tags=listOf("Family movie night","An unusually long descriptive catalog tag"),
                        originalLanguage="ja",releaseDate="2020-01-01",studios=listOf("A very long production studio name","Studio B"),
                        collectionName="Example Collection",addedAt="2026-10-08",imdbRating=8.2),{})
                }
            }
        } }
        scrollTo("Japanese"); compose.onNodeWithText("Japanese").assertIsDisplayed()
        scrollTo("Jan 1, 2020"); compose.onNodeWithText("Released").assertExists()
        scrollTo("Example"); compose.onNodeWithText("Example").assertIsDisplayed()
        scrollTo("8.2/10"); compose.onNodeWithText("8.2/10").assertIsDisplayed()
        scrollTo("An unusually long descriptive catalog tag")
        compose.onNodeWithText("An unusually long descriptive catalog tag").assertIsDisplayed()
        val card=compose.onNodeWithTag("catalog-details").fetchSemanticsNode().boundsInRoot
        val container=compose.onNodeWithTag("narrow-details-root").fetchSemanticsNode().boundsInRoot
        assertTrue(card.left>=container.left && card.right<=container.right)
        compose.onAllNodesWithContentDescription("Add tag").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Remove tag Family movie night").assertCountEquals(0)
    }

    @Test fun absentFieldsAndSwitchingToAnotherTitleDoNotLeaveStaleMetadata() {
        val current=mutableStateOf(detail().copy(tags=listOf("Private catalog tag"),imdbRating=8.2))
        compose.setContent { CinemArchiveTheme { TitleDetailScreen(current.value,{}) } }
        scrollTo("Private catalog tag"); compose.onNodeWithText("Private catalog tag").assertIsDisplayed()
        compose.runOnIdle { current.value=detail().copy(id="other",title="Other film") }
        compose.onAllNodesWithTag("catalog-details").assertCountEquals(0)
        compose.onAllNodesWithText("Private catalog tag").assertCountEquals(0)
        compose.onAllNodesWithText("8.2/10").assertCountEquals(0)
    }
}
