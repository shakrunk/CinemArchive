package work.kumarfamilynet.cinemarchive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.data.*
import work.kumarfamilynet.cinemarchive.feature.friends.FriendRow
import work.kumarfamilynet.cinemarchive.feature.settings.ShareScopeEditorDialog
import work.kumarfamilynet.cinemarchive.feature.settings.ShareScopeEditorSource

@RunWith(AndroidJUnit4::class)
class FriendScopeEditorScreenTest {
    @get:Rule val compose = createComposeRule()
    private val target = ShareScopeTarget.Friend("friend")

    @Test fun acceptedFriendCanEditReopenAndClearAccessOnNarrowLargeTextScreen() {
        val source = Source(ShareScope(listOf("Drama"), listOf("watched")))
        val relation = mutableStateOf(FriendshipRelation.FRIENDS)
        compose.setContent { CinemArchiveTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f)) {
                var editing by remember { mutableStateOf(false) }
                Column(Modifier.width(360.dp)) {
                    FriendRow(Friendship("friend", FriendshipStatus.ACCEPTED, "viewer", null, "", "", "A friend", null),
                        relation.value, {}, {}, {}, {}, {}, {}, { editing = true })
                    if (editing) ShareScopeEditorDialog(source, target, "A friend", listOf("Comedy", "Drama"), { editing = false })
                }
            }
        } }
        compose.onNodeWithText("Edit access").performClick()
        waitText("Custom")
        compose.onNodeWithText("Drama").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Comedy").performScrollTo().performClick()
        compose.onNodeWithText("Watching").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        waitText("Edit access")
        compose.runOnIdle {
            assertEquals(ShareScope(listOf("Drama", "Comedy"), listOf("watched", "watching")), source.value)
            assertEquals(target, source.saved.single().first)
        }
        compose.onNodeWithText("Edit access").performClick()
        waitText("Custom")
        compose.onNodeWithText("Full library").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, source.saved.size) }
        compose.onNodeWithText("Edit access").performClick()
        waitText("Custom")
        compose.onNodeWithText("Full library").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil { source.saved.size == 2 }
        compose.runOnIdle { assertNull(source.value) }
        compose.runOnIdle { relation.value = FriendshipRelation.REQUEST_SENT }
        compose.onAllNodesWithText("Edit access").assertCountEquals(0)
        compose.runOnIdle { relation.value = FriendshipRelation.BLOCKED_BY_ME }
        compose.onAllNodesWithText("Edit access").assertCountEquals(0)
    }

    @Test fun failuresKeepEditorAndDraftForVisibleRetry() {
        val source = Source().apply { failLoad = true; failSave = true }
        var closed = false
        compose.setContent { CinemArchiveTheme {
            ShareScopeEditorDialog(source, target, "A friend", listOf("Drama"), { closed = true })
        } }
        waitText("Retry load")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.runOnIdle { source.failLoad = false }
        compose.onNodeWithText("Retry load").performScrollTo().performClick()
        waitText("Custom")
        compose.onNodeWithText("Custom").performScrollTo().performClick()
        compose.onNodeWithText("Drama").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        waitText("Access could not be saved. Your selections are kept; retry when connected.")
        compose.onNodeWithText("Drama").performScrollTo().assertIsSelected()
        compose.runOnIdle { assertFalse(closed); source.failSave = false }
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil { closed }
        compose.runOnIdle { assertEquals(source.saved[0], source.saved[1]) }
    }

    @Test fun switchingOwnerDiscardsUncancellableOldScopeAndDraft() {
        val pending = CompletableDeferred<ShareScope?>()
        val old = Source().apply { loadWait = pending }
        val next = Source()
        val source = mutableStateOf<ShareScopeEditorSource>(old)
        compose.setContent { CinemArchiveTheme {
            ShareScopeEditorDialog(source.value, target, "A friend", listOf("Drama"), {})
        } }
        compose.waitUntil { old.loads == 1 }
        compose.runOnIdle { old.active = false; source.value = next }
        waitText("Full library")
        compose.runOnIdle { pending.complete(ShareScope(listOf("Private old genre"), listOf("watched"))) }
        compose.waitForIdle()
        compose.onNodeWithText("Full library").assertIsSelected()
        compose.onNodeWithText("Custom").performClick()
        compose.onAllNodesWithText("Private old genre").assertCountEquals(0)
        compose.runOnIdle { assertTrue(old.saved.isEmpty()) }
    }

    @Test fun linkEditorEmptySelectionsSaveUnrestrictedRatherThanDenyAll() {
        val source = Source(ShareScope(listOf("Drama"), listOf("watched")))
        val link = ShareScopeTarget.Link("link")
        var closed = false
        compose.setContent { CinemArchiveTheme {
            ShareScopeEditorDialog(source, link, "A link", listOf("Drama"), { closed = true })
        } }
        waitText("Custom")
        compose.onNodeWithText("Drama").performScrollTo().performClick()
        compose.onNodeWithText("Watched").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil { closed }
        compose.runOnIdle { assertEquals(link to ShareScope(null, null), source.saved.single()) }
    }

    @Test fun pendingSaveCannotBeDismissedAsThoughTheServerWriteWasCancelled() {
        val done = CompletableDeferred<Unit>()
        val source = Source().apply { saveWait = done }
        var closed = false
        compose.setContent { CinemArchiveTheme {
            ShareScopeEditorDialog(source, target, "A friend", emptyList(), { closed = true })
        } }
        waitText("Full library")
        compose.onNodeWithText("Save").performClick()
        waitText("Saving…")
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        val back = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(back).use { it.readBytes() }
        compose.onNodeWithText("Saving…").assertIsDisplayed()
        compose.runOnIdle { assertFalse(closed); done.complete(Unit) }
        compose.waitUntil { closed }
    }

    private fun waitText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private class Source(var value: ShareScope? = null) : ShareScopeEditorSource {
        var active = true
        var loads = 0
        var failLoad = false
        var failSave = false
        var loadWait: CompletableDeferred<ShareScope?>? = null
        var saveWait: CompletableDeferred<Unit>? = null
        val saved = mutableListOf<Pair<ShareScopeTarget, ShareScope?>>()
        override fun isActive() = active
        override suspend fun load(target: ShareScopeTarget): ShareScope? {
            loads++
            check(!failLoad) { "Private server detail" }
            return loadWait?.let { withContext(NonCancellable) { it.await() } } ?: value
        }
        override suspend fun save(target: ShareScopeTarget, value: ShareScope?) {
            saved += target to value
            saveWait?.await()
            check(!failSave) { "Private server detail" }
            this.value = value
        }
    }
}
