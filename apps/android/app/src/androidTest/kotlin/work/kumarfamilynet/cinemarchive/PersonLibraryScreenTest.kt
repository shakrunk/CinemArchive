package work.kumarfamilynet.cinemarchive

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaverScope
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
import work.kumarfamilynet.cinemarchive.feature.library.TitleDetailScreen
import work.kumarfamilynet.cinemarchive.feature.library.rememberAccountLibraryFilters
import work.kumarfamilynet.cinemarchive.feature.library.accountLibraryFiltersSaver

@RunWith(AndroidJUnit4::class)
class PersonLibraryScreenTest {
    @get:Rule val compose = createComposeRule()
    private val person = LibraryPerson(42, "Same Name")
    private val titles = listOf(
        LibraryTitle("first", "First film", 2020, null, LibraryStatus.WATCHED, MediaType.MOVIE, null, null, 4.0, people = listOf(person)),
        LibraryTitle("second", "Second film", 2021, null, LibraryStatus.WATCHED, MediaType.MOVIE, null, null, 4.0, people = listOf(LibraryPerson(84, "Same Name"))),
    )

    @Test fun savedStateRejectsDifferentOwnerOrSignInGeneration() {
        val original = LibraryFilters(search = "film", genres = setOf("Drama"), person = person)
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saver = accountLibraryFiltersSaver("owner-a:1")
        val saved = with(saver) { scope.save(original) }!!
        org.junit.Assert.assertEquals(original, saver.restore(saved))
        org.junit.Assert.assertEquals(LibraryFilters(), accountLibraryFiltersSaver("owner-b:1").restore(saved))
        org.junit.Assert.assertEquals(LibraryFilters(), accountLibraryFiltersSaver("owner-a:3").restore(saved))
    }

    @Test fun pickerUsesIdentityAndRetainsFiltersAcrossRestorationThenClearsOnlyPerson() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CinemArchiveTheme {
            val filters = rememberAccountLibraryFilters("owner-a:1")
            LibraryScreen(titles, LibraryViewMode.LIST, {}, 2, {}, {}, onTitleClick = {}, filtersState = filters)
        } }
        compose.onNodeWithContentDescription("Search library").performTextInput("film")
        compose.onNodeWithContentDescription("Filter and sort library").performClick()
        compose.onNodeWithText("Choose person").performClick()
        compose.onNodeWithText("Search people").performTextInput("Same")
        compose.onNodeWithTag("person-choice-42").assertExists()
        compose.onNodeWithTag("person-choice-84").assertExists().performClick()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Second film").assertIsDisplayed()
        compose.onNodeWithText("First film").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Featuring Same Name ×").assertIsDisplayed()
        compose.onNodeWithText("Second film").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear person filter").performClick()
        compose.onNodeWithText("2 titles on the bill").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search library").assertTextContains("film")
    }

    @Test fun detailCreditNavigatesBackToFilteredLibraryAndAccountChangesDiscardLabels() {
        var account by mutableStateOf("owner-a:1")
        var showDetail by mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CinemArchiveTheme {
            val filters = rememberAccountLibraryFilters(account)
            LaunchedEffect(account) { if (account == "owner-a:1") filters.value = filters.value.copy(search = "film") }
            if (showDetail) TitleDetailScreen(
                TitleDetail("first", MediaType.MOVIE, "First film", 2020, null, null, null, null, null, 100,
                    LibraryStatus.WATCHED, null, null, emptyList(), emptyList(), emptyList(),
                    cast = listOf(PersonCredit(42, "Same Name", "Lead"))), {},
                onBrowsePerson = { filters.value = filters.value.copy(person = it); showDetail = false },
            ) else LibraryScreen(titles, LibraryViewMode.LIST, {}, 2, {}, {}, onTitleClick = {}, filtersState = filters)
        } }
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasTestTag("credit-Cast-42"))
        compose.onNodeWithTag("credit-Cast-42").performClick()
        compose.onNodeWithText("Featuring Same Name ×").assertIsDisplayed()
        compose.onNodeWithText("1 title on the bill").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search library").assertTextContains("film")
        compose.runOnIdle { account = "owner-b:2" }
        compose.onNodeWithContentDescription("Clear person filter").assertDoesNotExist()
        compose.onNodeWithText("2 titles on the bill").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Clear person filter").assertDoesNotExist()
        compose.runOnIdle { account = "owner-a:3" }
        compose.onNodeWithContentDescription("Clear person filter").assertDoesNotExist()
        compose.onNodeWithText("2 titles on the bill").assertIsDisplayed()
    }
}
