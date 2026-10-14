package work.kumarfamilynet.cinemarchive

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.data.CatalogRefreshProgress
import work.kumarfamilynet.cinemarchive.data.CatalogRefreshReport
import work.kumarfamilynet.cinemarchive.feature.settings.CatalogRefreshSection

@RunWith(AndroidJUnit4::class)
class CatalogRefreshScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun progressDisablesDuplicateRunAndReportsSavedPartialResults() {
        val result = CompletableDeferred<CatalogRefreshReport>(); var calls = 0
        compose.setContent { CinemArchiveTheme { CatalogRefreshSection { progress ->
            calls++; progress(CatalogRefreshProgress(1, 2, "Second title")); result.await()
        } } }
        compose.onNodeWithText("Refresh all metadata").performClick()
        compose.onNodeWithText("Refreshing library…").assertIsNotEnabled()
        compose.onNodeWithText("1/2 · Second title").assertExists()
        compose.runOnIdle { assertEquals(1, calls); result.complete(CatalogRefreshReport(1, 0, listOf("Second title: offline"))) }
        compose.waitForIdle()
        compose.onNodeWithText("1 refreshed · 0 unchanged · 1 failed. Changes are saved for sync.").assertExists()
        compose.onNodeWithText("Second title: offline").assertExists()
        compose.onNodeWithText("Refresh all metadata").assertIsEnabled()
    }

    @Test fun accountReplacementCancelsOldRefreshAndSuppressesItsReport() {
        val owner = mutableStateOf("first"); val old = CompletableDeferred<CatalogRefreshReport>()
        compose.setContent { CinemArchiveTheme { key(owner.value) { CatalogRefreshSection { old.await() } } } }
        compose.onNodeWithText("Refresh all metadata").performClick()
        compose.runOnIdle { owner.value = "second" }
        compose.waitForIdle()
        compose.runOnIdle { old.complete(CatalogRefreshReport(99, 0, emptyList())) }
        compose.onNodeWithText("99 refreshed · 0 unchanged · 0 failed. Changes are saved for sync.").assertDoesNotExist()
        compose.onNodeWithText("Refresh all metadata").assertIsEnabled()
    }
}
