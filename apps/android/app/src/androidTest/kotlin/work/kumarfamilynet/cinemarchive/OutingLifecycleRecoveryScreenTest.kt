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
class OutingLifecycleRecoveryScreenTest {
    @get:Rule val compose = createComposeRule()
    private var exported: String? = null
    private fun show(source: Source) {
        compose.setContent { CinemArchiveTheme {
            Column(Modifier.width(360.dp).statusBarsPadding().verticalScroll(rememberScrollState())) {
                OutingRecoverySection(source, RecoverySubject.LIFECYCLE) { exported = it }
            }
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Review saved change").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Review saved change").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Reapply ${source.action}").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun completionRequiresWholeActionSelectionAndExplicitConfirmation() {
        val source = Source()
        show(source)
        compose.onNode(hasText("Complete outing") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Reapply Theater").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Reapply Complete outing").performScrollTo().performClick()
        compose.onNode(hasText("Complete outing") and hasClickAction()).performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, source.calls) }
        compose.onNode(hasText("Complete outing") and hasClickAction()).performClick()
        compose.onNodeWithText("Confirm outing action").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Saved outing action applied. The original remains available to export.").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(setOf("lifecycleAction"), source.selected); assertEquals("captured-version", source.version) }
    }

    @Test fun unconfirmedReversalLocksActionsButKeepsOriginalExportAndExactRetry() {
        val source = Source(action = "Undo completion", pending = true)
        show(source)
        compose.onNodeWithContentDescription("Reapply Undo completion").assertIsNotEnabled()
        compose.onNodeWithText("Discard saved change").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Export original data").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("immutable original", exported) }
        compose.onNodeWithText("Confirm previous attempt").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Original outing completion change confirmed. The current outing has been refreshed and the original remains available to export.").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(1, source.calls); assertTrue(source.selected.isEmpty()); assertEquals(0, source.discards) }
    }

    @Test fun discardExplainsPreservedDependentEditsAndRequiresConfirmation() {
        val source = Source(action = "Undo completion")
        show(source)
        compose.onNodeWithText("Discard saved change").performScrollTo().performClick()
        compose.onNodeWithText("Dependent saved edits are preserved", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, source.discards) }
        compose.onNodeWithText("Discard saved change").performScrollTo().performClick()
        compose.onNodeWithText("Discard this change").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Saved change discarded. Its original remains available to export.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Export original").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, source.discards); assertEquals("immutable original", exported) }
    }

    private class Source(val action: String = "Complete outing", var pending: Boolean = false) : OutingRecoverySource {
        override val changes = flowOf(Unit)
        var resolved = false
        var calls = 0
        var discards = 0
        var selected = emptySet<String>()
        var version: String? = null
        override fun isActive() = true
        override suspend fun items() = listOf(OutingRecoveryCard("one", "Film", resolved))
        override suspend fun review(id: String) = OutingRecoveryReview(id, "Film", listOf(
            OutingRecoveryField("lifecycleAction", action, action, "Current outing", true),
            OutingRecoveryField("venue", "Theater", "Saved theater", "Current theater", true),
        ), "captured-version", true, pending, resolved)
        override suspend fun pendingAttempt(id: String) = pending
        override suspend fun apply(id: String, expectedVersion: String?, selected: Set<String>): OutingRecoveryOutcome {
            calls++; this.selected = selected; version = expectedVersion; resolved = true
            return if (pending) OutingRecoveryOutcome.CONFIRMED else OutingRecoveryOutcome.APPLIED
        }
        override suspend fun discard(id: String) { discards++; resolved = true }
        override suspend fun exportOriginal(id: String) = "immutable original"
    }
}
