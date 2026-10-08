package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.LedgerWidgetId

@RunWith(RobolectricTestRunner::class)
class SharedLibrarySnapshotTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun movie() = JSONObject("""{
        "id":"movie","user_id":"shared-owner","tmdb_id":1,"type":"movie","title":"Shared film",
        "year":2000,"status":"watched","rating":4,"runtime":120,"genres":["Drama"],
        "imdb_rating":7.2,"original_language":"fr","release_date":"2000-02-01",
        "added_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z",
        "title_cast":[{"id":"cast","title_id":"movie","user_id":"shared-owner","tmdb_person_id":10,"name":"Actor","cast_order":0}],
        "title_crew":[{"id":"crew","title_id":"movie","user_id":"shared-owner","tmdb_person_id":20,"name":"Director","job":"Director"}],
        "viewings":[
          {"id":"v1","title_id":"movie","user_id":"shared-owner","viewed_at":"2026-01-01","venue":"Cinema","companions":[{"name":"Sam"}]},
          {"id":"v2","title_id":"movie","user_id":"shared-owner","viewed_at":"2026-02-01","venue":"Cinema"}
        ]
    }""")
    private fun library(vararg titles: JSONObject, layout: String? = null) = SharedLibrary(
        "shared-owner", parseSharedTitles(JSONArray(titles.toList()).toString(), "shared-owner"), layout)

    @Test fun graphDrivesBoardWithoutWritingAnOwnerDatabaseOrExposingSpend() = runBlocking {
        val owner = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val beforeFiles = context.databaseList().toSet()
            val snapshot = buildSharedLibrarySnapshot(context, library(movie()))
            assertEquals(beforeFiles, context.databaseList().toSet())
            assertTrue(owner.titleDao().observeAllTitles().first().isEmpty())
            assertTrue(owner.outboxDao().getPending().isEmpty())
            assertEquals(1, snapshot.stats.totalMovies)
            assertEquals(2, snapshot.stats.totalViewings)
            assertEquals(120, snapshot.stats.totalWatchedMovieMinutes)
            assertEquals(20, snapshot.layout.size)
            val board = snapshot.boards.values.first()
            assertEquals("Actor", board.ensemble.single().label)
            assertEquals("Director", board.auteurs.single().label)
            assertEquals("movie", board.encores.single().titleId)
            assertEquals(2, board.moviegoing.tripCount)
            assertEquals("Sam", board.moviegoing.companions.single().label)
            assertNull(board.moviegoing.totalSpend)
            assertTrue(board.moviegoing.formats.isEmpty())
            assertTrue(board.moviegoing.venueSpend.isEmpty())
            assertTrue(board.moviegoing.formatSpend.isEmpty())
            // Loading a second token never reuses the previous token's projection.
            val empty = buildSharedLibrarySnapshot(context, library())
            assertEquals(0, empty.stats.totalMovies)
            assertEquals(0, empty.stats.totalViewings)
            assertTrue(empty.boards.values.all { it.ensemble.isEmpty() && it.moviegoing.tripCount == 0 })
        } finally { owner.close() }
    }

    @Test fun ownerLayoutAndPerWidgetScopeUseTheSameScopedGraph() = runBlocking {
        val series = JSONObject("""{
          "id":"series","user_id":"shared-owner","tmdb_id":2,"type":"tv","title":"Shared series",
          "year":2020,"status":"watching","rating":null,"genres":["Comedy"],"added_at":"2026-01-01T00:00:00Z",
          "episodes":[{"id":"ep","title_id":"series","user_id":"shared-owner","season_number":1,"episode_number":1,"runtime":30,
            "episode_watch_events":[{"id":"watch","episode_id":"ep","user_id":"shared-owner","watched_at":"2026-02-01"}],
            "episode_ratings":[{"id":"rate","episode_id":"ep","user_id":"shared-owner","rating":4.5,"rated_at":"2026-02-02T00:00:00Z"}],
            "episode_reviews":[{"id":"review","episode_id":"ep","user_id":"shared-owner","review_text":"Great","reviewed_at":"2026-02-03T00:00:00Z"}]}]
        }""")
        val layout = """[{"id":"tv-genres","panel":"genres","width":"sm","settings":{"scope":"tv"}},
            {"id":"movies-genres","panel":"genres","width":"full","settings":{"scope":"movies"}}]"""
        val snapshot = buildSharedLibrarySnapshot(context, library(movie(), series, layout = layout))
        assertEquals(listOf("tv-genres", "movies-genres"), snapshot.layout.map { it.id })
        assertEquals(LedgerWidgetId.GENRES, snapshot.layout.first().panel)
        assertEquals(listOf("Comedy"), snapshot.boards.getValue("tv-genres").genres.map { it.label })
        assertEquals(listOf("Drama"), snapshot.boards.getValue("movies-genres").genres.map { it.label })
        assertEquals("Great", snapshot.library.titles.last().graph().getJSONArray("episodes")
            .getJSONObject(0).getJSONArray("episode_reviews").getJSONObject(0).getString("review_text"))
    }

    @Test fun sharedProjectionExactlyMatchesOwnerLedgerForCreditsScoresRuntimeAndHistory() = runBlocking {
        val owner = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            owner.titleDao().upsertAll(listOf(TitleEntity(
                id = "movie", tmdbId = 1, type = "MOVIE", title = "Shared film", year = 2000,
                director = null, genres = listOf("Drama"), posterUrl = null, backdropUrl = null,
                synopsis = null, runtime = 120, network = null, status = "WATCHED", rating = 4.0,
                notes = null, addedAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
                imdbRating = 7.2, originalLanguage = "fr", releaseDate = "2000-02-01",
            )))
            owner.titleCastDao().upsertAll(listOf(TitleCastEntity("cast", "movie", 10, "Actor", null, 0)))
            owner.titleCrewDao().upsertAll(listOf(TitleCrewEntity("crew", "movie", 20, "Director", "Director", null)))
            owner.viewingDao().upsertAll(listOf(
                ViewingEntity("v1", "movie", "2026-01-01", null, null, "Cinema", listOf("Sam")),
                ViewingEntity("v2", "movie", "2026-02-01", null, null, "Cinema"),
            ))
            val ledger = LedgerRepository(owner.titleDao(), owner.viewingDao(), owner.titleCastDao(), owner.titleCrewDao(),
                owner.cinemaOutingDao(), owner.episodeWatchEventDao(), owner.seasonDao(), owner.episodeDao())
            val snapshot = buildSharedLibrarySnapshot(context, library(movie(),
                layout = """[{"id":"fixture","panel":"genres","width":"full","settings":{"scope":"all","timeRange":"all"}}]"""))
            assertEquals(ledger.observeLedgerStats().first(), snapshot.stats)
            assertEquals(ledger.observeLedgerBoard().first(), snapshot.boards.getValue("fixture"))
        } finally { owner.close() }
    }

    @Test fun corruptLayoutFallsBackAndFailedProjectionLeavesNoPersistentDatabase() = runBlocking {
        val before = context.databaseList().toSet()
        val snapshot = buildSharedLibrarySnapshot(context, library(layout = "invalid"))
        assertEquals(20, snapshot.layout.size)
        val broken = movie().put("title_cast", JSONArray("""[{"id":"broken","title_id":"movie","user_id":"shared-owner","name":"Missing TMDB id"}]"""))
        try {
            buildSharedLibrarySnapshot(context, library(broken))
            fail("Expected malformed graph failure")
        } catch (_: org.json.JSONException) { }
        assertEquals(before, context.databaseList().toSet())
        assertEquals(0, buildSharedLibrarySnapshot(context, library()).stats.totalMovies)
    }
}
