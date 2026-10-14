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
class PersonDetailProjectionTest {
    @Test fun creditedIdsRolesAndParentScopesSurviveProjectionAndLiveUpdates() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            fun title(id: String) = TitleEntity(id, 42, "TV", "Show $id", 2020, null, emptyList(), null, null, null, 30,
                null, "WATCHING", null, null, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
            db.titleDao().upsertAll(listOf(title("one"), title("other")))
            db.seasonDao().upsertAll(listOf(SeasonEntity("s0", "one", 0, 1, 0, null), SeasonEntity("s1", "one", 1, 1, 0, null)))
            db.episodeDao().upsertAll(listOf(EpisodeEntity("e0", "one", "s0", 1, "Special", null, 30), EpisodeEntity("e1", "one", "s1", 1, "Pilot", null, 30)))
            db.titleCastDao().upsertAll(listOf(TitleCastEntity("tc1", "one", 42, "Same Name", "Lead", 2),
                TitleCastEntity("tc2", "one", 84, "Same Name", "Friend", 0), TitleCastEntity("tc3", "other", 99, "Other person", null, 0)))
            db.titleCrewDao().upsertAll(listOf(TitleCrewEntity("crew", "one", 42, "Same Name", "Creator", null)))
            db.personCreditsDao().upsertSeasonCast(listOf(SeasonCastEntity("sc0", "one", "s0", 100, "Special Actor", "Guest", 0),
                SeasonCastEntity("sc1", "one", "s1", 101, "Regular Actor", null, 0)))
            db.personCreditsDao().upsertEpisodeCrew(listOf(EpisodeCrewEntity("ec0", "one", "e0", 102, "Special Writer", "Story"),
                EpisodeCrewEntity("ec1", "one", "e1", 103, "Pilot Director", "Director")))
            val repo = LibraryRepository(db.titleDao(), db.seasonDao(), db.episodeDao(), db.episodeWatchEventDao(), db.episodeRatingDao(), db.episodeReviewDao(),
                db.viewingDao(), db.cinemaOutingDao(), db.titleCastDao(), db.titleCrewDao(), db.theaterInterestDao(),
                MutationOutbox(db.outboxDao(), object : RemoteMutationWriter { override suspend fun push(entry: OutboxEntity) = PushResult.Success }, TitleConflictHandler(db.titleDao()), RoomTransactor(db)),
                object : EpisodeMetadataFetcher {
                    override suspend fun fetchSeasonEpisodes(tmdbId: Int, seasonNumber: Int) = emptyList<MediaEpisode>()
                    override suspend fun fetchEpisodeCast(tmdbId: Int, seasonNumber: Int, episodeNumber: Int) = EpisodeCast.EMPTY
                }, personCreditsDao = db.personCreditsDao())
            val detail = repo.observeTitleDetail("one").first()!!
            assertEquals(listOf(84, 42), detail.cast.map { it.tmdbPersonId })
            assertEquals(listOf("Friend", "Lead"), detail.cast.map { it.role })
            assertEquals(PersonCredit(42, "Same Name", "Creator"), detail.crew.single())
            assertEquals(100, detail.seasons.first { it.seasonNumber == 0 }.cast.single().tmdbPersonId)
            assertEquals(102, detail.seasons.first { it.seasonNumber == 0 }.episodes.single().crew.single().tmdbPersonId)
            assertEquals(103, detail.seasons.first { it.seasonNumber == 1 }.episodes.single().crew.single().tmdbPersonId)
            assertEquals(setOf(42, 84, 100, 101, 102, 103), repo.observeLibrary().first().first { it.id == "one" }.people.map { it.tmdbPersonId }.toSet())
            db.personCreditsDao().deleteEpisodeCrew("ec1")
            assertTrue(repo.observeTitleDetail("one").first()!!.seasons.first { it.seasonNumber == 1 }.episodes.single().crew.isEmpty())
            assertFalse(repo.observeLibrary().first().first { it.id == "one" }.people.any { it.tmdbPersonId == 103 })
            assertTrue(repo.observeTitleDetail("other").first()!!.seasons.isEmpty())
        } finally { db.close() }
    }
}
