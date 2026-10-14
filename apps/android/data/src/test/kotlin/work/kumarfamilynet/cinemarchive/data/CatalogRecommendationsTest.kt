package work.kumarfamilynet.cinemarchive.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.model.MediaType

@RunWith(RobolectricTestRunner::class)
class CatalogRecommendationsTest {
    @Test fun movieSeedUsesReturnedTypesAndKeepsSameNumberMovieAndTv() {
        var query = ""
        val titles = fetchCatalogRecommendations(7, MediaType.MOVIE) {
            query = it
            """{"results":[
                {"id":42,"media_type":"tv","name":"A series","first_air_date":"2025-01-01","poster_path":"/series.jpg"},
                {"id":42,"media_type":"movie","title":"A movie","release_date":"1942-11-26","overview":"Story"},
                {"id":42,"media_type":"tv","name":"Duplicate"},
                {"id":84,"title":"Without media type"},
                {"id":0,"title":"Invalid"},{"id":99,"title":""},null
            ]}"""
        }
        assertEquals("action=recommendations&id=7&type=movie&page=1", query)
        assertEquals(listOf(42 to MediaType.TV, 42 to MediaType.MOVIE, 84 to MediaType.MOVIE), titles.map { it.tmdbId to it.type })
        assertEquals(listOf(2025, 1942, null), titles.map { it.year })
        assertEquals("https://image.tmdb.org/t/p/w500/series.jpg", titles.first().posterUrl)
        assertEquals("Story", titles[1].synopsis)
    }

    @Test fun tvSeedUsesTvEndpointAndEmptyPayloadIsEmpty() {
        assertTrue(fetchCatalogRecommendations(7, MediaType.TV) {
            assertEquals("action=recommendations&id=7&type=tv&page=1", it)
            "{\"results\":[]}"
        }.isEmpty())
        assertTrue(fetchCatalogRecommendations(7, MediaType.TV) { "{}" }.isEmpty())
    }

    @Test fun manualSeedNeverMakesAnInvalidRemoteRequest() {
        assertThrows(IllegalArgumentException::class.java) { fetchCatalogRecommendations(0, MediaType.MOVIE) { error("must not request") } }
    }

    @Test fun errorsAndCancellationRemainFailuresForIndependentRetry() {
        assertThrows(IOException::class.java) { fetchCatalogRecommendations(7, MediaType.MOVIE) { throw IOException("offline") } }
        assertThrows(CancellationException::class.java) { fetchCatalogRecommendations(7, MediaType.TV) { throw CancellationException("owner changed") } }
    }
}
