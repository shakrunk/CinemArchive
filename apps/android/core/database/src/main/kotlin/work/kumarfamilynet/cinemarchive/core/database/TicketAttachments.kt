package work.kumarfamilynet.cinemarchive.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** No outing FK: REPLACE-based ordinary outing refreshes must not cascade away private bytes
 * or managed clears. Immutable records also survive deletion for pending-command recovery. */
@Entity(tableName = "ticket_originals", primaryKeys = ["projectId", "ownerId", "attachmentId"])
data class TicketOriginalEntity(
    val projectId: String,
    val ownerId: String,
    val attachmentId: String,
    val outingId: String,
    val descriptorJson: String,
    val savedAt: Long,
)

/** Row presence means managed, including explicit null. Absence retains legacy behavior. */
@Entity(tableName = "ticket_associations", primaryKeys = ["projectId", "ownerId", "outingId"])
data class TicketAssociationEntity(
    val projectId: String,
    val ownerId: String,
    val outingId: String,
    val attachmentId: String?,
)

/** Kept after ACK so an operation ID cannot later be reused for different captured intent. */
@Entity(tableName = "ticket_intents")
data class TicketIntentEntity(
    @PrimaryKey val operationId: String,
    val projectId: String,
    val ownerId: String,
    val outingId: String,
    val payloadJson: String,
    val createdAt: Long,
    val acknowledgedAt: Long? = null,
)

@Dao
interface TicketAttachmentDao {
    @Query("SELECT * FROM ticket_originals WHERE projectId = :project AND ownerId = :owner AND attachmentId = :id")
    suspend fun original(project: String, owner: String, id: String): TicketOriginalEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOriginal(original: TicketOriginalEntity)

    @Query("SELECT * FROM ticket_associations WHERE projectId = :project AND ownerId = :owner AND outingId = :outing")
    suspend fun association(project: String, owner: String, outing: String): TicketAssociationEntity?

    @Query("SELECT * FROM ticket_associations WHERE projectId = :project AND ownerId = :owner AND outingId = :outing")
    fun observeAssociation(project: String, owner: String, outing: String): Flow<TicketAssociationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAssociation(association: TicketAssociationEntity)

    @Query("SELECT * FROM ticket_intents WHERE operationId = :id")
    suspend fun intent(id: String): TicketIntentEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIntent(intent: TicketIntentEntity)

    @Query("SELECT * FROM mutation_outbox WHERE id = :id")
    suspend fun queued(id: String): OutboxEntity?

    /** Unlike general enqueue, a duplicate operation must never replace its immutable payload. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCommand(entry: OutboxEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM cinema_outings WHERE id = :id)")
    suspend fun outingExists(id: String): Boolean

    @Query("SELECT * FROM cinema_outings WHERE id = :id")
    suspend fun outing(id: String): CinemaOutingEntity?

    @Query("SELECT * FROM mutation_outbox ORDER BY rowid ASC")
    suspend fun pending(): List<OutboxEntity>

    @Query("SELECT * FROM ticket_associations WHERE projectId = :project AND ownerId = :owner ORDER BY outingId")
    suspend fun associations(project: String, owner: String): List<TicketAssociationEntity>

    @Query("SELECT * FROM ticket_intents WHERE projectId = :project AND ownerId = :owner ORDER BY operationId")
    suspend fun intents(project: String, owner: String): List<TicketIntentEntity>
}
