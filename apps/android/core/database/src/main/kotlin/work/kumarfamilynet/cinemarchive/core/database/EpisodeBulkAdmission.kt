package work.kumarfamilynet.cinemarchive.core.database

import androidx.room.*

/** Account-database admission receipt: replaying a restored form never recreates deleted history. */
@Entity(tableName = "episode_bulk_admissions")
data class EpisodeBulkAdmissionEntity(@PrimaryKey val operationId: String, val payloadJson: String)

@Dao
interface EpisodeBulkAdmissionDao {
    @Query("SELECT * FROM episode_bulk_admissions ORDER BY operationId")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<EpisodeBulkAdmissionEntity>>
    @Query("SELECT * FROM episode_bulk_admissions ORDER BY operationId")
    suspend fun all(): List<EpisodeBulkAdmissionEntity>
    @Query("SELECT payloadJson FROM episode_bulk_admissions WHERE operationId = :id")
    suspend fun payload(id: String): String?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(receipt: EpisodeBulkAdmissionEntity)
    @Query("UPDATE mutation_outbox SET id = :newId, operation = :operation, payloadJson = :payload, attemptCount = 0, lastError = NULL WHERE id = :id AND entityType = 'episode_bulk' AND operation = 'review' AND payloadJson = :original")
    suspend fun replaceReviewed(id: String, original: String, newId: String, operation: String, payload: String): Int
}
