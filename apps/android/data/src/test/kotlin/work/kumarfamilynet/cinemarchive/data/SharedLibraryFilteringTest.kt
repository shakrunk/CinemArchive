package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.*

class SharedLibraryFilteringTest {
    private fun title(id: String, name: String = id, year: Int = 2020, type: String = "movie",
        status: String = "watched", rating: Double? = 4.0, genres: List<String> = listOf("Drama"),
        added: String = "2026-01-01T00:00:00Z", json: String = "{}") =
        SharedLibraryTitle(id, 42, type, name, year, null, status, rating, genres, added, json)

    @Test fun searchMatchesWebFieldsWithoutBroadeningToCrewSeasonCastNetworkOrLanguage() {
        val projected = title("one", "The Film", json = """{
            "director":"A Director","tags":["Favorite"],"network":"Hidden network","original_language":"fr",
            "title_cast":[{"tmdb_person_id":1,"name":"Title Actor"}],
            "title_crew":[{"tmdb_person_id":2,"name":"Crew Editor"}],
            "seasons":[{"season_cast":[{"tmdb_person_id":3,"name":"Season Actor"}]}]
        }""").toLibraryFilterTitle()
        listOf("the film", "DIRECTOR", "drama", "favorite", "title actor").forEach { query ->
            assertEquals(query, listOf("one"), filterLibrary(listOf(projected), LibraryFilters(search = query)).map { it.id })
        }
        listOf("Crew Editor", "Season Actor", "Hidden network", "fr", " the film ").forEach { query ->
            assertTrue(query, filterLibrary(listOf(projected), LibraryFilters(search = query)).isEmpty())
        }
    }

    @Test fun facetsUseOnlyScopedRowsWithOrWithinAndAcrossDimensions() {
        val a = title("a", json = """{"tags":["Favorite"],"network":"Net","original_language":"en","studios":["A"]}""")
        val b = title("b", year = 1995, type = "tv", status = "watching", rating = 2.0, genres = listOf("Comedy", "Drama"),
            json = """{"tags":["Slow"],"network":"Stream","original_language":"fr","studios":["B"]}""")
        val c = title("c", rating = null, status = "watchlist", genres = listOf("Horror"),
            json = """{"tags":["Favorite"],"network":"Net","original_language":"en","studios":["A"]}""")
        val rows = listOf(a, b, c).map { it.toLibraryFilterTitle() }
        val cases = listOf(
            LibraryFilters(type = MediaType.TV) to listOf("b"),
            LibraryFilters(statuses = setOf(LibraryStatus.WATCHLIST)) to listOf("c"),
            LibraryFilters(genres = setOf("Drama", "Horror")) to listOf("a", "b", "c"),
            LibraryFilters(tags = setOf("Slow")) to listOf("b"),
            LibraryFilters(networks = setOf("Net")) to listOf("a", "c"),
            LibraryFilters(languages = setOf("fr")) to listOf("b"),
            LibraryFilters(decades = setOf("1990s")) to listOf("b"),
            LibraryFilters(studio = "A") to listOf("a", "c"),
            LibraryFilters(minRating = 3.5) to listOf("a"),
            LibraryFilters(genres = setOf("Drama", "Horror"), tags = setOf("Favorite"), languages = setOf("en"),
                decades = setOf("2020s"), studio = "A", minRating = 3.5) to listOf("a"),
        )
        cases.forEach { (filters, expected) -> assertEquals(filters.toString(), expected, filterLibrary(rows, filters).map { it.id }) }
        // A subset passed by anonymous/friend access rules never gains excluded titles or facets.
        val subset = listOf(a.toLibraryFilterTitle())
        assertEquals(listOf("Net"), libraryFilterChoices(subset).networks)
        assertEquals(listOf("a"), filterLibrary(subset, LibraryFilters()).map { it.id })
        assertTrue(filterLibrary(subset, LibraryFilters(studio = "B")).isEmpty())
    }

    @Test fun allSixSortFieldsAndBothDirectionsMatchTheirProjectedValues() {
        val rows = listOf(
            title("a", "Alpha", year = 2020, rating = 2.0, added = "2020-01-01", json = """{"director":"Zoe","viewings":[{"viewed_at":"2025-01-01"}]}"""),
            title("b", "Beta", year = 2000, rating = 4.0, added = "2021-01-01", json = """{"director":"Ann","viewings":[{"viewed_at":"2024-01-01"}]}"""),
            title("c", "Gamma", year = 2010, rating = null, added = "2019-01-01", json = """{"director":"Mia","viewings":[{"viewed_at":"2023-01-01"}]}"""),
        ).map { it.toLibraryFilterTitle() }
        val expected = mapOf(LibrarySortOrder.TITLE to listOf("a", "b", "c"),
            LibrarySortOrder.YEAR_NEWEST to listOf("b", "c", "a"), LibrarySortOrder.RATING_HIGHEST to listOf("c", "a", "b"),
            LibrarySortOrder.DIRECTOR to listOf("b", "c", "a"), LibrarySortOrder.ADDED_AT to listOf("c", "a", "b"),
            LibrarySortOrder.LAST_INTERACTION to listOf("c", "b", "a"))
        expected.forEach { (sort, ids) -> LibrarySortDirection.entries.forEach { direction ->
            assertEquals("$sort $direction", if (direction == LibrarySortDirection.ASCENDING) ids else ids.reversed(),
                filterLibrary(rows, LibraryFilters(sortOrder = sort, sortDirection = direction)).map { it.id })
        } }
    }

