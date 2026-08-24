package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
import work.kumarfamilynet.cinemarchive.core.database.TheaterInterestDao
import work.kumarfamilynet.cinemarchive.core.database.TheaterInterestEntity
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

/**
 * Covers `LibraryRepository.removeTitle` — the local hard delete and the single `title`/`delete`
 * outbox entry that carries it to Supabase (pushed by `SupabaseRemoteMutationWriter.deleteTitle`).
 * Only [TitleDao] and the outbox are exercised: every other Room table cascades out locally via
 * `ON DELETE CASCADE` (see `Entities.kt`), so `removeTitle` itself never touches those DAOs.
 */
class LibraryRepositoryRemoveTitleTest {

    private val titleDao = RemoveTitleTitleDao()
    private val outboxDao = RecordingOutboxDaoRm()

    private val repository = LibraryRepository(
        titleDao = titleDao,
        seasonDao = NoSeasonsDao,
        episodeDao = NoEpisodesDao,
        watchEventDao = NoWatchEventsDaoRm,
        ratingDao = NoRatingsDao2,
        reviewDao = NoReviewsDao2,
        viewingDao = NoViewingsDao,
        cinemaOutingDao = NoOutingsDaoRm,
        titleCastDao = NoCastDao,
        titleCrewDao = NoCrewDao,
        theaterInterestDao = NoTheaterInterestDaoRm,
        outbox = MutationOutbox(outboxDao, NoopWriterRm, NoopConflictHandlerRm),
        episodeMetadataFetcher = NoEpisodeMetadataFetcher2,
    )

    @Test
    fun `deletes the title row locally`() = runTest {
        titleDao.rows += TitleEntity(
            id = "title-1", tmdbId = 27205, type = "MOVIE", title = "Inception", year = 2010,
            director = null, genres = emptyList(), posterUrl = null, backdropUrl = null,
            synopsis = null, runtime = null, network = null, status = "WATCHED", rating = null,
            notes = null, addedAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
            imdbRating = null, originalLanguage = null, releaseDate = "2010-07-15",
        )

        repository.removeTitle("title-1")

        assertEquals(listOf("title-1"), titleDao.deleted)
    }

    @Test
    fun `enqueues exactly one delete outbox entry carrying the title id`() = runTest {
        repository.removeTitle("title-1")

        val entry = outboxDao.entries.single()
        assertEquals("title", entry.entityType)
        assertEquals("title-1", entry.entityId)
        assertEquals("delete", entry.operation)
        assertEquals("title-1", JSONObject(entry.payloadJson).getString("id"))
    }
}

private class RemoveTitleTitleDao : TitleDao {
    val rows = mutableListOf<TitleEntity>()
    val deleted = mutableListOf<String>()
    override fun observeLibrary(): Flow<List<TitleListRow>> = throw UnsupportedOperationException()
    override fun observeLastInteractions(): Flow<List<TitleLastInteraction>> = throw UnsupportedOperationException()
    override fun observeTitle(titleId: String): Flow<TitleEntity?> = throw UnsupportedOperationException()
    override fun observeLibraryTmdbIds(): Flow<List<Int>> = throw UnsupportedOperationException()
    override fun observeLibraryTitleIdsByTmdbKey(): Flow<List<TitleIdByTmdbKey>> = throw UnsupportedOperationException()
    override suspend fun findIdByTmdbKey(tmdbId: Int, type: String): String? = throw UnsupportedOperationException()
    override suspend fun getById(id: String): TitleEntity? = rows.firstOrNull { it.id == id }
    override fun observeAllTitles(): Flow<List<TitleEntity>> = MutableStateFlow(rows)
    override suspend fun upsertAll(titles: List<TitleEntity>) = throw UnsupportedOperationException()
    override suspend fun updateStatus(titleId: String, status: String, updatedAt: String) = throw UnsupportedOperationException()
    override suspend fun updateRating(titleId: String, rating: Double, updatedAt: String) = throw UnsupportedOperationException()
    override suspend fun count(): Int = rows.size
    override suspend fun deleteById(id: String) {
        rows.removeAll { it.id == id }
        deleted += id
    }
}

private class RecordingOutboxDaoRm : OutboxDao {
    val entries = mutableListOf<OutboxEntity>()
    override suspend fun enqueue(entry: OutboxEntity) { entries += entry }
    override fun observePending(): Flow<List<OutboxEntity>> = MutableStateFlow(entries)
    override suspend fun getPending(): List<OutboxEntity> = entries
    override suspend fun remove(id: String) { entries.removeAll { it.id == id } }
    override suspend fun recordFailure(id: String, error: String?) = Unit
}

