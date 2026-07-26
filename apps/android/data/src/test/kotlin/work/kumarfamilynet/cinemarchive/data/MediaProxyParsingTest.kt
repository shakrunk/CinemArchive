package work.kumarfamilynet.cinemarchive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult
import work.kumarfamilynet.cinemarchive.core.model.MediaType

/**
 * Covers the TMDB/OMDb → domain mapping in `MediaProxyParsing.kt` against payloads shaped like
 * the real `media-proxy` responses (trimmed to the keys the mapping reads).
 *
 * The mapping is where the Add flow's correctness actually lives: movie and TV carry the same
 * information under different keys at different depths, and several of these fields are ones
 * TMDB omits rather than nulls. Each case below is a shape that would otherwise only be caught
 * by adding a real title and noticing something missing on the Ledger weeks later.
 */
class MediaProxyParsingTest {

    private val movieFallback = MediaSearchResult(27205, "Inception", 2010, MediaType.MOVIE, null, null)
    private val tvFallback = MediaSearchResult(1396, "Breaking Bad", 2008, MediaType.TV, null, null)

    private val movieDetails = """
        {
          "id": 27205,
          "title": "Inception",
          "release_date": "2010-07-15",
          "runtime": 148,
          "overview": "A thief who steals corporate secrets…",
          "original_language": "en",
          "imdb_id": "tt1375666",
          "poster_path": "/poster.jpg",
          "backdrop_path": "/backdrop.jpg",
          "genres": [{"id": 28, "name": "Action"}, {"id": 878, "name": "Science Fiction"}],
          "production_companies": [{"name": "Legendary Pictures"}, {"name": "Syncopy"}],
          "belongs_to_collection": {"id": 448150, "name": "Inception Collection"},
          "release_dates": {"results": [
            {"iso_3166_1": "GB", "release_dates": [{"certification": "12A"}]},
            {"iso_3166_1": "US", "release_dates": [{"certification": ""}, {"certification": "PG-13"}]}
          ]},
          "credits": {
            "cast": [
              {"id": 6193, "name": "Leonardo DiCaprio", "character": "Cobb", "order": 0},
              {"id": 24045, "name": "Joseph Gordon-Levitt", "character": "Arthur", "order": 1},
              {"id": 6193, "name": "Leonardo DiCaprio", "character": "Projection", "order": 9}
            ],
            "crew": [
              {"id": 525, "name": "Christopher Nolan", "job": "Director", "department": "Directing"},
              {"id": 525, "name": "Christopher Nolan", "job": "Director", "department": "Directing"},
              {"id": 525, "name": "Christopher Nolan", "job": "Writer", "department": "Writing"},
              {"id": 999, "name": "Some Runner", "job": "Second Assistant Director", "department": "Directing"}
            ]
          }
        }
    """.trimIndent()

    private val tvDetails = """
        {
          "id": 1396,
          "name": "Breaking Bad",
          "first_air_date": "2008-01-20",
          "episode_run_time": [47],
          "overview": "A high school chemistry teacher…",
          "original_language": "en",
          "external_ids": {"imdb_id": "tt0903747"},
          "networks": [{"name": "AMC"}, {"name": "Netflix"}],
          "genres": [{"name": "Drama"}],
          "content_ratings": {"results": [
            {"iso_3166_1": "AU", "rating": "MA15+"},
            {"iso_3166_1": "US", "rating": "TV-MA"}
          ]},
          "created_by": [{"id": 66633, "name": "Vince Gilligan"}],
          "aggregate_credits": {"cast": [
            {"id": 17419, "name": "Bryan Cranston", "order": 0, "roles": [{"character": "Walter White"}]},
            {"id": 84497, "name": "Aaron Paul", "order": 1, "roles": [{"character": "Jesse Pinkman"}]}
          ]},
          "credits": {"cast": [{"id": 1, "name": "Ignored", "order": 0}], "crew": []},
          "seasons": [
            {"season_number": 0, "episode_count": 5, "air_date": "2009-02-17"},
            {"season_number": 2, "episode_count": 13, "air_date": "2009-03-08"},
            {"season_number": 1, "episode_count": 7, "air_date": "2008-01-20"}
          ]
        }
    """.trimIndent()

