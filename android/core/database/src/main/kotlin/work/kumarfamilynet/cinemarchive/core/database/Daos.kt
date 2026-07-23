package work.kumarfamilynet.cinemarchive.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Library-list projection — only the columns the poster wall needs (see
 *  docs/android-contracts/library.md §1). Deliberately not the full [TitleEntity] so the
 *  list query stays cheap regardless of how much detail the `titles` table accumulates. */
data class TitleListRow(
    val id: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val status: String,
    val type: String,
    val director: String?,
    val network: String?,
    val rating: Double?,
)

data class EpisodeWatchCount(val episodeId: String, val watchCount: Int)

@Dao
interface TitleDao {
    @Query(
        "SELECT id, title, year, posterUrl, status, type, director, network, rating " +
            "FROM titles ORDER BY title COLLATE NOCASE",
    )
    fun observeLibrary(): Flow<List<TitleListRow>>

    @Query("SELECT * FROM titles WHERE id = :titleId")
    fun observeTitle(titleId: String): Flow<TitleEntity?>

    // Ledger hero-stat rollup (docs/android-contracts/ledger.md) reads every title's
    // type/status/rating/runtime — small enough locally to just select the full row rather
    // than add a second bespoke projection alongside TitleListRow.
    @Query("SELECT * FROM titles")
    fun observeAllTitles(): Flow<List<TitleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(titles: List<TitleEntity>)

    @Query("UPDATE titles SET status = :status, updatedAt = :updatedAt WHERE id = :titleId")
    suspend fun updateStatus(titleId: String, status: String, updatedAt: String)

    @Query("UPDATE titles SET rating = :rating, updatedAt = :updatedAt WHERE id = :titleId")
    suspend fun updateRating(titleId: String, rating: Double, updatedAt: String)

    @Query("SELECT COUNT(*) FROM titles")
    suspend fun count(): Int

