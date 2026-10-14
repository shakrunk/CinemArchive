package work.kumarfamilynet.cinemarchive.core.database

import androidx.room.*

/** Survives queue ACK and later deletion, so a restored form cannot recreate a saved trip. */
@Entity(tableName = "outing_schedule_admissions")
data class OutingScheduleAdmissionEntity(@PrimaryKey val operationId: String, val payloadJson: String)

@Dao
interface OutingScheduleAdmissionDao {
    @Query("SELECT payloadJson FROM outing_schedule_admissions WHERE operationId = :id")
    suspend fun payload(id: String): String?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(receipt: OutingScheduleAdmissionEntity)
}
