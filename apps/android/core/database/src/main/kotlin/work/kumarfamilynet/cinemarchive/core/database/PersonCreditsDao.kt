package work.kumarfamilynet.cinemarchive.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class LibraryPersonRow(val titleId: String, val tmdbPersonId: Int, val name: String)

@Dao
interface PersonCreditsDao {
    @Upsert suspend fun upsertSeasonCast(rows: List<SeasonCastEntity>)
    @Upsert suspend fun upsertEpisodeCrew(rows: List<EpisodeCrewEntity>)
    @Query("DELETE FROM season_cast WHERE id = :id") suspend fun deleteSeasonCast(id: String)
    @Query("DELETE FROM episode_crew WHERE id = :id") suspend fun deleteEpisodeCrew(id: String)
    @Query("SELECT * FROM season_cast") fun observeSeasonCast(): Flow<List<SeasonCastEntity>>
    @Query("SELECT * FROM episode_crew") fun observeEpisodeCrew(): Flow<List<EpisodeCrewEntity>>
    @Query("SELECT EXISTS(SELECT 1 FROM seasons WHERE id = :seasonId AND titleId = :titleId)")
    suspend fun hasSeason(titleId: String, seasonId: String): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM episodes WHERE id = :episodeId AND titleId = :titleId)")
    suspend fun hasEpisode(titleId: String, episodeId: String): Boolean

    @Query("""
        SELECT titleId, tmdbPersonId, name FROM title_cast
        UNION SELECT titleId, tmdbPersonId, name FROM title_crew
        UNION SELECT titleId, tmdbPersonId, name FROM season_cast
        UNION SELECT titleId, tmdbPersonId, name FROM episode_crew
        ORDER BY titleId, name, tmdbPersonId
    """)
    fun observeLibraryPeople(): Flow<List<LibraryPersonRow>>
}
