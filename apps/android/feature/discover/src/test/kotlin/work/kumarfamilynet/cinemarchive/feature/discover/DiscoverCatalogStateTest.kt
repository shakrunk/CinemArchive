package work.kumarfamilynet.cinemarchive.feature.discover

import org.junit.Assert.*
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.TrendingTitle

class DiscoverCatalogStateTest {
    private val movie = TrendingTitle(42, "A movie", 2020, MediaType.MOVIE, null, null)
    private val series = TrendingTitle(42, "A series", 2021, MediaType.TV, null, null)

    @Test fun `same numeric ID has distinct ownership opening and grid identity`() {
        val library = mapOf(movie.mediaIdentity to "owned-movie")
        assertEquals("owned-movie", library[movie.mediaIdentity])
        assertNull(library[series.mediaIdentity])
        assertTrue(movie.mediaIdentity in library)
        assertFalse(series.mediaIdentity in library)
        assertNotEquals(movie.catalogKey, series.catalogKey)
        val bothOwned = library + (series.mediaIdentity to "owned-series")
        assertEquals("owned-series", bothOwned[series.mediaIdentity])
        assertEquals("owned-movie", bothOwned[movie.mediaIdentity])
    }

    @Test fun `media type filters remote results without filtering out alternate titles`() {
        val results = listOf(movie, series)
        assertEquals(results, filterDiscoverTitles(results, TypeFilter.ALL))
        assertEquals(listOf(movie), filterDiscoverTitles(results, TypeFilter.MOVIE))
        assertEquals(listOf(series), filterDiscoverTitles(results, TypeFilter.TV))
    }
}
