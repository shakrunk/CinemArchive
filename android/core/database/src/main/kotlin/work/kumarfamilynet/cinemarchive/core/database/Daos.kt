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
)

data class EpisodeWatchCount(val episodeId: String, val watchCount: Int)

@Dao
interface TitleDao {
    @Query("SELECT id, title, year, posterUrl, status FROM titles ORDER BY title COLLATE NOCASE")
    fun observeLibrary(): Flow<List<TitleListRow>>

    @Query("SELECT * FROM titles WHERE id = :titleId")
    fun observeTitle(titleId: String): Flow<TitleEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(titles: List<TitleEntity>)

    @Query("SELECT COUNT(*) FROM titles")
    suspend fun count(): Int
}

@Dao
interface SeasonDao {
    @Query("SELECT * FROM seasons WHERE titleId = :titleId ORDER BY seasonNumber")
    fun observeSeasons(titleId: String): Flow<List<SeasonEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(seasons: List<SeasonEntity>)
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE titleId = :titleId ORDER BY seasonId, episodeNumber")
    fun observeEpisodes(titleId: String): Flow<List<EpisodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(episodes: List<EpisodeEntity>)
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<EpisodeWatchEventEntity>)
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
}

@Dao
interface ViewingDao {
    @Query("SELECT * FROM viewings WHERE titleId = :titleId ORDER BY date DESC")
    fun observeViewings(titleId: String): Flow<List<ViewingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(viewings: List<ViewingEntity>)
}
