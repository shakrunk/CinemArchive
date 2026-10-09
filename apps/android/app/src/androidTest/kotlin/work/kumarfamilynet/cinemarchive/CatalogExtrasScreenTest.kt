package work.kumarfamilynet.cinemarchive

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import work.kumarfamilynet.cinemarchive.data.CatalogExtrasSource
import work.kumarfamilynet.cinemarchive.feature.library.*

@RunWith(AndroidJUnit4::class)
class CatalogExtrasScreenTest {
    @get:Rule val compose = createComposeRule()
    private val key = CatalogExtrasKey(42, MediaType.MOVIE, "GB")
    private fun visible(text: String) = compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    @Test fun failedTrailersRetryWhileProvidersRemainVisibleAndLinksOpenSafeTargets() {
        var attempts = 0
        val opened = mutableListOf<String>()
        val source = object : CatalogExtrasSource {
            override suspend fun videos(key: CatalogExtrasKey): List<CatalogVideo> {
                if (++attempts == 1) error("Network unavailable")
                return listOf(CatalogVideo("abcdefghijk", "A long official trailer title that wraps on a narrow screen", "Trailer", true))
            }
            override suspend fun providers(key: CatalogExtrasKey) = CatalogProviders(
                "https://www.themoviedb.org/movie/42/watch?locale=GB",
                listOf(CatalogProvider(1, "Streaming service", null), CatalogProvider(2, "Free service", null)),
                listOf(CatalogProvider(3, "Rental store", null)), listOf(CatalogProvider(4, "Purchase store", null)),
            )
        }
        compose.setContent { CinemArchiveTheme {
            val scope = rememberCoroutineScope()
            val controller = remember { CatalogExtrasController(source, scope) }
            val state by controller.state.collectAsState()
            LaunchedEffect(Unit) { controller.select(key) }
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f)) {
                Column(Modifier.width(280.dp).verticalScroll(rememberScrollState()).testTag("extras-root")) {
                    CatalogExtrasSection(state, controller::retryVideos, controller::retryProviders, opened::add)
                }
            }
        } }
        compose.waitUntil { compose.onAllNodesWithText("Retry trailers").fetchSemanticsNodes().isNotEmpty() }
        visible("Stream"); visible("Free service"); visible("Rent"); visible("Purchase store")
        compose.onNodeWithText("Retry trailers").performScrollTo().performClick()
        val trailer = "A long official trailer title that wraps on a narrow screen"
        compose.waitUntil { compose.onAllNodesWithText(trailer).fetchSemanticsNodes().isNotEmpty() }
        visible(trailer)
        compose.onNodeWithText(trailer).performClick()
        compose.onNodeWithText("Streaming data provided by JustWatch").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("https://www.youtube.com/watch?v=abcdefghijk", "https://www.themoviedb.org/movie/42/watch?locale=GB"), opened)
        }
        val card = compose.onNodeWithTag("catalog-extras").fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("extras-root").fetchSemanticsNode().boundsInRoot
        assertTrue(card.left >= root.left && card.right <= root.right)
    }

    @Test fun emptyCatalogIsDistinctFromFailureAndMissingIdentityHidesOldState() {
        lateinit var controller: CatalogExtrasController
        val source = object : CatalogExtrasSource {
            override suspend fun videos(key: CatalogExtrasKey) = emptyList<CatalogVideo>()
            override suspend fun providers(key: CatalogExtrasKey): CatalogProviders? = null
        }
        compose.setContent { CinemArchiveTheme {
            val scope = rememberCoroutineScope()
            controller = remember { CatalogExtrasController(source, scope) }
            val state by controller.state.collectAsState()
            LaunchedEffect(Unit) { controller.select(key) }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CatalogExtrasSection(state, controller::retryVideos, controller::retryProviders)
            }
        } }
        compose.waitUntil { compose.onAllNodesWithText("No trailers available.").fetchSemanticsNodes().isNotEmpty() }
        visible("No trailers available."); visible("No streaming providers available.")
        compose.onAllNodesWithText("Retry trailers").assertCountEquals(0)
        compose.runOnIdle { controller.select(null) }
        compose.onAllNodesWithTag("catalog-extras").assertCountEquals(0)
        compose.onAllNodesWithText("No trailers available.").assertCountEquals(0)
    }
}
