package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.MediaType

class CatalogLookupTest {
    @Test fun lookupRequestsEncodeNamesAndParsersRetainIdentification() {
        assertEquals("action=person_search&q=A+%26+B", catalogLookupQuery(" A & B ", true))
        assertEquals("action=company_search&q=Studio", catalogLookupQuery("Studio", false))
        val people = parseCatalogLookups("""{"results":[{"id":31,"name":"A Person","profile_path":"/face.jpg","known_for":[{"title":"Movie"},{"name":"Series"}]},{"id":0,"name":"Bad"}]}""", true)
        assertEquals(listOf(CatalogLookup(31, "A Person", "Movie, Series", "https://image.tmdb.org/t/p/w185/face.jpg")), people)
        val studio = parseCatalogLookups("""{"results":[{"id":42,"name":"A Studio","origin_country":"US","logo_path":null}]}""", false).single()
        assertEquals("US", studio.description); assertNull(studio.imageUrl)
    }

    @Test fun personCreditsMergeCastCrewWithoutCollidingMovieAndTelevisionIdentities() {
        fun row(id: Int, kind: String, popularity: Int) = JSONObject().put("id", id).put("media_type", kind)
            .put("title", "Movie $id").put("name", "Series $id").put("popularity", popularity)
        val body = JSONObject().put("cast", JSONArray().put(row(1, "movie", 2)).put(row(1, "tv", 3)))
            .put("crew", JSONArray().put(row(1, "movie", 9)).put(row(2, "movie", 4)).put(row(3, "person", 99)))
        val results = parseCatalogPersonTitles(body.toString())
        assertEquals(listOf(2 to MediaType.MOVIE, 1 to MediaType.TV, 1 to MediaType.MOVIE), results.map { it.tmdbId to it.type })
        val many = JSONObject().put("cast", JSONArray((1..50).map { row(it, "movie", it) }))
        assertEquals(40, parseCatalogPersonTitles(many.toString()).size)
    }

    @Test fun studioAllQueriesBothTypesAndKeepsInterleavedIdentities() = runTest {
        val queries = mutableListOf<String>()
        val results = fetchCatalogStudioTitles(42, null) { query ->
            queries += query
            """{"results":[{"id":1,"title":"Movie","name":"TV"}]}"""
        }
        assertEquals(setOf("action=discover&type=movie&company=42", "action=discover&type=tv&company=42"), queries.toSet())
        assertEquals(listOf(MediaType.MOVIE, MediaType.TV), results.map { it.type })
        queries.clear()
        fetchCatalogStudioTitles(42, MediaType.TV) { queries += it; """{"results":[]}""" }
        assertEquals(listOf("action=discover&type=tv&company=42"), queries)
    }
}
