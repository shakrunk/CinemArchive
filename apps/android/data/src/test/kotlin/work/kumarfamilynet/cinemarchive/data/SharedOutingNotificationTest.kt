package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.CinemaFormat
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaType

class SharedOutingNotificationTest {
    private fun payload() = JSONObject("""{"tmdb_id":42,"type":"movie","title":"Film","year":2026,"poster_url":"poster","showtime":"2099-10-08T19:00:00Z","ends_at":"2099-10-08T21:00:00Z","venue":"Cinema","format":"IMAX","seat":"F12","companions":["Friend",42],"booking_ref":"private","notes":"private"}""")

    @Test fun publicSnapshotPreservesCalendarFieldsButScheduleCopiesOnlyPublicHints() {
        val shared = checkNotNull(parseSharedOutingNotification(payload()))
        assertEquals("F12", shared.plan.seat)
        assertEquals(listOf("Friend"), shared.plan.companions)
        assertEquals("Cinema", shared.prefill.venue)
        assertEquals(CinemaFormat.IMAX, shared.prefill.format)
        assertEquals(shared.plan.showtime, shared.prefill.showtime)
        assertFalse(shared.toString().contains("private"))
        val request = shared.addRequest()
        assertEquals(LibraryStatus.WATCHLIST, request.status)
        assertEquals(42, request.details.tmdbId)
        assertEquals("Film", request.details.title)
        assertNull(request.notes)
        assertNull(request.rating)
    }

    @Test fun malformedSnapshotCannotExposeRecipientActions() {
        listOf("tmdb_id", "type", "title", "showtime", "ends_at").forEach { field ->
            assertNull(parseSharedOutingNotification(payload().also { it.remove(field) }))
        }
        assertNull(parseSharedOutingNotification(payload().put("tmdb_id", "42")))
        assertNull(parseSharedOutingNotification(payload().put("tmdb_id", 1.5)))
        assertNull(parseSharedOutingNotification(payload().put("showtime", "invalid")))
        assertNull(parseSharedOutingNotification(payload().put("type", "book")))
        assertNull(parseSharedOutingNotification(payload().put("ends_at", "2000-01-01T00:00:00Z")))
        assertNull(parseSharedOutingNotification(payload().put("format", "invented"))!!.plan.format)
    }

    @Test fun existingCatalogIdentityOpensWithoutDuplicateWrite() = runTest {
        val shared = checkNotNull(parseSharedOutingNotification(payload()))
        val id = resolveSharedOutingNotification(shared, { true }, { tmdb, type ->
            assertEquals(42, tmdb); assertEquals(MediaType.MOVIE, type); "owned"
        }, { error("Duplicate write") })
        assertEquals("owned", id)
    }

    @Test fun absentTitleMustFinishDurableAddBeforeOpeningSchedule() = runTest {
        val shared = checkNotNull(parseSharedOutingNotification(payload()))
        var written = false
        val id = resolveSharedOutingNotification(shared, { true }, { _, _ -> null }, {
            assertEquals(shared.addRequest(), it)
            written = true
            "new-title"
        })
        assertTrue(written)
        assertEquals("new-title", id)
    }

    @Test fun accountSwitchCannotWriteOrNavigateForAnotherOwner() = runTest {
        val shared = checkNotNull(parseSharedOutingNotification(payload()))
        var active = true
        assertNull(resolveSharedOutingNotification(shared, { active }, { _, _ -> active = false; null }, { error("Stale write") }))
        active = true
        assertNull(resolveSharedOutingNotification(shared, { active }, { _, _ -> null }, { active = false; "old-title" }))
        assertNull(resolveSharedOutingNotification(shared, { false }, { _, _ -> error("Inactive read") }, { error("Inactive write") }))
    }
}
