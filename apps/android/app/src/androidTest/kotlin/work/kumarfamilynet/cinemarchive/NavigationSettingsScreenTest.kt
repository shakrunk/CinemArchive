package work.kumarfamilynet.cinemarchive

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.designsystem.MorphingBottomNav
import work.kumarfamilynet.cinemarchive.core.designsystem.MorphingNavigationRail
import work.kumarfamilynet.cinemarchive.core.designsystem.NavDestination
import work.kumarfamilynet.cinemarchive.core.model.NavigationDestination
import work.kumarfamilynet.cinemarchive.core.model.NavigationPreferences
import work.kumarfamilynet.cinemarchive.data.PreferencesRepository
import work.kumarfamilynet.cinemarchive.feature.settings.NavigationSettingsRoute

class NavigationSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var repository: PreferencesRepository
    @Before fun reset() = runBlocking {
        repository = PreferencesRepository(ApplicationProvider.getApplicationContext())
        repository.resetNavigation()
    }
    private fun prefs() = runBlocking { repository.observeNavigation().first() }
    private fun waitFor(predicate: (NavigationPreferences) -> Boolean) = compose.waitUntil(5_000) { predicate(prefs()) }

    @Test fun phoneReordersHidesAndCompactsWithoutNavigatingAwayFromHiddenActiveTab() {
        compose.setContent {
            MaterialTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    val prefs by repository.observeNavigation().collectAsState(initial = NavigationPreferences())
                    Column(Modifier.width(360.dp).fillMaxHeight().statusBarsPadding()) {
                        Text("Current page: Library")
                        Box(Modifier.weight(1f)) { NavigationSettingsRoute(repository, {}) }
                        MorphingBottomNav(prefs.visible.map { NavDestination(it, it.label, Icons.Filled.Movie) },
                            NavigationDestination.LIBRARY, {}, compact = prefs.compact)
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Move The Library up").performScrollTo().performClick()
        waitFor { it.order.first() == NavigationDestination.LIBRARY }
        compose.onNodeWithContentDescription("Show The Library in navigation").performScrollTo().performClick()
        waitFor { NavigationDestination.LIBRARY in it.hidden }
        compose.onNodeWithText("Current page: Library").assertIsDisplayed()
        compose.onNodeWithContentDescription("The Library", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Compact navigation").performScrollTo().performClick()
        waitFor { it.compact }
        compose.onNodeWithContentDescription("Discover", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Reset navigation").performScrollTo().performClick()
        waitFor { it == NavigationPreferences() }
        compose.onNodeWithContentDescription("The Library", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun lastVisibleTabRemainsAccessibleAndCompactRailKeepsNamedTargets() {
        runBlocking { NavigationDestination.entries.filter { it != NavigationDestination.LISTS }.forEach { repository.showNavigation(it, false) }; repository.setNavigationCompact(true) }
        compose.setContent {
            MaterialTheme {
                val prefs by repository.observeNavigation().collectAsState(initial = NavigationPreferences())
                Row(Modifier.fillMaxSize().statusBarsPadding()) {
                    MorphingNavigationRail(prefs.visible.map { NavDestination(it, it.label, Icons.Filled.Movie) }, NavigationDestination.LISTS, {}, compact = prefs.compact)
                    Box(Modifier.weight(1f)) { NavigationSettingsRoute(repository, {}, showBack = false) }
                }
            }
        }
        compose.onNodeWithContentDescription("Show Lists in navigation").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Lists", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Show Discover in navigation").performScrollTo().performClick()
        waitFor { NavigationDestination.DISCOVER in it.visible }
        compose.onNodeWithContentDescription("Show Lists in navigation").performScrollTo().assertIsEnabled()
    }
}
