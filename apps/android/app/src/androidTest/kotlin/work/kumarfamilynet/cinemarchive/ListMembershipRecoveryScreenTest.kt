package work.kumarfamilynet.cinemarchive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.data.*
import work.kumarfamilynet.cinemarchive.feature.settings.OutingRecoverySection
import work.kumarfamilynet.cinemarchive.feature.settings.RecoverySubject

@RunWith(AndroidJUnit4::class)
class ListMembershipRecoveryScreenTest {
    @get:Rule val compose = createComposeRule()
    private var exported: String? = null
    private fun show(source: Source) {
        compose.setContent { CinemArchiveTheme {
            Column(Modifier.width(360.dp).statusBarsPadding().verticalScroll(rememberScrollState())) {
                OutingRecoverySection(source, RecoverySubject.LIST) { exported = it }
            }
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Review saved change").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Review saved change").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Reapply List membership").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun membershipRequiresSelectionAndConfirmationAndReportsQueuedNotDelivered() {
        val source = Source(); show(source)
        compose.onNodeWithText("Queue saved membership").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Reapply List membership").performScrollTo().performClick()
        compose.onNodeWithText("Queue saved membership").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, source.calls) }
        compose.onNodeWithText("Queue saved membership").performClick()
        compose.onNodeWithText("Queue membership change").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Saved membership queued for sync. The original remains available to export.").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(setOf("membership"), source.selected); assertEquals("captured", source.version) }
    }
    @Test fun discardExplainsDeliveredChangesAndPreservesExport() {
        val source = Source(); show(source)
        compose.onNodeWithText("Discard saved change").performScrollTo().performClick()
        compose.onNodeWithText("Changes already delivered are not undone", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Discard this change").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Saved change discarded. Its original remains available to export.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Export original").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("immutable original", exported) }
    }
    private class Source : OutingRecoverySource {
        override val changes = flowOf(Unit)
        var resolved = false
        var calls = 0
        var selected = emptySet<String>()
        var version: String? = null
        override fun isActive() = true
        override suspend fun items() = listOf(OutingRecoveryCard("one", "Film in Films", resolved))
        override suspend fun review(id: String) = OutingRecoveryReview(id, "Film in Films", listOf(
            OutingRecoveryField("membership", "List membership", "Add to list", "Not in list", true)),
            "captured", true, false, resolved)
        override suspend fun pendingAttempt(id: String) = false
        override suspend fun apply(id: String, expectedVersion: String?, selected: Set<String>): OutingRecoveryOutcome {
            calls++; this.selected = selected; version = expectedVersion; resolved = true
            return OutingRecoveryOutcome.APPLIED
        }
        override suspend fun discard(id: String) { resolved = true }
        override suspend fun exportOriginal(id: String) = "immutable original"
    }
}