    @Test
    fun `movie details map every column the add path writes`() {
        val details = parseDetails(movieDetails, MediaType.MOVIE, movieFallback)

        assertEquals(27205, details.tmdbId)
        assertEquals("Inception", details.title)
        assertEquals(2010, details.year)
        assertEquals("2010-07-15", details.releaseDate)
        assertEquals("Christopher Nolan", details.director)
        assertEquals(listOf("Action", "Science Fiction"), details.genres)
        assertEquals(148, details.runtime)
        assertEquals("en", details.originalLanguage)
        assertEquals("tt1375666", details.imdbId)
        assertEquals(listOf("Legendary Pictures", "Syncopy"), details.studios)
        assertEquals(448150, details.collectionId)
        assertEquals("Inception Collection", details.collectionName)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", details.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w1280/backdrop.jpg", details.backdropUrl)
        assertTrue(details.seasons.isEmpty())
    }

    /** TMDB lists certifications per country and often leaves the first US entry blank; the web
     *  app reads the first *non-empty* US one, and a mismatch here would silently ship a "12A"
     *  to a US-configured library. */
    @Test
    fun `certification takes the first non-blank US entry`() {
        assertEquals("PG-13", parseDetails(movieDetails, MediaType.MOVIE, movieFallback).contentRating)
        assertEquals("TV-MA", parseDetails(tvDetails, MediaType.TV, tvFallback).contentRating)
    }

    /** `title_cast` is uniquely keyed by (title_id, tmdb_person_id) server-side, so an actor
     *  billed twice would fail the whole add. Highest billing wins. */
    @Test
    fun `cast is de-duplicated by person and ordered by billing`() {
        val cast = parseDetails(movieDetails, MediaType.MOVIE, movieFallback).cast

        assertEquals(listOf(6193, 24045), cast.map { it.tmdbPersonId })
        assertEquals("Cobb", cast.first().characterName)
    }

    /** Same constraint on `title_crew`, plus the job allowlist — TMDB's raw crew list runs to
     *  hundreds of rows on a big production. */
    @Test
    fun `crew keeps allowlisted jobs once each`() {
        val crew = parseDetails(movieDetails, MediaType.MOVIE, movieFallback).crew

        assertEquals(listOf("Director", "Writer"), crew.map { it.job })
        assertTrue(crew.none { it.job == "Second Assistant Director" })
    }

    @Test
    fun `tv details read name, network and imdb id from their own keys`() {
        val details = parseDetails(tvDetails, MediaType.TV, tvFallback)

        assertEquals("Breaking Bad", details.title)
        assertEquals(2008, details.year)
        assertEquals("AMC", details.network)
        assertEquals("tt0903747", details.imdbId)
        // TMDB reports TV runtime per episode, so titles.runtime stays null for a series —
        // matching the web app, and keeping the Ledger's movie-minutes stats honest.
        assertNull(details.runtime)
    }

    /** TV credits come from `aggregate_credits` (character nested under `roles[0]`), never the
     *  plain `credits` array that sits alongside it. */
    @Test
    fun `tv cast prefers aggregate credits`() {
        val cast = parseDetails(tvDetails, MediaType.TV, tvFallback).cast

        assertEquals(listOf("Bryan Cranston", "Aaron Paul"), cast.map { it.name })
        assertEquals("Walter White", cast.first().characterName)
    }

    @Test
    fun `tv creators are recorded as crew, leaving director null`() {
        val details = parseDetails(tvDetails, MediaType.TV, tvFallback)

        assertNull(details.director)
        assertEquals(listOf("Creator" to "Vince Gilligan"), details.crew.map { it.job to it.name })
    }

    /** Season 0 is TMDB's "Specials" bucket; counting it would inflate every episode total
     *  relative to the same series added on the web. */
    @Test
    fun `seasons drop specials and sort by number`() {
        val seasons = parseDetails(tvDetails, MediaType.TV, tvFallback).seasons

        assertEquals(listOf(1, 2), seasons.map { it.seasonNumber })
        assertEquals(listOf(7, 13), seasons.map { it.episodeCount })
        assertEquals(listOf(2008, 2009), seasons.map { it.airYear })
    }

    @Test
    fun `details fall back to the search hit when TMDB omits a field`() {
        val sparse = """{"id": 27205, "title": "Inception"}"""
        val fallback = movieFallback.copy(posterUrl = "https://poster", synopsis = "from search")

        val details = parseDetails(sparse, MediaType.MOVIE, fallback)

        assertEquals(2010, details.year)
        assertEquals("https://poster", details.posterUrl)
        assertEquals("from search", details.synopsis)
    }

    @Test
    fun `season episodes parse and sort`() {
        val body = """
            {"episodes": [
              {"episode_number": 2, "name": "Cat's in the Bag...", "air_date": "2008-01-27", "runtime": 48},
              {"episode_number": 1, "name": "Pilot", "air_date": "2008-01-20", "runtime": 58}
            ]}
        """.trimIndent()

        val episodes = parseSeasonEpisodes(body)

        assertEquals(listOf(1, 2), episodes.map { it.episodeNumber })
        assertEquals("Pilot", episodes.first().name)
        assertEquals(58, episodes.first().runtime)
    }

    @Test
    fun `search results drop untitled hits and read the type-specific keys`() {
        val body = """
            {"results": [
              {"id": 1, "name": "Severance", "first_air_date": "2022-02-18", "popularity": 90.5, "poster_path": "/s.jpg"},
              {"id": 2, "first_air_date": "1999-01-01", "popularity": 3.0}
            ]}
        """.trimIndent()

        val results = parseSearchPage(body, MediaType.TV)

        assertEquals(1, results.size)
        assertEquals("Severance", results.first().title)
        assertEquals(2022, results.first().year)
        assertEquals("https://image.tmdb.org/t/p/w500/s.jpg", results.first().posterUrl)
    }

    /** Search ranks by popularity rather than interleaving the way trending does — a query is a
     *  specific intent, so the best match has to be first regardless of which kind it is. */
    @Test
    fun `search merge ranks by popularity across both media types`() {
        val movies = listOf(
            MediaSearchResult(1, "Low movie", 2000, MediaType.MOVIE, null, null, popularity = 1.0),
            MediaSearchResult(2, "High movie", 2001, MediaType.MOVIE, null, null, popularity = 50.0),
        )
        val tv = listOf(MediaSearchResult(3, "Top show", 2002, MediaType.TV, null, null, popularity = 99.0))

        assertEquals(listOf("Top show", "High movie", "Low movie"), mergeSearchResults(movies, tv).map { it.title })
        assertEquals(2, mergeSearchResults(movies, tv, limit = 2).size)
    }

    /** OMDb encodes "no data" as the literal string "N/A" rather than omitting the key, and
     *  exposes Rotten Tomatoes only inside a source-keyed array. */
    @Test
    fun `critic scores parse OMDb's shape`() {
        val body = """
            {
              "imdbRating": "8.8",
              "Metascore": "74",
              "Ratings": [
                {"Source": "Internet Movie Database", "Value": "8.8/10"},
                {"Source": "Rotten Tomatoes", "Value": "87%"}
              ]
            }
        """.trimIndent()

        val scores = parseCriticScores(body)

        assertEquals(8.8, scores.imdbRating!!, 0.001)
        assertEquals(87, scores.rtScore)
        assertEquals(74, scores.metacriticScore)
    }

    @Test
    fun `critic scores treat N-A as absent`() {
        val scores = parseCriticScores("""{"imdbRating": "N/A", "Metascore": "N/A", "Ratings": []}""")

        assertNull(scores.imdbRating)
        assertNull(scores.rtScore)
        assertNull(scores.metacriticScore)
    }
}
