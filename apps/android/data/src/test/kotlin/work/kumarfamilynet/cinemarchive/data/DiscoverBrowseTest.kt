package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.MediaType

class DiscoverBrowseTest {
    private fun page(vararg ids: Int) = """{"results":[${ids.joinToString(",") {
        """{"id":$it,"title":"Movie $it","name":"TV $it","release_date":"2020-01-01","first_air_date":"2021-01-01"}"""
    }}]}"""

    @Test fun mixedGenrePagesRequestBothTypesAndRetainMovieFirstIdentity() = runBlocking {
        val requests = mutableListOf<String>()
        val result = fetchDiscoverBrowse(null, 18, 2) { query -> requests += query; page(42, 84) }
        assertEquals(setOf("action=discover&type=movie&page=2&genre=18", "action=discover&type=tv&page=2&genre=18"), requests.toSet())
        assertEquals(listOf(42 to MediaType.MOVIE, 42 to MediaType.TV, 84 to MediaType.MOVIE, 84 to MediaType.TV), result.map { it.tmdbId to it.type })
        assertEquals(listOf("Movie 42", "TV 42", "Movie 84", "TV 84"), result.map { it.title })
    }

    @Test fun selectedTypeGetsItsOwnFullTrendingPage() = runBlocking {
        val requests = mutableListOf<String>()
        val result = fetchDiscoverBrowse(MediaType.TV, null, 3) { query -> requests += query; page(*(1..20).toList().toIntArray()) }
        assertEquals(listOf("action=trending&type=tv&page=3"), requests)
        assertEquals(20, result.size)
        assertTrue(result.all { it.type == MediaType.TV })
    }

    @Test fun mixedPageMatchesWebTwentyItemLimitAndContinuesWhenOneTypeEmpty() = runBlocking {
        assertEquals(20, fetchDiscoverBrowse(null, 18, 1) { page(*(1..20).toList().toIntArray()) }.size)
        val result = fetchDiscoverBrowse(null, 18, 2) { if ("type=movie" in it) page() else page(70, 71) }
        assertEquals(listOf(70, 71), result.map { it.tmdbId })
        assertTrue(result.all { it.type == MediaType.TV })
        assertTrue(fetchDiscoverBrowse(null, 18, 3) { page() }.isEmpty())
    }

    @Test fun rejectedPagesFailuresAndCancellationDoNotBecomeEmptySuccess() = runBlocking {
        assertTrue(runCatching { fetchDiscoverBrowse(null, null, 0) { error("Must not call") } }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { fetchDiscoverBrowse(null, null, 501) { error("Must not call") } }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { fetchDiscoverBrowse(MediaType.TV, 18, 1) { "invalid JSON" } }.isFailure)
        assertTrue(runCatching { fetchDiscoverBrowse(MediaType.TV, null, 1) { throw CancellationException("Cancelled") } }.exceptionOrNull() is CancellationException)
        assertEquals("Offline", runCatching { fetchDiscoverBrowse(null, 18, 1) { error("Offline") } }.exceptionOrNull()?.message)
    }
}
