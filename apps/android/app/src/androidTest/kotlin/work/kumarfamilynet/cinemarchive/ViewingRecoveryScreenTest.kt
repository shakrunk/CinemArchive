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
import work.kumarfamilynet.cinemarchive.feature.settings.RecoverySubject

@RunWith(AndroidJUnit4::class)
class ViewingRecoveryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun viewingComparisonExportsOriginalAndAppliesOnlyExplicitSelection() {
        val source = Source()
        var exported: String? = null
        compose.setContent { CinemArchiveTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutingRecoverySection(source, RecoverySubject.VIEWING) { exported = it }
            }
        } }
        compose.onNodeWithText("Saved viewing changes").assertIsDisplayed()
        compose.onNodeWithText("Saved outing changes").assertDoesNotExist()
        compose.onAllNodesWithText("Review saved change")[0].performScrollTo().performClick()
        compose.onNodeWithText("Current: Newer remote note").assertIsDisplayed()
        compose.onNodeWithContentDescription("Reapply Notes").assertIsOff()
        compose.onNodeWithText("Apply selected fields").assertIsNotEnabled()
        compose.onNodeWithText("Export original data").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Retained viewing original", exported) }
        compose.onNodeWithContentDescription("Reapply Notes").performScrollTo().performClick()
        compose.onNodeWithText("Apply selected fields").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(source.selected.isEmpty()) }
        compose.onNodeWithText("Apply selected fields").performClick()
        compose.onNodeWithText("Apply selected and keep other current values").performClick()
        compose.runOnIdle { assertEquals(setOf("notes"), source.selected) }
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Review saved change").performScrollTo().assertIsDisplayed()
    }

    @Test fun deletionIsUnselectedAndRequiresExactEventConfirmation() {
        val source = Source(deleting = true)
        compose.setContent { CinemArchiveTheme { OutingRecoverySection(source, RecoverySubject.VIEWING, {}) } }
        compose.onAllNodesWithText("Review saved change")[0].performClick()
        compose.onNodeWithText("Current: 2026-10-01").assertIsDisplayed()
        compose.onNodeWithContentDescription("Reapply Viewing date").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Reapply Delete this viewing").performScrollTo().assertIsOff()
        compose.onNodeWithText("Apply selected fields").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Reapply Delete this viewing").performClick()
        compose.onAllNodesWithText("Delete this viewing").filterToOne(hasClickAction()).performClick()
        compose.onNodeWithText("Delete this exact viewing?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(source.selected.isEmpty()) }
        compose.onAllNodesWithText("Delete this viewing").filterToOne(hasClickAction()).performClick()
        compose.onNodeWithText("Delete only this viewing").performClick()
        compose.runOnIdle { assertEquals(setOf("delete"), source.selected) }
        compose.onNodeWithText("Selected viewing deleted. Other history is preserved and the original remains available to export.").assertIsDisplayed()
    }

    private class Source(private val deleting: Boolean = false) : OutingRecoverySource {
        override val changes = flowOf(Unit)
        var selected = emptySet<String>()
        private val resolved = mutableSetOf<String>()
        override fun isActive() = true
        override suspend fun items() = listOf("one", "two").map { OutingRecoveryCard(it, "Viewing " + it, it in resolved) }
        override suspend fun pendingAttempt(id: String) = false
        override suspend fun review(id: String) = OutingRecoveryReview(id, "Viewing " + id, if (deleting) listOf(
            OutingRecoveryField("date", "Viewing date", "2026-10-01", "2026-10-01", false),
            OutingRecoveryField("delete", "Delete this viewing", "Remove only this event", "Current event", true),
        ) else listOf(
            OutingRecoveryField("notes", "Notes", "Saved note", "Newer remote note", true),
            OutingRecoveryField("rating", "Rating", "2", "4", true),
        ), "2026-10-08T10:00:00Z", true, false, id in resolved)
        override suspend fun apply(id: String, expectedVersion: String?, selected: Set<String>): OutingRecoveryOutcome {
            this.selected = selected; resolved += id
            return OutingRecoveryOutcome.APPLIED
        }
        override suspend fun discard(id: String) { resolved += id }
        override suspend fun exportOriginal(id: String) = "Retained viewing original"
    }
}
