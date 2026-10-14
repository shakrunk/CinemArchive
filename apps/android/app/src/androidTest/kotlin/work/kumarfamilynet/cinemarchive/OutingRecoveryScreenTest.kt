package work.kumarfamilynet.cinemarchive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.data.*
import work.kumarfamilynet.cinemarchive.feature.settings.OutingRecoverySection

@RunWith(AndroidJUnit4::class)
class OutingRecoveryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compareExportAndExplicitReapplyOrDiscardKeepOtherCardsReachable() {
        val source = Source()
        var exported: String? = null
        compose.setContent { CinemArchiveTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { OutingRecoverySection(source) { exported = it } }
        } }
        compose.onAllNodesWithText("Review saved change")[0].performScrollTo().performClick()
        compose.onNodeWithText("Saved: Saved cinema").assertIsDisplayed()
        compose.onNodeWithText("Current: Current cinema").assertIsDisplayed()
        compose.onNodeWithContentDescription("Reapply Theater").assertIsOff()
        compose.onNodeWithText("Apply selected fields").assertIsNotEnabled()
        compose.onNodeWithText("Export original data").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Original one", exported) }
        compose.onNodeWithContentDescription("Reapply Theater").performScrollTo().performClick()
        compose.onNodeWithText("Apply selected fields").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, source.applies) }
        compose.onNodeWithText("Apply selected fields").performClick()
        compose.onNodeWithText("Apply selected and keep other current values").performClick()
        compose.onNodeWithText("Selected fields applied. The original remains available to export.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(setOf("venue"), source.selected) }
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Review saved change").performScrollTo().performClick()
        compose.onNodeWithText("Discard saved change").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(source.discarded.isEmpty()) }
        compose.onNodeWithText("Discard saved change").performClick()
        compose.onNodeWithText("Discard this change").performClick()
        compose.runOnIdle { assertEquals(listOf("two"), source.discarded) }
        compose.onAllNodesWithText("Export original").assertCountEquals(2)
    }

    @Test fun unconfirmedAttemptLocksChoicesAndConfirmsBeforeNewIntent() {
        val source = Source().apply { fail = true }
        compose.setContent { CinemArchiveTheme { OutingRecoverySection(source, {}) } }
        compose.onAllNodesWithText("Review saved change")[0].performClick()
        compose.onNodeWithContentDescription("Reapply Theater").performClick()
        compose.onNodeWithText("Apply selected fields").performClick()
        compose.onNodeWithText("Apply selected and keep other current values").performClick()
        compose.onNodeWithText("Connection lost").assertIsDisplayed()
        compose.onNodeWithContentDescription("Reapply Theater").assertIsNotEnabled()
        compose.onNodeWithText("Discard saved change").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { source.fail = false }
        compose.onNodeWithText("Confirm previous attempt").performClick()
        compose.runOnIdle { assertEquals(2, source.applies) }
        compose.onNodeWithText("Selected fields applied. The original remains available to export.").assertIsDisplayed()
    }

    @Test fun unreadableCardOffersRawExportWhileValidReviewRemainsReachable() {
        val source = Source().apply { unreadable = true }
        var exported: String? = null
        compose.setContent { CinemArchiveTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { OutingRecoverySection(source) { exported = it } }
        } }
        compose.onNodeWithText("Unreadable saved outing change").assertIsDisplayed()
        compose.onNodeWithText("Export preserved raw data").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("{broken", exported) }
        compose.onAllNodesWithText("Review saved change").assertCountEquals(2)
        compose.onAllNodesWithText("Review saved change")[0].performScrollTo().performClick()
        compose.onNodeWithText("Current: Current cinema").assertIsDisplayed()
    }

    private class Source : OutingRecoverySource {
        override val changes = flowOf(Unit)
        var fail = false
        var pending = false
        var unreadable = false
        var applies = 0
        var selected = emptySet<String>()
        val discarded = mutableListOf<String>()
        private val resolved = mutableSetOf<String>()
        override fun isActive() = true
        override suspend fun items() = (if (unreadable) listOf(OutingRecoveryCard("broken", "Unreadable saved outing change", false,
            "Preserved raw recovery data. Export it for recovery; this entry cannot be applied or discarded.")) else emptyList()) +
            listOf("one", "two").map { OutingRecoveryCard(it, "Film $it", it in resolved) }
        override suspend fun pendingAttempt(id: String) = pending
        override suspend fun review(id: String) = OutingRecoveryReview(id, "Film $id", listOf(
            OutingRecoveryField("venue", "Theater", "Saved cinema", "Current cinema", true),
            OutingRecoveryField("notes", "Notes", "Saved note", "Current note", true),
        ), "version", true, pending, id in resolved)
        override suspend fun apply(id: String, expectedVersion: String?, selected: Set<String>): OutingRecoveryOutcome {
            applies++; this.selected = selected
            pending = fail
            check(!fail) { "Connection lost" }
            resolved += id
            return OutingRecoveryOutcome.APPLIED
        }
        override suspend fun discard(id: String) { discarded += id; resolved += id }
        override suspend fun exportOriginal(id: String) = if (id == "broken") "{broken" else "Original $id"
    }
}
