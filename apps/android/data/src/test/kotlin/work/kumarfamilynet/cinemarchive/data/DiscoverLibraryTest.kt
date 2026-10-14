package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.*

@RunWith(RobolectricTestRunner::class)
class DiscoverLibraryTest {
    private fun title(id: String, tmdb: Int = 42, type: String = "MOVIE") = TitleEntity(id, tmdb, type, "Title $id", 2020,
        null, emptyList(), null, null, null, 30, null, "WATCHLIST", null, null, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")

    @Test fun sourceOrderManualTitlesAndSameNamePeopleHaveStableIdentities() {
        val result = discoverLibrary(listOf(title("second"), title("first", 42, "TV"), title("manual", 0)), listOf(
            TitleCastEntity("a", "second", 7, "Zulu", null, 2),
            TitleCastEntity("b", "second", 8, "Alex", null, 1),
            TitleCastEntity("c", "first", 9, "Alex", null, 0),
            TitleCastEntity("d", "first", 7, "Renamed duplicate", null, 1),
            TitleCastEntity("invalid", "first", 0, "Invalid", null, 2),
            TitleCastEntity("foreign", "absent", 99, "Absent title", null, 0),
        ))
        assertEquals(listOf("second", "first", "manual"), result.titles.map { it.id })
        assertEquals(listOf(8, 9, 7), result.cast.map { it.tmdbPersonId })
        assertEquals(listOf("Alex", "Alex", "Zulu"), result.cast.map { it.name })
        assertEquals(setOf(42 to MediaType.MOVIE, 42 to MediaType.TV, 0 to MediaType.MOVIE), result.ownedKeys)
    }

    @Test fun roomObserverUsesOnlyTitleCastAndReflectsRemovalAndNewOwnership() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.titleDao().upsertAll(listOf(title("movie"), title("series", 42, "TV")))
            db.seasonDao().upsertAll(listOf(SeasonEntity("s", "series", 0, 1, 0, null)))
            db.episodeDao().upsertAll(listOf(EpisodeEntity("e", "series", "s", 1, "Special", null, 30)))
            db.titleCastDao().upsertAll(listOf(TitleCastEntity("cast", "movie", 7, "Title actor", "Lead", 0)))
            db.titleCrewDao().upsertAll(listOf(TitleCrewEntity("crew", "movie", 8, "Director", "Director", null)))
            db.personCreditsDao().upsertSeasonCast(listOf(SeasonCastEntity("sc", "series", "s", 9, "Season actor", null, 0)))
            db.personCreditsDao().upsertEpisodeCrew(listOf(EpisodeCrewEntity("ec", "series", "e", 10, "Writer", "Writer")))
            val repo = LibraryRepository(db.titleDao(), db.seasonDao(), db.episodeDao(), db.episodeWatchEventDao(), db.episodeRatingDao(), db.episodeReviewDao(),
                db.viewingDao(), db.cinemaOutingDao(), db.titleCastDao(), db.titleCrewDao(), db.theaterInterestDao(),
                MutationOutbox(db.outboxDao(), object : RemoteMutationWriter { override suspend fun push(entry: OutboxEntity) = PushResult.Success }, TitleConflictHandler(db.titleDao()), RoomTransactor(db)),
                object : EpisodeMetadataFetcher {
                    override suspend fun fetchSeasonEpisodes(tmdbId: Int, seasonNumber: Int) = emptyList<MediaEpisode>()
                    override suspend fun fetchEpisodeCast(tmdbId: Int, seasonNumber: Int, episodeNumber: Int) = EpisodeCast.EMPTY
                }, personCreditsDao = db.personCreditsDao())
            assertEquals(listOf(7), repo.observeDiscoverLibrary().first().cast.map { it.tmdbPersonId })
            assertEquals(2, repo.observeDiscoverLibrary().first().ownedKeys.size)
            db.titleDao().upsertAll(listOf(title("new", 84)))
            assertTrue(84 to MediaType.MOVIE in repo.observeDiscoverLibrary().first().ownedKeys)
            db.titleCastDao().upsertAll(listOf(TitleCastEntity("cast", "movie", 7, "Updated actor", null, 0)))
            assertEquals("Updated actor", repo.observeDiscoverLibrary().first().cast.single().name)
            db.titleDao().deleteById("movie")
            val remaining = repo.observeDiscoverLibrary().first()
            assertTrue(remaining.cast.isEmpty())
            assertFalse(42 to MediaType.MOVIE in remaining.ownedKeys)
            assertTrue(42 to MediaType.TV in remaining.ownedKeys)
        } finally { db.close() }
    }
}