    @Query("DELETE FROM titles WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface SeasonDao {
    @Query("SELECT * FROM seasons WHERE titleId = :titleId ORDER BY seasonNumber")
    fun observeSeasons(titleId: String): Flow<List<SeasonEntity>>

    // Ledger's "Still Rolling" widget needs every TV title's episode-progress rollup, not
    // just one title's — same whole-library rationale as TitleDao.observeAllTitles().
    @Query("SELECT * FROM seasons")
    fun observeAllSeasons(): Flow<List<SeasonEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(seasons: List<SeasonEntity>)

    // LibrarySyncRepository's episode->season resolution: sync_library_changes' episode
    // payloads carry seasonNumber, not seasonId, so this looks up the local FK target.
    @Query("SELECT id FROM seasons WHERE titleId = :titleId AND seasonNumber = :seasonNumber LIMIT 1")
    suspend fun findSeasonId(titleId: String, seasonNumber: Int): String?

    @Query("DELETE FROM seasons WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE titleId = :titleId ORDER BY seasonId, episodeNumber")
    fun observeEpisodes(titleId: String): Flow<List<EpisodeEntity>>

    // Up Next's continue-watching progress and Ledger's Still Rolling widget both need the
    // real per-episode watch state across the whole library (seasons.episodesWatched is never
    // updated after a season's initial sync — see LibraryRepository.observeUpNext's kdoc — so
    // both roll their own count from this plus EpisodeWatchEventDao.observeAllWatchEvents()).
    @Query("SELECT * FROM episodes")
    fun observeAllEpisodes(): Flow<List<EpisodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(episodes: List<EpisodeEntity>)

    @Query("DELETE FROM episodes WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface EpisodeWatchEventDao {
    @Query(
        """
        SELECT episodeId, COUNT(*) as watchCount FROM episode_watch_events
        WHERE episodeId IN (SELECT id FROM episodes WHERE titleId = :titleId)
        GROUP BY episodeId
        """
    )
    fun observeWatchCounts(titleId: String): Flow<List<EpisodeWatchCount>>

    // Ledger's "The Marathon" streak widget folds episode watch events into its date
    // bucketing across the whole library (ledger.md §1), not scoped to one title.
    @Query("SELECT * FROM episode_watch_events")
    fun observeAllWatchEvents(): Flow<List<EpisodeWatchEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<EpisodeWatchEventEntity>)

    @Query("DELETE FROM episode_watch_events WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface EpisodeRatingDao {
    // Ordered newest-first so the repository can take the first row per episodeId as
    // "latest rating" without a SQLite window function (portable across API 31+ devices).
    @Query(
        """
        SELECT * FROM episode_ratings
        WHERE episodeId IN (SELECT id FROM episodes WHERE titleId = :titleId)
        ORDER BY ratedAt DESC
        """
    )
    fun observeRatings(titleId: String): Flow<List<EpisodeRatingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(ratings: List<EpisodeRatingEntity>)

    @Query("DELETE FROM episode_ratings WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface EpisodeReviewDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(reviews: List<EpisodeReviewEntity>)

    @Query("DELETE FROM episode_reviews WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ViewingDao {
    @Query("SELECT * FROM viewings WHERE titleId = :titleId ORDER BY date DESC")
    fun observeViewings(titleId: String): Flow<List<ViewingEntity>>

    @Query("SELECT COUNT(*) FROM viewings")
    fun observeTotalViewingCount(): Flow<Int>

    // Ledger's date-bucketed widgets (Activity, The Run, Screening Nights, Premieres &
    // Revivals, The Revival House, The Marathon, Shifting Standards, Encores, At the
    // Movies) all fold over every viewing in the library, same rationale as
    // TitleDao.observeAllTitles().
    @Query("SELECT * FROM viewings")
    fun observeAllViewings(): Flow<List<ViewingEntity>>

    @Query("SELECT * FROM viewings WHERE id = :id")
    suspend fun getById(id: String): ViewingEntity?

    // Completion-engine idempotency check (OutingsRepository.completeDueOutings): a re-run
    // after a process death between the viewing insert and the outing's status flip must not
    // insert a second viewing for the same trip.
    @Query("SELECT * FROM viewings WHERE outingId = :outingId LIMIT 1")
    suspend fun getByOutingId(outingId: String): ViewingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(viewings: List<ViewingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(viewing: ViewingEntity)

    @Query("DELETE FROM viewings WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface TitleCastDao {
    @Query("SELECT * FROM title_cast")
    fun observeAllCast(): Flow<List<TitleCastEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<TitleCastEntity>)
}

@Dao
interface TitleCrewDao {
    @Query("SELECT * FROM title_crew")
    fun observeAllCrew(): Flow<List<TitleCrewEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<TitleCrewEntity>)
}

@Dao
interface CinemaOutingDao {
    @Query("SELECT * FROM cinema_outings")
    fun observeAllOutings(): Flow<List<CinemaOutingEntity>>

    @Query("SELECT * FROM cinema_outings WHERE titleId = :titleId")
    fun observeOutingsForTitle(titleId: String): Flow<List<CinemaOutingEntity>>

    @Query("SELECT * FROM cinema_outings WHERE id = :id")
    suspend fun getById(id: String): CinemaOutingEntity?

    @Query("DELETE FROM cinema_outings WHERE id = :id")
    suspend fun deleteById(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<CinemaOutingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CinemaOutingEntity)

    // Deliberately not filtered by endsAt <= :now in SQL: Instant#toString()'s fractional-
    // second width varies (no decimal point at all when nanos == 0, else 3/6/9 digits), which
    // breaks lexicographic string comparison at exactly the boundary this query cares about.
    // The scheduled list is small (one user's own trips), so comparing via Instant.parse() in
    // Kotlin (OutingsRepository.completeDueOutings) is simpler than guaranteeing a fixed-width
    // timestamp format everywhere just to make `<=` safe in SQLite.
    @Query("SELECT * FROM cinema_outings WHERE status = 'SCHEDULED'")
    suspend fun getScheduledOutings(): List<CinemaOutingEntity>
}