private object NoopWriterRm : RemoteMutationWriter {
    override suspend fun push(entry: OutboxEntity): PushResult = PushResult.Success
}

private object NoopConflictHandlerRm : ConflictHandler {
    override suspend fun applyRemote(entityType: String, entityId: String, serverPayload: JSONObject) = Unit
}

private object NoSeasonsDao : SeasonDao {
    override fun observeSeasons(titleId: String): Flow<List<SeasonEntity>> = throw UnsupportedOperationException()
    override fun observeAllSeasons(): Flow<List<SeasonEntity>> = throw UnsupportedOperationException()
    override suspend fun upsertAll(seasons: List<SeasonEntity>) = throw UnsupportedOperationException()
    override suspend fun findSeasonId(titleId: String, seasonNumber: Int): String? = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private object NoEpisodesDao : EpisodeDao {
    override fun observeEpisodes(titleId: String): Flow<List<EpisodeEntity>> = throw UnsupportedOperationException()
    override fun observeAllEpisodes(): Flow<List<EpisodeEntity>> = throw UnsupportedOperationException()
    override suspend fun upsertAll(episodes: List<EpisodeEntity>) = throw UnsupportedOperationException()
    override suspend fun getById(id: String): EpisodeEntity? = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private object NoWatchEventsDaoRm : EpisodeWatchEventDao {
    override fun observeWatchCounts(titleId: String): Flow<List<EpisodeWatchCount>> = throw UnsupportedOperationException()
    override fun observeAllWatchEvents(): Flow<List<EpisodeWatchEventEntity>> = throw UnsupportedOperationException()
    override suspend fun upsertAll(events: List<EpisodeWatchEventEntity>) = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private object NoRatingsDao2 : EpisodeRatingDao {
    override fun observeRatings(titleId: String): Flow<List<EpisodeRatingEntity>> = throw UnsupportedOperationException()
    override suspend fun upsertAll(ratings: List<EpisodeRatingEntity>) = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private object NoReviewsDao2 : EpisodeReviewDao {
    override suspend fun upsertAll(reviews: List<EpisodeReviewEntity>) = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private object NoViewingsDao : ViewingDao {
    override fun observeViewings(titleId: String): Flow<List<ViewingEntity>> = throw UnsupportedOperationException()
    override fun observeTotalViewingCount(): Flow<Int> = throw UnsupportedOperationException()
    override fun observeAllViewings(): Flow<List<ViewingEntity>> = throw UnsupportedOperationException()
    override suspend fun getById(id: String): ViewingEntity? = throw UnsupportedOperationException()
    override suspend fun getByOutingId(outingId: String): ViewingEntity? = throw UnsupportedOperationException()
    override suspend fun upsertAll(viewings: List<ViewingEntity>) = throw UnsupportedOperationException()
    override suspend fun upsert(viewing: ViewingEntity) = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private object NoOutingsDaoRm : CinemaOutingDao {
    override fun observeAllOutings(): Flow<List<CinemaOutingEntity>> = throw UnsupportedOperationException()
    override fun observeOutingsForTitle(titleId: String): Flow<List<CinemaOutingEntity>> = throw UnsupportedOperationException()
    override suspend fun getById(id: String): CinemaOutingEntity? = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
    override suspend fun upsertAll(rows: List<CinemaOutingEntity>) = throw UnsupportedOperationException()
    override suspend fun upsert(row: CinemaOutingEntity) = throw UnsupportedOperationException()
    override suspend fun getScheduledOutings(): List<CinemaOutingEntity> = throw UnsupportedOperationException()
}

private object NoCastDao : TitleCastDao {
    override fun observeAllCast(): Flow<List<TitleCastEntity>> = throw UnsupportedOperationException()
    override suspend fun upsertAll(rows: List<TitleCastEntity>) = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private object NoCrewDao : TitleCrewDao {
    override fun observeAllCrew(): Flow<List<TitleCrewEntity>> = throw UnsupportedOperationException()
    override suspend fun upsertAll(rows: List<TitleCrewEntity>) = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private object NoTheaterInterestDaoRm : TheaterInterestDao {
    override fun observeAll(): Flow<List<TheaterInterestEntity>> = throw UnsupportedOperationException()
    override fun observeIsInterested(titleId: String): Flow<Boolean> = throw UnsupportedOperationException()
    override suspend fun upsert(row: TheaterInterestEntity) = throw UnsupportedOperationException()
    override suspend fun deleteByTitleId(titleId: String) = throw UnsupportedOperationException()
}

private object NoEpisodeMetadataFetcher2 : EpisodeMetadataFetcher {
    override suspend fun fetchSeasonEpisodes(tmdbId: Int, seasonNumber: Int) = throw UnsupportedOperationException()
}
