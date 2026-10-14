package work.kumarfamilynet.cinemarchive

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.data.*
import work.kumarfamilynet.cinemarchive.feature.friends.TitleSocialSource

@RunWith(AndroidJUnit4::class)
class FriendLibraryScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun archive() = SharedLibrary("friend", listOf(SharedLibraryTitle(
        "film", 1, "movie", "Friend Film", 2020, null, "watched", 4.5, listOf("Drama"),
        "2026-01-01T00:00:00Z",
        """{"id":"film","user_id":"friend","tmdb_id":1,"type":"movie","title":"Friend Film",
          "status":"watched","runtime":90,"notes":"Friend film notes","genres":["Drama"],
          "title_cast":[{"id":"c","title_id":"film","user_id":"friend","tmdb_person_id":1,"name":"Shared performer","cast_order":0}],
          "viewings":[{"id":"v","title_id":"film","user_id":"friend","viewed_at":"2026-01-01","notes":"Friend history notes","venue":"Cinema"}]}""",
    )), """[{"id":"cinema","panel":"moviegoing","width":"full","settings":{"title":"Friend cinema history"}}]""")

    @Test fun fullDetailDiscussionAndReadonlyLedgerStayInsideFriendArchive() {
        val social = Social()
        val source = object : FriendLibrarySource {
            override suspend fun loadFriendLibrary(viewerUserId: String, friendUserId: String) = archive()
        }
        var closed = false
        compose.setContent { CinemArchiveTheme {
            FriendLibraryRoute(source, "viewer", "friend", "Friend", social) { closed = true }
        } }
        waitText("Friend Film")
        compose.onNodeWithText("Friend Film").performClick()
        compose.onNodeWithText("Friend film notes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Shared performer").performScrollTo().assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Friend discussion"))
        compose.onNodeWithText("Friend discussion").assertIsDisplayed()
        compose.onNodeWithContentDescription("React with ❤️", substring = true).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("❤️"), social.reacted) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Friend history notes"))
        compose.onNodeWithText("Friend history notes").assertIsDisplayed()
        listOf("Edit", "Log viewing", "Book an outing", "Delete", "Add to list", "Tickets").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(0)
        }
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("The Ledger").performClick()
        compose.onNodeWithText("Friend cinema history").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Edit").assertCountEquals(0)
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test fun failedReadCanRetryToAuthoritativeScopedEmptyArchive() {
        var requests = 0
        val source = object : FriendLibrarySource {
            override suspend fun loadFriendLibrary(viewerUserId: String, friendUserId: String): SharedLibrary {
                requests++
                check(requests > 1) { "secret backend message" }
                return SharedLibrary("friend", emptyList())
            }
        }
        val social = Social()
        compose.setContent { CinemArchiveTheme { FriendLibraryRoute(source, "viewer", "friend", "Friend", social, {}) } }
        waitText("Retry")
        compose.onAllNodesWithText("secret backend message").assertCountEquals(0)
        compose.onNodeWithText("Retry").performClick()
        waitText("Nothing shared with you yet.")
        compose.runOnIdle { assertEquals(2, requests) }
        compose.onAllNodesWithText("Friend Film").assertCountEquals(0)
    }

    @Test fun accountSwitchDiscardsEvenAnUncancellableOldRead() {
        val viewer = mutableStateOf("first")
        val started = CompletableDeferred<Unit>()
        val oldRead = CompletableDeferred<SharedLibrary>()
        val source = object : FriendLibrarySource {
            override suspend fun loadFriendLibrary(viewerUserId: String, friendUserId: String): SharedLibrary {
                if (viewerUserId == "first") return withContext(NonCancellable) {
                    started.complete(Unit)
                    oldRead.await()
                }
                return SharedLibrary("friend", emptyList())
            }
        }
        val firstSocial = Social { viewer.value == "first" }
        val nextSocial = Social { viewer.value == "next" }
        compose.setContent { CinemArchiveTheme {
            FriendLibraryRoute(source, viewer.value, "friend", "Friend",
                if (viewer.value == "first") firstSocial else nextSocial, {})
        } }
        compose.waitUntil(10_000) { started.isCompleted }
        compose.runOnIdle { viewer.value = "next" }
        waitText("Nothing shared with you yet.")
        compose.runOnIdle { oldRead.complete(archive()) }
        compose.waitForIdle()
        compose.onAllNodesWithText("Friend Film").assertCountEquals(0)
        compose.onAllNodesWithText("Friend discussion").assertCountEquals(0)
        compose.onNodeWithText("Nothing shared with you yet.").assertIsDisplayed()
    }

    private fun waitText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private class Social(private val active: () -> Boolean = { true }) : TitleSocialSource {
        val reacted = mutableListOf<String?>()
        override fun isActive() = active()
        override suspend fun comments(titleId: String) =
            listOf(TitleComment("comment", "friend", "Friend", null, "Friend discussion", "2026-01-01T00:00:00Z"))
        override suspend fun reactions(titleId: String) = emptyList<TitleReaction>()
        override suspend fun post(titleId: String, body: String) = error("Unused")
        override suspend fun delete(commentId: String) = error("Friend comments cannot be deleted")
        override suspend fun react(titleId: String, emoji: String?) { reacted += emoji }
        override suspend fun friends() = emptyList<Friendship>()
        override suspend fun sent(tmdbId: Int, type: MediaType) = emptyMap<String, RecommendationStatus>()
        override suspend fun recommend(recipientId: String, draft: RecommendationDraft, note: String, url: String) = Unit
    }
}

