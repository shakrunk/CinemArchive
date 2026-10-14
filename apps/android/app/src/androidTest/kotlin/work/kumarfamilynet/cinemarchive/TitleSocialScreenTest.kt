package work.kumarfamilynet.cinemarchive

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.data.*
import work.kumarfamilynet.cinemarchive.feature.friends.*
import work.kumarfamilynet.cinemarchive.feature.library.TitleDetailScreen

@RunWith(AndroidJUnit4::class)
class TitleSocialScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ownerDetailDisplaysDiscussionAndAccountExitRemovesIt() {
        val source = FakeSocialSource()
        val viewer = mutableStateOf("owner")
        val detail = TitleDetail(
            id = "title", type = MediaType.MOVIE, title = "Social Film", year = 2026,
            posterUrl = null, backdropUrl = null, synopsis = null, director = null, network = null,
            runtime = 90, status = LibraryStatus.WATCHED, rating = null, notes = null,
            genres = emptyList(), seasons = emptyList(), viewings = emptyList(), tmdbId = 42,
        )
        compose.setContent { CinemArchiveTheme {
            TitleDetailScreen(detail, {}, socialContent = { OwnerTitleSocial(source, viewer.value, it) })
        } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Recommend to a friend"))
        compose.onNodeWithText("Recommend to a friend").performClick()
        compose.onNodeWithText("Recommend \"Social Film\"").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Private owner comment"))
        compose.onNodeWithText("Private owner comment").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete your comment: Private owner comment").performClick()
        compose.runOnIdle { assertEquals(listOf("own"), source.deleted) }
        compose.onAllNodesWithText("Private owner comment").assertCountEquals(0)
        compose.runOnIdle { source.active = false; viewer.value = "" }
        compose.onAllNodesWithText("Recommend to a friend").assertCountEquals(0)
        compose.onAllNodesWithText("Friend comment").assertCountEquals(0)
    }

    @Test fun recommendationSearchFindsLastFriendAndRetryIsPerRecipient() {
        val source = FakeSocialSource()
        compose.setContent { CinemArchiveTheme {
            RecommendDialog(source, "owner", RecommendationDraft(42, MediaType.MOVIE, "Film", 2026, null), {})
        } }
        compose.onNodeWithText("Search friends").performTextInput("Friend 30")
        compose.onNodeWithContentDescription("Send to Friend 30").performScrollTo().performClick()
        compose.onNodeWithText("Try again").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithContentDescription("Send again to Friend 30").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("30", "30"), source.sentRecipients) }
    }

    private class FakeSocialSource : TitleSocialSource {
        var active = true
        val deleted = mutableListOf<String>()
        val sentRecipients = mutableListOf<String>()
        override fun isActive() = active
        override suspend fun comments(titleId: String) = listOf(
            TitleComment("own", "owner", "Owner", null, "Private owner comment", "2026-10-08T12:00:00Z"),
            TitleComment("other", "friend", "Friend", null, "Friend comment", "2026-10-08T12:01:00Z"),
        )
        override suspend fun reactions(titleId: String) = listOf(TitleReaction("friend", "Friend", null, "👍"))
        override suspend fun post(titleId: String, body: String) = error("Unused")
        override suspend fun delete(commentId: String) { deleted += commentId }
        override suspend fun react(titleId: String, emoji: String?) = Unit
        override suspend fun friends() = (1..30).map {
            Friendship("$it", FriendshipStatus.ACCEPTED, "owner", null, "", "", "Friend $it", null)
        }
        override suspend fun sent(tmdbId: Int, type: MediaType) = emptyMap<String, RecommendationStatus>()
        override suspend fun recommend(recipientId: String, draft: RecommendationDraft, note: String, url: String) {
            sentRecipients += recipientId
            check(sentRecipients.size > 1) { "Try again" }
        }
    }
}
