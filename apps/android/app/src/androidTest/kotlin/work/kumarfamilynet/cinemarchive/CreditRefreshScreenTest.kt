package work.kumarfamilynet.cinemarchive

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.feature.library.TitleDetailScreen

@RunWith(AndroidJUnit4::class)
class CreditRefreshScreenTest {
    @get:Rule val compose=createComposeRule()
    private fun detail()=TitleDetail("film",MediaType.MOVIE,"Film",2020,null,null,null,null,null,100,
        LibraryStatus.WATCHED,4.5,"My notes",emptyList(),emptyList(),emptyList())
    private fun scrollTo(text:String) = compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        .performScrollToNode(hasText(text))

    @Test fun failedRefreshCanRetryAndRecoveredCreditNavigatesByProviderIdentity() {
        var calls=0; var selected:LibraryPerson?=null
        compose.setContent { CinemArchiveTheme {
            var title by remember { mutableStateOf(detail()) }
            TitleDetailScreen(title,{},onBrowsePerson={selected=it},onRefreshCredits={
                calls++
                if(calls==1) error("No connection. Try again.")
                title=title.copy(cast=listOf(PersonCredit(84,"Recovered person","Actor")))
                true
            })
        } }
        scrollTo("Refresh metadata")
        compose.onNodeWithText("Refresh metadata").performClick()
        compose.onNodeWithText("No connection. Try again.").assertIsDisplayed()
        compose.onNodeWithText("Refresh metadata").performClick()
        compose.onNodeWithText("Metadata refresh saved. Missing episodes will appear after sync.").assertExists()
        compose.onNodeWithTag("credit-Cast-84").performClick()
        compose.runOnIdle { assertEquals(LibraryPerson(84,"Recovered person"),selected); assertEquals(2,calls) }
    }

    @Test fun inFlightRefreshDisablesDuplicateRequestAndUnchangedResultIsClear() {
        val result=CompletableDeferred<Boolean>(); var calls=0
        compose.setContent { CinemArchiveTheme {
            TitleDetailScreen(detail(),{},onRefreshCredits={calls++; result.await()})
        } }
        scrollTo("Refresh metadata")
        compose.onNodeWithText("Refresh metadata").performClick()
        compose.onNodeWithText("Refreshing metadata…").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1,calls); result.complete(false) }
        compose.onNodeWithText("Metadata is up to date.").assertExists()
        compose.onNodeWithText("Refresh metadata").assertIsEnabled()
    }
}
