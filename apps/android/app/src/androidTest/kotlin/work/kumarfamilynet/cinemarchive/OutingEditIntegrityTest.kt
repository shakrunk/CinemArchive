package work.kumarfamilynet.cinemarchive

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.feature.library.OutingScheduleSheet

@RunWith(AndroidJUnit4::class)
class OutingEditIntegrityTest {
    @get:Rule val compose = createComposeRule()
    private val names = listOf("Smith, Alex", " Sam ")
    private fun outing() = CinemaOuting(
        id = "outing", titleId = "title", showtime = "2099-10-08T19:00:00Z", previewsMinutes = 20,
        runtimeMinutes = 90, endsAt = "2099-10-08T20:50:00Z", venue = "Cinema", companions = names,
        format = null, ticketPrice = null, seat = null, auditorium = null, seatRow = null, seats = emptyList(),
        bookingRef = null, ticketImagePath = null, ticketBarcodePayload = null, ticketBarcodeFormat = null,
        notes = null, status = OutingStatus.SCHEDULED, previousStatus = null, completedViewingId = null,
        followUpDismissedAt = null, createdAt = "2026-10-08T12:00:00Z",
    )

    @Test fun unrelatedEditKeepsOriginalCompanionNamesAndAbsentFormat() {
        var saved = false
        compose.setContent { CinemArchiveTheme {
            OutingScheduleSheet(90, outing(), {}, { _, _, _, venue, companions, format, _, _, _, _ ->
                assertEquals("New cinema", venue)
                assertEquals(names, companions)
                assertNull(format)
                saved = true
            })
        } }
        compose.onNodeWithText("Theater").performScrollTo().performTextReplacement("New cinema")
        compose.onNodeWithText("Save tickets").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(saved) }
    }

    @Test fun deliberateFormatSelectionIsIncludedInTheEdit() {
        var selected: CinemaFormat? = null
        compose.setContent { CinemArchiveTheme {
            OutingScheduleSheet(90, outing(), {}, { _, _, _, _, companions, format, _, _, _, _ ->
                assertEquals(names, companions)
                selected = format
            })
        } }
        compose.onNodeWithText("3D").performScrollTo().performClick()
        compose.onNodeWithText("Save tickets").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(CinemaFormat.THREE_D, selected) }
    }
}

