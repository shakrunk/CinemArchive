package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult
import work.kumarfamilynet.cinemarchive.core.model.MediaType

class CatalogEnrichmentTest {
    private val fallback = MediaSearchResult(1, "Film", 2026, MediaType.MOVIE, null, null)
    private fun base(imdb: String = "tt1") = parseDetails(
        """{"id":1,"imdb_id":"$imdb"}""", MediaType.MOVIE, fallback,
    )

    @Test fun `independent metadata lookups retain accolades when ratings fail`() = runTest {
        val queries = mutableSetOf<String>()
        val result = enrichCatalogDetails(base()) { query ->
            queries += query
            when {
                query.startsWith("action=ratings") -> error("Ratings unavailable")
                query.startsWith("action=rt_link") -> """{"rtUrl":"https://www.rottentomatoes.com/m/film"}"""
                else -> """{"awardsCount":0,"bechdel":{"outcome":"pass","score":"3/3"}}"""
            }
        }
        assertEquals(3, queries.size)
        assertNull(result.imdbRating)
        assertEquals("https://www.rottentomatoes.com/m/film", result.rtUrl)
        assertEquals(0, result.awardsCount)
        assertEquals("pass", result.bechdelOutcome)
        assertEquals("3/3", result.bechdelScore)
    }

    @Test fun `missing IMDb avoids optional requests and cancellation propagates`() = runTest {
        val noId = base().copy(imdbId = null)
        assertEquals(noId, enrichCatalogDetails(noId) { error("Should not request") })
        try {
            enrichCatalogDetails(base()) { throw CancellationException("Owner changed") }
            fail("Cancellation must escape")
        } catch (_: CancellationException) { }
    }

    @Test fun `aggregate credits preserve images counts and creator portraits`() {
        val details = parseDetails("""{
          "id":1,"aggregate_credits":{"cast":[{"id":2,"name":"Actor","order":0,
          "profile_path":"/actor.jpg","total_episode_count":12,"roles":[{"character":"Role"}]}]},
          "created_by":[{"id":3,"name":"Creator","profile_path":"/creator.jpg"}],
          "credits":{"crew":[{"id":4,"name":"Director","job":"Director","profile_path":"/director.jpg"}]}
        }""", MediaType.TV, fallback.copy(type = MediaType.TV))
        assertEquals(12, details.cast.single().episodeCount)
        assertEquals("https://image.tmdb.org/t/p/w185/actor.jpg", details.cast.single().profileUrl)
        assertEquals("https://image.tmdb.org/t/p/w185/creator.jpg", details.crew.first { it.job == "Creator" }.profileUrl)
        assertEquals("https://image.tmdb.org/t/p/w185/director.jpg", details.crew.first { it.job == "Director" }.profileUrl)
        assertNull(parseCast(JSONObject("""{"credits":{"cast":[{"id":2,"name":"Actor"}]}}"""), MediaType.MOVIE).single().episodeCount)
    }
}
