package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingDao
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeRatingDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeRatingEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeReviewDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeReviewEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeWatchCount
import work.kumarfamilynet.cinemarchive.core.database.EpisodeWatchEventDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeWatchEventEntity
import work.kumarfamilynet.cinemarchive.core.database.OutboxDao
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.database.SeasonDao
import work.kumarfamilynet.cinemarchive.core.database.SeasonEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleCastDao
import work.kumarfamilynet.cinemarchive.core.database.TitleCastEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleCrewDao
import work.kumarfamilynet.cinemarchive.core.database.TitleCrewEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleDao
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleIdByTmdbKey
import work.kumarfamilynet.cinemarchive.core.database.TitleLastInteraction
import work.kumarfamilynet.cinemarchive.core.database.TitleListRow
import work.kumarfamilynet.cinemarchive.core.database.ViewingDao
import work.kumarfamilynet.cinemarchive.core.database.ViewingEntity
import work.kumarfamilynet.cinemarchive.core.model.MediaEpisode

/**
 * Covers `LibraryRepository.backfillEpisodeMetadata` — the on-demand TMDB fetch that fills in
 * episode synopsis/stillUrl for titles that were never opened on the web app (see that method's
 * kdoc). Room I/O is faked the same way `LibraryRepositoryAddTitleTest` does it; only the DAOs
 * this path actually touches are backed, everything else throws — which doubles as the
 * assertion that a movie, or an already-fully-populated season, never reaches them.
 */
class LibraryRepositoryBackfillEpisodeMetadataTest {

    private val outboxDao = RecordingOutboxDao2()

    private fun repository(titleDao: TitleDao, seasonDao: SeasonDao, episodeDao: EpisodeDao, fetcher: EpisodeMetadataFetcher) =
        LibraryRepository(
            titleDao = titleDao,
            seasonDao = seasonDao,
            episodeDao = episodeDao,
            watchEventDao = NoWatchEventsDao2,
            ratingDao = NoEpisodeRatingsDao2,
            reviewDao = NoEpisodeReviewsDao2,
            viewingDao = NoViewingDao2,
            cinemaOutingDao = NoOutingsDao2,
            titleCastDao = NoCastDao2,
            titleCrewDao = NoCrewDao2,
            outbox = MutationOutbox(outboxDao, NoopWriter2, NoopConflictHandler2),
            episodeMetadataFetcher = fetcher,
        )

    @Test
    fun `fills only the null fields, preserving anything already written, and skips a fully-populated season`() = runTest {
        val title = BackfillFakeTitleDao(TitleEntity(
            id = "t1", tmdbId = 1396, type = "TV", title = "Breaking Bad", year = 2008, director = null,
            genres = emptyList(), posterUrl = null, backdropUrl = null, synopsis = null, runtime = null,
            network = "AMC", status = "WATCHING", rating = null, notes = null, addedAt = "now", updatedAt = "now",
        ))
        val seasons = BackfillFakeSeasonDao(listOf(
            SeasonEntity("s1", "t1", seasonNumber = 1, episodeCount = 2, episodesWatched = 0, airYear = 2008),
            SeasonEntity("s2", "t1", seasonNumber = 2, episodeCount = 1, episodesWatched = 1, airYear = 2009),
        ))
        val episodes = BackfillFakeEpisodeDao(listOf(
            EpisodeEntity("e1", "t1", "s1", episodeNumber = 1, episodeName = "Pilot", airDate = null, runtime = 58, synopsis = null, stillUrl = null),
            EpisodeEntity("e2", "t1", "s1", episodeNumber = 2, episodeName = "Cat's in the Bag...", airDate = null, runtime = 48, synopsis = "existing synopsis", stillUrl = null),
            EpisodeEntity("e3", "t1", "s2", episodeNumber = 1, episodeName = "Seven Thirty-Seven", airDate = null, runtime = 47, synopsis = "already there", stillUrl = "already-url"),
        ))
        val fetcher = FakeFetcher(
            mapOf(
                (1396 to 1) to listOf(
                    MediaEpisode(1, "Pilot", null, 58, synopsis = "fetched synopsis 1", stillUrl = "fetched-still-1"),
                    MediaEpisode(2, "Cat's in the Bag...", null, 48, synopsis = "fetched synopsis 2", stillUrl = "fetched-still-2"),
                ),
            ),
        )
        val repo = repository(title, seasons, episodes, fetcher)

        repo.backfillEpisodeMetadata("t1")

        // Season 2 was already fully populated — never fetched, never touched.
        assertEquals(listOf(1), fetcher.calledSeasons)
        assertEquals(2, episodes.updated.size)

        val e1 = episodes.updated.single { it.id == "e1" }
        assertEquals("fetched synopsis 1", e1.synopsis)
        assertEquals("fetched-still-1", e1.stillUrl)

        val e2 = episodes.updated.single { it.id == "e2" }
        assertEquals("existing synopsis", e2.synopsis) // preserved, not overwritten
        assertEquals("fetched-still-2", e2.stillUrl) // gap filled

        assertEquals(2, outboxDao.entries.size)
        assertTrue(outboxDao.entries.all { it.entityType == "episode_metadata" && it.operation == "update" })
        val payload = JSONObject(outboxDao.entries.single { it.entityId == "e1" }.payloadJson)
        assertEquals("fetched synopsis 1", payload.getString("synopsis"))
        assertEquals("fetched-still-1", payload.getString("stillUrl"))
    }