    @Test fun smartUsesActualEventDatesIncludingRatingsReviewsAndOffsetsButNeverMetadataRevision() {
        val row = JSONObject("""{"updated_at":"2099-01-01","viewings":[{"viewed_at":"2026-01-02","created_at":"2099-01-01"}],
            "episodes":[{"episode_watch_events":[{"watched_at":"2026-01-03T00:00:00Z"},{"watched_at":null}],
            "episode_ratings":[{"rated_at":"2026-01-04T00:00:00Z"}],
            "episode_reviews":[{"reviewed_at":"2026-01-04T01:00:00-02:00"}]}]}""")
        fun projected() = title("one", json = row.toString()).toLibraryFilterTitle().lastInteractionAt
        assertEquals("2026-01-04T01:00:00-02:00", projected())
        val ep = row.getJSONArray("episodes").getJSONObject(0)
        ep.remove("episode_reviews"); assertEquals("2026-01-04T00:00:00Z", projected())
        ep.remove("episode_ratings"); assertEquals("2026-01-03T00:00:00Z", projected())
        ep.remove("episode_watch_events"); assertEquals("2026-01-02", projected())
        row.remove("viewings"); assertEquals("2026-01-01T00:00:00Z", projected())
    }

    @Test fun personFilterRetainsSeparateSameNameIdsAcrossEveryCreditLevelIncludingSpecials() {
        val a = title("a", json = """{"title_cast":[{"tmdb_person_id":1,"name":"Same Name"}],
            "title_crew":[{"tmdb_person_id":2,"name":"Same Name"}],
            "seasons":[{"season_number":0,"season_cast":[{"tmdb_person_id":3,"name":"Specials Actor"}]}],
            "episodes":[{"season_number":0,"episode_crew":[{"tmdb_person_id":4,"name":"Specials Director"}]}]}""").toLibraryFilterTitle()
        val b = title("b", json = """{"title_cast":[{"tmdb_person_id":9,"name":"Same Name"}]}""").toLibraryFilterTitle()
        val rows = listOf(a, b)
        assertEquals(setOf(1, 2, 3, 4, 9), libraryFilterChoices(rows).people.map { it.person.tmdbPersonId }.toSet())
        (1..4).forEach { id -> assertEquals(listOf("a"), filterLibrary(rows, LibraryFilters(person = LibraryPerson(id, "Any label"))).map { it.id }) }
        assertEquals(listOf("b"), filterLibrary(rows, LibraryFilters(person = LibraryPerson(9, "Same Name"))).map { it.id })
    }

    @Test fun franchiseGroupingUsesCanonicalCollectionAndReleaseOrder() {
        val rows = listOf(
            title("late", "Alpha", json = """{"collection_id":5,"collection_name":"Saga","release_date":"2022-01-01"}"""),
            title("early", "Zeta", json = """{"collection_id":5,"collection_name":"Saga","release_date":"2020-01-01"}"""),
            title("other", "Middle"),
        ).map { it.toLibraryFilterTitle() }
        val sorted = filterLibrary(rows, LibraryFilters(sortOrder = LibrarySortOrder.TITLE, sortDirection = LibrarySortDirection.ASCENDING))
        val grouped = groupLibrary(sorted, LibraryGrouping.FRANCHISE)
        assertEquals(listOf("Saga", "Other titles"), grouped.map { it.label })
        assertEquals(listOf("early", "late"), grouped[0].titles.map { it.id })
        assertEquals(listOf("other"), grouped[1].titles.map { it.id })
    }

    @Test fun absentOptionalFieldsStayAbsentAndDoNotInventFilterChoices() {
        val row = title("minimal", rating = null, genres = emptyList()).toLibraryFilterTitle()
        assertNull(row.director); assertNull(row.network); assertNull(row.collectionId); assertNull(row.collectionName)
        assertTrue(row.people.isEmpty()); assertTrue(row.tags.isEmpty()); assertTrue(row.studios.isEmpty())
        assertTrue(libraryFilterChoices(listOf(row)).people.isEmpty())
        assertEquals(listOf("minimal"), filterLibrary(listOf(row), LibraryFilters()).map { it.id })
        assertTrue(filterLibrary(listOf(row), LibraryFilters(minRating = 0.5)).isEmpty())
    }
}
