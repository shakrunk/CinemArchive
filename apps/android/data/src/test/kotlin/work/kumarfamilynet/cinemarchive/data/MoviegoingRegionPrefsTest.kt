package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.model.MediaType

/** Default now-playing region (#202) cases from docs/android-contracts/moviegoing-prefs.md. */
@RunWith(RobolectricTestRunner::class)
class MoviegoingRegionPrefsTest {
    private val stamp = "\"updatedAt\":\"2026-10-29T10:00:00Z\""

    private fun parse(region: String) = parseMoviegoingPrefs(JSONObject("""{"region":$region,$stamp}"""))

    @Test fun parsesAnUppercaseCode() {
        assertEquals("GB", parse("\"GB\"")!!.region)
    }

    @Test fun anAbsentOrNullRegionMeansNoneSet() {
        assertNull(parseMoviegoingPrefs(JSONObject("{$stamp}"))!!.region)
        assertNull(parse("null")!!.region)
    }

    @Test fun anythingButTwoUppercaseLettersRejectsThePayload() {
        listOf("\"gb\"", "\"G\"", "\"GBR\"", "\"G1\"", "\"\"", "\" GB\"", "7", "[\"GB\"]").forEach {
            assertNull(it, parse(it))
        }
    }

    @Test fun settingTheRegionKeepsEveryOtherKeyAndStampsTheEdit() {
        val base = JSONObject("""{"budget":{"amount":50,"period":"month"},"future":1,$stamp}""")
        val next = moviegoingPrefsWithRegion(base, "CA", "2026-10-30T10:00:00Z")
        assertEquals("CA", next.getString("region"))
        assertEquals(1, next.getInt("future"))
        assertEquals(50, next.getJSONObject("budget").getInt("amount"))
        assertEquals("2026-10-30T10:00:00Z", next.getString("updatedAt"))
    }

    @Test fun clearingWritesAnExplicitNullSoTheClearPropagates() {
        val next = moviegoingPrefsWithRegion(JSONObject("""{"region":"CA",$stamp}"""), null, "2026-10-30T10:00:00Z")
        assertTrue(next.has("region") && next.isNull("region"))
        assertNull(parseMoviegoingPrefs(next)!!.region)
    }

    @Test fun nowPlayingRequestsTheRegionAndReadsMovies() = runBlocking {
        val requests = mutableListOf<String>()
        val body = """{"results":[{"id":7,"title":"Seven","release_date":"2026-10-01"},{"id":8}]}"""
        val result = fetchNowPlayingPage("GB") { query -> requests += query; body }
        assertEquals(listOf("action=now_playing&region=GB"), requests)
        assertEquals(listOf(7), result.map { it.tmdbId })
        assertTrue(result.all { it.type == MediaType.MOVIE })
    }

    @Test fun nowPlayingRefusesAMalformedRegionBeforeAnyRequest() = runBlocking {
        listOf("gb", "GBR", "", "G&", "US&action=x").forEach { region ->
            val failure = runCatching { fetchNowPlayingPage(region) { error("Must not call") } }.exceptionOrNull()
            assertTrue(region, failure is IllegalArgumentException)
        }
    }
}
