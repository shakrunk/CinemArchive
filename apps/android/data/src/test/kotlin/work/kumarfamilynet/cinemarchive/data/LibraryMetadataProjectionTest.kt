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
class LibraryMetadataProjectionTest {
    @Test fun libraryProjectsFilterMetadataAndCastUpdatesWithoutMixingTitles() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            fun title(id: String) = TitleEntity(id, 42, "MOVIE", "Film $id", 2020, "Director", listOf("Drama"), null, null, null, 100,
                "Network", "WATCHED", 4.5, null, "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", originalLanguage = "ja",
                tags = listOf("Favorite"), studios = listOf("Studio"), collectionId = 42, collectionName = "A collection")
            db.titleDao().upsertAll(listOf(title("one"), title("two")))
            db.titleCastDao().upsertAll(listOf(TitleCastEntity("cast1", "one", 10, "Actor One", null, 0), TitleCastEntity("cast2", "two", 20, "Actor Two", null, 0)))
            val repo = LibraryRepository(db.titleDao(), db.seasonDao(), db.episodeDao(), db.episodeWatchEventDao(), db.episodeRatingDao(), db.episodeReviewDao(),
                db.viewingDao(), db.cinemaOutingDao(), db.titleCastDao(), db.titleCrewDao(), db.theaterInterestDao(),
                MutationOutbox(db.outboxDao(), object : RemoteMutationWriter { override suspend fun push(entry: OutboxEntity) = PushResult.Success }, TitleConflictHandler(db.titleDao()), RoomTransactor(db)),
                object : EpisodeMetadataFetcher {
                    override suspend fun fetchSeasonEpisodes(tmdbId: Int, seasonNumber: Int) = emptyList<MediaEpisode>()
                    override suspend fun fetchEpisodeCast(tmdbId: Int, seasonNumber: Int, episodeNumber: Int) = EpisodeCast.EMPTY
                },
                personCreditsDao = db.personCreditsDao(),
            )
            val row = repo.observeLibrary().first().first { it.id == "one" }
            assertEquals("2026-01-01T00:00:00Z", row.addedAt)
            assertEquals("ja", row.originalLanguage)
            assertEquals(listOf("Drama"), row.genres)
            assertEquals(listOf("Favorite"), row.tags); assertEquals(listOf("Studio"), row.studios)
            assertEquals(42, row.collectionId); assertEquals("A collection", row.collectionName)
            assertEquals(listOf("Actor One"), row.castNames)
            db.titleCastDao().upsertAll(listOf(TitleCastEntity("cast3", "one", 30, "New Actor", null, 1)))
            assertEquals(listOf("Actor One", "New Actor"), repo.observeLibrary().first().first { it.id == "one" }.castNames)
        } finally { db.close() }
    }
}
