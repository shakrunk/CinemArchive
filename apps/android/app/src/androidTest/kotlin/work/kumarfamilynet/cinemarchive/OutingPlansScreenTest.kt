package work.kumarfamilynet.cinemarchive

import android.provider.CalendarContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.data.*
import work.kumarfamilynet.cinemarchive.feature.friends.*
import work.kumarfamilynet.cinemarchive.feature.library.TitleDetailScreen

@RunWith(AndroidJUnit4::class)
class OutingPlansScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingPlansBlockSharingThenCurrentPlanExportsAndRecipientRetryWorks() {
        var pending = true
        var calls = 0
        var exported: PublicOutingPlan? = null
        var calendarTitle: String? = null
        val plan = PublicOutingPlan("outing", "Film", "2099-10-08T19:00:00Z", "2099-10-08T21:00:00Z", "Current cinema", CinemaFormat.IMAX, "F12", listOf("Friend"))
        val source = OutingPlansRepository("owner", { SupabaseSession("fake", "owner") },
            { OutingPlanSnapshot(plan, true, pending) },
            { listOf(Friendship("friend", FriendshipStatus.ACCEPTED, "owner", null, "", "", "Friend", null)) },
            { _, outing, recipient, _ ->
                assertEquals("outing", outing); assertEquals("friend", recipient)
                calls++; check(calls > 1) { "Offline, retry" }; plan
            })
        compose.setContent { CinemArchiveTheme {
            OutingPlansDialog(source, "outing", {}, onShare = { exported = it }, onCalendar = {
                val intent = outingCalendarIntent(it)
                calendarTitle = intent.getStringExtra(CalendarContract.Events.TITLE)
                assertEquals("With Friend\nF12", intent.getStringExtra(CalendarContract.Events.DESCRIPTION))
                assertEquals("Current cinema", intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION))
            })
        } }
        compose.onNodeWithText("These plans are still syncing. Connect to the internet, refresh your library, then retry sharing.").assertIsDisplayed()
        compose.onAllNodesWithText("Share text").assertCountEquals(0)
        compose.runOnIdle { pending = false }
        compose.onNodeWithText("Retry loading plans").performClick()
        compose.onNodeWithText("Share text").performScrollTo().performClick()
        compose.onNodeWithText("Add to calendar").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(plan, exported); assertEquals("Film", calendarTitle) }
        compose.onNodeWithContentDescription("Share plans with Friend").performScrollTo().performClick()
        compose.onNodeWithText("Offline, retry").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Share plans with Friend").performClick()
        compose.onNodeWithText("Plans shared").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, calls) }
    }

    @Test fun titlePostShowRecommendationKeepsDraftUntilExplicitSaveAndTargetsExactTitle() {
        var recommended: String? = null
        var savedNotes: String? = null
        val detail = TitleDetail(
            "title", MediaType.MOVIE, "Film", 2026, null, null, null, null, null, 90,
            LibraryStatus.WATCHED, null, null, emptyList(), emptyList(),
            listOf(Viewing("viewing", "2026-10-08", null, null, "Cinema", outingId = "outing")), tmdbId = 42,
        )
        compose.setContent { CinemArchiveTheme {
            TitleDetailScreen(detail, {}, onRecommendTitle = { recommended = it },
                onPreparePostShow = { PostShowOpening(ViewingDraft(it, "2026-10-08", null, null, "Cinema", openingContext = "captured")) },
                onSavePostShow = { opening, _, notes ->
                assertEquals("viewing", opening.viewing.id); assertEquals("captured", opening.viewing.openingContext); savedNotes = notes
            })
        } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("How was it?"))
        compose.onNodeWithText("How was it?").performClick()
        compose.onNodeWithText("Quick note").performTextInput("Great with friends")
        compose.onNodeWithText("Recommend to a friend").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("title", recommended); assertNull(savedNotes) }
        compose.onNodeWithText("Film just let out").assertExists()
        compose.onNodeWithText("Save", substring = false).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Great with friends", savedNotes) }
        compose.onAllNodesWithText("Film just let out").assertCountEquals(0)
    }
}
