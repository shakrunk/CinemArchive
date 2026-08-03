package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingDao
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
import work.kumarfamilynet.cinemarchive.core.model.AddTitleRequest
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaCredit
import work.kumarfamilynet.cinemarchive.core.model.MediaCrewCredit
import work.kumarfamilynet.cinemarchive.core.model.MediaDetails
import work.kumarfamilynet.cinemarchive.core.model.MediaEpisode
import work.kumarfamilynet.cinemarchive.core.model.MediaSeason
import work.kumarfamilynet.cinemarchive.core.model.MediaType

// --- Recording fakes: unlike LedgerRepositoryTest's read-only ones, these keep what was
// written so the assertions can inspect it. Only the methods addTitle exercises are backed;
// everything else throws, same "implement what's exercised" precedent as the rest of this
// module's tests.

private class AddTitleTitleDao(private val existing: MutableMap<Pair<Int, String>, String> = mutableMapOf()) : TitleDao {
    val written = mutableListOf<TitleEntity>()
    override fun observeLibrary(): Flow<List<TitleListRow>> = throw UnsupportedOperationException()
    override fun observeLastInteractions(): Flow<List<TitleLastInteraction>> = throw UnsupportedOperationException()
    override fun observeTitle(titleId: String): Flow<TitleEntity?> = throw UnsupportedOperationException()
    override fun observeLibraryTmdbIds(): Flow<List<Int>> = throw UnsupportedOperationException()
    override fun observeLibraryTitleIdsByTmdbKey(): Flow<List<TitleIdByTmdbKey>> = throw UnsupportedOperationException()
    override suspend fun findIdByTmdbKey(tmdbId: Int, type: String): String? = existing[tmdbId to type]
    override suspend fun getById(id: String): TitleEntity? = written.firstOrNull { it.id == id }
    override fun observeAllTitles(): Flow<List<TitleEntity>> = MutableStateFlow(written)
    override suspend fun upsertAll(titles: List<TitleEntity>) { written += titles }
    override suspend fun updateStatus(titleId: String, status: String, updatedAt: String) = throw UnsupportedOperationException()
    override suspend fun updateRating(titleId: String, rating: Double, updatedAt: String) = throw UnsupportedOperationException()
    override suspend fun count(): Int = written.size
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private class RecordingSeasonDao : SeasonDao {
    val written = mutableListOf<SeasonEntity>()
    override fun observeSeasons(titleId: String): Flow<List<SeasonEntity>> = throw UnsupportedOperationException()
    override fun observeAllSeasons(): Flow<List<SeasonEntity>> = MutableStateFlow(written)
    override suspend fun upsertAll(seasons: List<SeasonEntity>) { written += seasons }
    override suspend fun findSeasonId(titleId: String, seasonNumber: Int): String? =
        written.firstOrNull { it.titleId == titleId && it.seasonNumber == seasonNumber }?.id
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private class RecordingEpisodeDao : EpisodeDao {
    val written = mutableListOf<EpisodeEntity>()
    override fun observeEpisodes(titleId: String): Flow<List<EpisodeEntity>> = throw UnsupportedOperationException()
    override fun observeAllEpisodes(): Flow<List<EpisodeEntity>> = MutableStateFlow(written)
    override suspend fun upsertAll(episodes: List<EpisodeEntity>) { written += episodes }
    override suspend fun getById(id: String): EpisodeEntity? = written.firstOrNull { it.id == id }
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private class RecordingViewingDao : ViewingDao {
    val written = mutableListOf<ViewingEntity>()
    override fun observeViewings(titleId: String): Flow<List<ViewingEntity>> = throw UnsupportedOperationException()
    override fun observeTotalViewingCount(): Flow<Int> = throw UnsupportedOperationException()
    override fun observeAllViewings(): Flow<List<ViewingEntity>> = MutableStateFlow(written)
    override suspend fun getById(id: String): ViewingEntity? = written.firstOrNull { it.id == id }
    override suspend fun getByOutingId(outingId: String): ViewingEntity? = throw UnsupportedOperationException()
    override suspend fun upsertAll(viewings: List<ViewingEntity>) { written += viewings }
    override suspend fun upsert(viewing: ViewingEntity) { written += viewing }
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private class RecordingCastDao : TitleCastDao {
    val written = mutableListOf<TitleCastEntity>()
    override fun observeAllCast(): Flow<List<TitleCastEntity>> = MutableStateFlow(written)
    override suspend fun upsertAll(rows: List<TitleCastEntity>) { written += rows }
    override suspend fun deleteById(id: String) { written.removeAll { it.id == id } }
}

private class RecordingCrewDao : TitleCrewDao {
    val written = mutableListOf<TitleCrewEntity>()
    override fun observeAllCrew(): Flow<List<TitleCrewEntity>> = MutableStateFlow(written)
    override suspend fun upsertAll(rows: List<TitleCrewEntity>) { written += rows }
    override suspend fun deleteById(id: String) { written.removeAll { it.id == id } }
}

private class RecordingOutboxDao : OutboxDao {
    val entries = mutableListOf<OutboxEntity>()
    override suspend fun enqueue(entry: OutboxEntity) { entries += entry }
    override fun observePending(): Flow<List<OutboxEntity>> = MutableStateFlow(entries)
    override suspend fun getPending(): List<OutboxEntity> = entries
    override suspend fun remove(id: String) { entries.removeAll { it.id == id } }
    override suspend fun recordFailure(id: String, error: String?) = Unit
}

// addTitle never touches the episode watch/rating/review logs — a new title has no episode
// history yet — so these stay unimplemented rather than pretending to store anything.
private object NoWatchEventsDao : EpisodeWatchEventDao {
    override fun observeWatchCounts(titleId: String): Flow<List<EpisodeWatchCount>> = throw UnsupportedOperationException()
    override fun observeAllWatchEvents(): Flow<List<EpisodeWatchEventEntity>> = throw UnsupportedOperationException()
    override suspend fun upsertAll(events: List<EpisodeWatchEventEntity>) = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private object NoEpisodeRatingsDao : EpisodeRatingDao {
    override fun observeRatings(titleId: String): Flow<List<EpisodeRatingEntity>> = throw UnsupportedOperationException()
    override suspend fun upsertAll(ratings: List<EpisodeRatingEntity>) = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private object NoEpisodeReviewsDao : EpisodeReviewDao {
    override suspend fun upsertAll(reviews: List<EpisodeReviewEntity>) = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

/**
 * Covers `LibraryRepository.addTitle` — the Room write and the single outbox entry that
 * carries it to Supabase.
 *
 * The outbox payload is asserted directly rather than through a fake writer: it's the contract
 * between the two halves of the add (see `SupabaseRemoteMutationWriter.insertTitle`), it's the
 * only record of the write if the app dies before flushing, and a missing key there is silent —
 * it would surface as a column quietly absent on the web app days later.
 */
class LibraryRepositoryAddTitleTest {

    private val castDao = RecordingCastDao()
    private val crewDao = RecordingCrewDao()
    private val outboxDao = RecordingOutboxDao()

    private fun repository(
        titleDao: AddTitleTitleDao = AddTitleTitleDao(),
        seasonDao: RecordingSeasonDao = RecordingSeasonDao(),
        episodeDao: RecordingEpisodeDao = RecordingEpisodeDao(),
        viewingDao: RecordingViewingDao = RecordingViewingDao(),
    ): LibraryRepository {
        return LibraryRepository(
            titleDao = titleDao,
            seasonDao = seasonDao,
            episodeDao = episodeDao,
            watchEventDao = NoWatchEventsDao,
            ratingDao = NoEpisodeRatingsDao,
            reviewDao = NoEpisodeReviewsDao,
            viewingDao = viewingDao,
            cinemaOutingDao = NoOutingsDao,
            titleCastDao = castDao,
            titleCrewDao = crewDao,
            outbox = MutationOutbox(outboxDao, NoopWriter, NoopConflictHandler),
        )
    }

    private val movie = MediaDetails(
        tmdbId = 27205,
        type = MediaType.MOVIE,
        title = "Inception",
        year = 2010,
        releaseDate = "2010-07-15",
        director = "Christopher Nolan",
        genres = listOf("Action", "Science Fiction"),
        posterUrl = "https://poster",
        backdropUrl = "https://backdrop",
        synopsis = "A thief…",
        runtime = 148,
        network = null,
        originalLanguage = "en",
        contentRating = "PG-13",
        imdbId = "tt1375666",
        studios = listOf("Syncopy"),
        collectionId = 448150,
        collectionName = "Inception Collection",
        imdbRating = 8.8,
        rtScore = 87,
        metacriticScore = 74,
        cast = listOf(MediaCredit(6193, "Leonardo DiCaprio", "Cobb", 0)),
        crew = listOf(MediaCrewCredit(525, "Christopher Nolan", "Director", "Directing")),
    )

    private val series = MediaDetails(
        tmdbId = 1396,
        type = MediaType.TV,
        title = "Breaking Bad",
        year = 2008,
        releaseDate = "2008-01-20",
        director = null,
        genres = listOf("Drama"),
        posterUrl = null,
        backdropUrl = null,
        synopsis = null,
        runtime = null,
        network = "AMC",
        originalLanguage = "en",
        contentRating = "TV-MA",
        imdbId = "tt0903747",
        seasons = listOf(
            MediaSeason(1, 2, 2008, listOf(MediaEpisode(1, "Pilot", "2008-01-20", 58), MediaEpisode(2, "Cat's in the Bag...", "2008-01-27", 48))),
            MediaSeason(2, 1, 2009, listOf(MediaEpisode(1, "Seven Thirty-Seven", "2009-03-08", 47))),
        ),
    )

    @Test
    fun `adding a movie writes the title row and one outbox entry`() = runTest {
        val titleDao = AddTitleTitleDao()
        val repo = repository(titleDao = titleDao)

        val id = repo.addTitle(AddTitleRequest(movie, LibraryStatus.WATCHLIST, rating = null, notes = null))

        val written = titleDao.written.single()
        assertEquals(id, written.id)
        assertEquals(27205, written.tmdbId)
        assertEquals("MOVIE", written.type)
        assertEquals("WATCHLIST", written.status)
        assertEquals(148, written.runtime)
        assertEquals(2010, written.year)
        assertEquals("2010-07-15", written.releaseDate)
        assertEquals(8.8, written.imdbRating!!, 0.001)
        assertEquals(listOf("Action", "Science Fiction"), written.genres)
        assertNull(written.rating)
        assertEquals(1, outboxDao.entries.size)
        assertEquals("title", outboxDao.entries.single().entityType)
        assertEquals("insert", outboxDao.entries.single().operation)
    }

    /** Fields with no local Room column still have to reach the server, or a title added on the
     *  phone renders permanently poorer on the web than the same title added there. */
    @Test
    fun `outbox payload carries fields the local mirror has no column for`() = runTest {
        val repo = repository()

        repo.addTitle(AddTitleRequest(movie, LibraryStatus.WATCHED, rating = 4.5, notes = "rewatch", watchedOn = "2026-07-25"))

        val payload = JSONObject(outboxDao.entries.single().payloadJson)
        assertEquals("PG-13", payload.getString("contentRating"))
        assertEquals("tt1375666", payload.getString("imdbId"))
        assertEquals(87, payload.getInt("rtScore"))
        assertEquals(74, payload.getInt("metacriticScore"))
        assertEquals("Syncopy", payload.getJSONArray("studios").getString(0))
        assertEquals(448150, payload.getInt("collectionId"))
        assertEquals("Inception Collection", payload.getString("collectionName"))
        assertEquals("WATCHED", payload.getString("status"))
        assertEquals(4.5, payload.getDouble("rating"), 0.001)
        assertEquals("rewatch", payload.getString("notes"))
    }

    /** `titles.year` is `not null` server-side while TMDB genuinely omits dates for some
     *  entries — the push substitutes 0, the local mirror stays honest. */
    @Test
    fun `an unknown year goes up as zero and stays null locally`() = runTest {
        val titleDao = AddTitleTitleDao()
        val repo = repository(titleDao = titleDao)

        repo.addTitle(AddTitleRequest(movie.copy(year = null), LibraryStatus.WATCHLIST, null, null))

        assertNull(titleDao.written.single().year)
        assertEquals(0, JSONObject(outboxDao.entries.single().payloadJson).getInt("year"))
    }

    @Test
    fun `adding a series writes seasons and episodes linked to it`() = runTest {
        val titleDao = AddTitleTitleDao()
        val seasonDao = RecordingSeasonDao()
        val episodeDao = RecordingEpisodeDao()
        val repo = repository(titleDao = titleDao, seasonDao = seasonDao, episodeDao = episodeDao)

        val id = repo.addTitle(AddTitleRequest(series, LibraryStatus.WATCHING, null, null))

        assertEquals(listOf(1, 2), seasonDao.written.map { it.seasonNumber })
        assertTrue(seasonDao.written.all { it.titleId == id && it.episodesWatched == 0 })
        assertEquals(3, episodeDao.written.size)
        assertTrue(episodeDao.written.all { it.titleId == id })
        // Each episode's local FK points at its own season's generated id.
        val seasonOneId = seasonDao.written.first { it.seasonNumber == 1 }.id
        assertEquals(2, episodeDao.written.count { it.seasonId == seasonOneId })
        assertNull(titleDao.written.single().runtime)
    }

    /** The remote `episodes` table is keyed by (title_id, season_number, episode_number) and has
     *  no season_id column at all, so the payload has to translate the local FK back. */
    @Test
    fun `episode payload carries season numbers, not local season ids`() = runTest {
        val repo = repository()

        repo.addTitle(AddTitleRequest(series, LibraryStatus.WATCHING, null, null))

        val episodes = JSONObject(outboxDao.entries.single().payloadJson).getJSONArray("episodes")
        val pairs = (0 until episodes.length()).map {
            episodes.getJSONObject(it).getInt("seasonNumber") to episodes.getJSONObject(it).getInt("episodeNumber")
        }
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), pairs)
    }

    @Test
    fun `a watched title seeds its first viewing`() = runTest {
        val viewingDao = RecordingViewingDao()
        val repo = repository(viewingDao = viewingDao)

        val id = repo.addTitle(AddTitleRequest(movie, LibraryStatus.WATCHED, rating = 5.0, notes = "great", watchedOn = "2026-07-25"))

        val viewing = viewingDao.written.single()
        assertEquals(id, viewing.titleId)
        assertEquals("2026-07-25", viewing.date)
        assertEquals(5.0, viewing.rating!!, 0.001)
        assertNotNull(JSONObject(outboxDao.entries.single().payloadJson).optJSONObject("viewing"))
    }

    @Test
    fun `a watchlist title seeds no viewing`() = runTest {
        val viewingDao = RecordingViewingDao()
        val repo = repository(viewingDao = viewingDao)

        repo.addTitle(AddTitleRequest(movie, LibraryStatus.WATCHLIST, null, null, watchedOn = "2026-07-25"))

        assertTrue(viewingDao.written.isEmpty())
        assertNull(JSONObject(outboxDao.entries.single().payloadJson).optJSONObject("viewing"))
    }

    @Test
    fun `cast and crew are written for the Ledger's ensemble and auteur tallies`() = runTest {
        val repo = repository()

        val id = repo.addTitle(AddTitleRequest(movie, LibraryStatus.WATCHLIST, null, null))

        assertEquals(listOf("Leonardo DiCaprio"), castDao.written.map { it.name })
        assertEquals(0, castDao.written.single().castOrder)
        assertEquals(listOf("Director"), crewDao.written.map { it.job })
        assertTrue(castDao.written.all { it.titleId == id } && crewDao.written.all { it.titleId == id })
    }

    /** TMDB bills whole ensembles; the payload is one JSON blob in a Room row, and only the
     *  top five matter to anything that reads it. */
    @Test
    fun `cast is capped`() = runTest {
        val crowd = (0 until 60).map { MediaCredit(it, "Actor $it", null, it) }
        val repo = repository()

        repo.addTitle(AddTitleRequest(movie.copy(cast = crowd), LibraryStatus.WATCHLIST, null, null))

        assertEquals(MAX_CAST_ROWS, castDao.written.size)
        assertEquals("Actor 0", castDao.written.first().name)
    }

    /** Mirrors the server's own `unique_user_tmdb` constraint, so the duplicate is refused here
     *  rather than surfacing later as a push that can never succeed. */
    @Test
    fun `re-adding an owned title returns the existing row without writing`() = runTest {
        val titleDao = AddTitleTitleDao(mutableMapOf((27205 to "MOVIE") to "existing-id"))
        val repo = repository(titleDao = titleDao)

        val id = repo.addTitle(AddTitleRequest(movie, LibraryStatus.WATCHED, 5.0, null))

        assertEquals("existing-id", id)
        assertTrue(titleDao.written.isEmpty())
        assertTrue(outboxDao.entries.isEmpty())
    }

    /** The same TMDB id can legitimately belong to both a film and a series, and the unique
     *  constraint is on (user, tmdb_id, type) — so the guard must not collapse them. */
    @Test
    fun `the duplicate guard is scoped by media type`() = runTest {
        val titleDao = AddTitleTitleDao(mutableMapOf((1396 to "MOVIE") to "the-movie"))
        val repo = repository(titleDao = titleDao)

        val id = repo.addTitle(AddTitleRequest(series, LibraryStatus.WATCHING, null, null))

        assertTrue(id != "the-movie")
        assertEquals("TV", titleDao.written.single().type)
    }
}

private object NoopWriter : RemoteMutationWriter {
    override suspend fun push(entry: OutboxEntity): PushResult = PushResult.Success
}

private object NoopConflictHandler : ConflictHandler {
    override suspend fun applyRemote(entityType: String, entityId: String, serverPayload: JSONObject) = Unit
}

private object NoOutingsDao : CinemaOutingDao {
    override fun observeAllOutings(): Flow<List<work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity>> =
        MutableStateFlow(emptyList())
    override fun observeOutingsForTitle(titleId: String): Flow<List<work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity>> =
        MutableStateFlow(emptyList())
    override suspend fun getById(id: String) = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
    override suspend fun upsertAll(rows: List<work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity>) =
        throw UnsupportedOperationException()
    override suspend fun upsert(row: work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity) =
        throw UnsupportedOperationException()
    override suspend fun getScheduledOutings() = throw UnsupportedOperationException()
}