    @Test
    fun `does nothing for a movie`() = runTest {
        val title = BackfillFakeTitleDao(TitleEntity(
            id = "m1", tmdbId = 27205, type = "MOVIE", title = "Inception", year = 2010, director = null,
            genres = emptyList(), posterUrl = null, backdropUrl = null, synopsis = null, runtime = 148,
            network = null, status = "WATCHLIST", rating = null, notes = null, addedAt = "now", updatedAt = "now",
        ))
        // Throwing fakes: reaching either would fail the test, proving the movie guard short-circuits first.
        val repo = repository(title, ThrowingSeasonDao, ThrowingEpisodeDao, ThrowingFetcher)

        repo.backfillEpisodeMetadata("m1")

        assertTrue(outboxDao.entries.isEmpty())
    }
}

private class BackfillFakeTitleDao(private val title: TitleEntity) : TitleDao {
    override fun observeLibrary(): Flow<List<TitleListRow>> = throw UnsupportedOperationException()
    override fun observeLastInteractions(): Flow<List<TitleLastInteraction>> = throw UnsupportedOperationException()
    override fun observeTitle(titleId: String): Flow<TitleEntity?> = throw UnsupportedOperationException()
    override fun observeLibraryTmdbIds(): Flow<List<Int>> = throw UnsupportedOperationException()
    override fun observeLibraryTitleIdsByTmdbKey(): Flow<List<TitleIdByTmdbKey>> = throw UnsupportedOperationException()
    override suspend fun findIdByTmdbKey(tmdbId: Int, type: String): String? = throw UnsupportedOperationException()
    override suspend fun getById(id: String): TitleEntity? = title.takeIf { it.id == id }
    override fun observeAllTitles(): Flow<List<TitleEntity>> = throw UnsupportedOperationException()
    override suspend fun upsertAll(titles: List<TitleEntity>) = throw UnsupportedOperationException()
    override suspend fun updateStatus(titleId: String, status: String, updatedAt: String) = throw UnsupportedOperationException()
    override suspend fun updateRating(titleId: String, rating: Double, updatedAt: String) = throw UnsupportedOperationException()
    override suspend fun count(): Int = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private class BackfillFakeSeasonDao(private val seasons: List<SeasonEntity>) : SeasonDao {
    override fun observeSeasons(titleId: String): Flow<List<SeasonEntity>> = MutableStateFlow(seasons.filter { it.titleId == titleId })
    override fun observeAllSeasons(): Flow<List<SeasonEntity>> = throw UnsupportedOperationException()
    override suspend fun upsertAll(seasons: List<SeasonEntity>) = throw UnsupportedOperationException()
    override suspend fun findSeasonId(titleId: String, seasonNumber: Int): String? = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private class BackfillFakeEpisodeDao(private val episodes: List<EpisodeEntity>) : EpisodeDao {
    val updated = mutableListOf<EpisodeEntity>()
    override fun observeEpisodes(titleId: String): Flow<List<EpisodeEntity>> = MutableStateFlow(episodes.filter { it.titleId == titleId })
    override fun observeAllEpisodes(): Flow<List<EpisodeEntity>> = throw UnsupportedOperationException()
    override suspend fun upsertAll(episodes: List<EpisodeEntity>) { updated += episodes }
    override suspend fun getById(id: String): EpisodeEntity? = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private class FakeFetcher(private val bySeason: Map<Pair<Int, Int>, List<MediaEpisode>>) : EpisodeMetadataFetcher {
    val calledSeasons = mutableListOf<Int>()
    override suspend fun fetchSeasonEpisodes(tmdbId: Int, seasonNumber: Int): List<MediaEpisode> {
        calledSeasons += seasonNumber
        return bySeason[tmdbId to seasonNumber].orEmpty()
    }
}

private object ThrowingSeasonDao : SeasonDao {
    override fun observeSeasons(titleId: String): Flow<List<SeasonEntity>> = throw UnsupportedOperationException()
    override fun observeAllSeasons(): Flow<List<SeasonEntity>> = throw UnsupportedOperationException()
    override suspend fun upsertAll(seasons: List<SeasonEntity>) = throw UnsupportedOperationException()
    override suspend fun findSeasonId(titleId: String, seasonNumber: Int): String? = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private object ThrowingEpisodeDao : EpisodeDao {
    override fun observeEpisodes(titleId: String): Flow<List<EpisodeEntity>> = throw UnsupportedOperationException()
    override fun observeAllEpisodes(): Flow<List<EpisodeEntity>> = throw UnsupportedOperationException()
    override suspend fun upsertAll(episodes: List<EpisodeEntity>) = throw UnsupportedOperationException()
    override suspend fun getById(id: String): EpisodeEntity? = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private object ThrowingFetcher : EpisodeMetadataFetcher {
    override suspend fun fetchSeasonEpisodes(tmdbId: Int, seasonNumber: Int) = throw UnsupportedOperationException()
}

private object NoWatchEventsDao2 : EpisodeWatchEventDao {
    override fun observeWatchCounts(titleId: String): Flow<List<EpisodeWatchCount>> = throw UnsupportedOperationException()
    override fun observeAllWatchEvents(): Flow<List<EpisodeWatchEventEntity>> = throw UnsupportedOperationException()
    override suspend fun upsertAll(events: List<EpisodeWatchEventEntity>) = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private object NoEpisodeRatingsDao2 : EpisodeRatingDao {
    override fun observeRatings(titleId: String): Flow<List<EpisodeRatingEntity>> = throw UnsupportedOperationException()
    override suspend fun upsertAll(ratings: List<EpisodeRatingEntity>) = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private object NoEpisodeReviewsDao2 : EpisodeReviewDao {
    override suspend fun upsertAll(reviews: List<EpisodeReviewEntity>) = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private object NoViewingDao2 : ViewingDao {
    override fun observeViewings(titleId: String): Flow<List<ViewingEntity>> = throw UnsupportedOperationException()
    override fun observeTotalViewingCount(): Flow<Int> = throw UnsupportedOperationException()
    override fun observeAllViewings(): Flow<List<ViewingEntity>> = throw UnsupportedOperationException()
    override suspend fun getById(id: String): ViewingEntity? = throw UnsupportedOperationException()
    override suspend fun getByOutingId(outingId: String): ViewingEntity? = throw UnsupportedOperationException()
    override suspend fun upsertAll(viewings: List<ViewingEntity>) = throw UnsupportedOperationException()
    override suspend fun upsert(viewing: ViewingEntity) = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private object NoCastDao2 : TitleCastDao {
    override fun observeAllCast(): Flow<List<TitleCastEntity>> = throw UnsupportedOperationException()
    override suspend fun upsertAll(rows: List<TitleCastEntity>) = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private object NoCrewDao2 : TitleCrewDao {
    override fun observeAllCrew(): Flow<List<TitleCrewEntity>> = throw UnsupportedOperationException()
    override suspend fun upsertAll(rows: List<TitleCrewEntity>) = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private object NoOutingsDao2 : CinemaOutingDao {
    override fun observeAllOutings(): Flow<List<CinemaOutingEntity>> = throw UnsupportedOperationException()
    override fun observeOutingsForTitle(titleId: String): Flow<List<CinemaOutingEntity>> = throw UnsupportedOperationException()
    override suspend fun getById(id: String): CinemaOutingEntity? = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
    override suspend fun upsertAll(rows: List<CinemaOutingEntity>) = throw UnsupportedOperationException()
    override suspend fun upsert(row: CinemaOutingEntity) = throw UnsupportedOperationException()
    override suspend fun getScheduledOutings() = throw UnsupportedOperationException()
}

private class RecordingOutboxDao2 : OutboxDao {
    val entries = mutableListOf<OutboxEntity>()
    override suspend fun enqueue(entry: OutboxEntity) { entries += entry }
    override fun observePending(): Flow<List<OutboxEntity>> = MutableStateFlow(entries)
    override suspend fun getPending(): List<OutboxEntity> = entries
    override suspend fun remove(id: String) { entries.removeAll { it.id == id } }
    override suspend fun recordFailure(id: String, error: String?) = Unit
}

private object NoopWriter2 : RemoteMutationWriter {
    override suspend fun push(entry: OutboxEntity): PushResult = PushResult.Success
}

private object NoopConflictHandler2 : ConflictHandler {
    override suspend fun applyRemote(entityType: String, entityId: String, serverPayload: JSONObject) = Unit
}
