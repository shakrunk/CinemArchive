package work.kumarfamilynet.cinemarchive.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A durable, local-first queue of tracking mutations awaiting a remote write. Every write
 * the user makes (log a watch, change a rating, ...) lands here optimistically alongside
 * the local Room write, and is flushed to Supabase once a network client is available —
 * see docs/android-sync-contract.md §4 for the idempotency contract this follows
 * (client-generated ids, upsert-not-insert).
 */
@Entity(tableName = "mutation_outbox")
data class OutboxEntity(
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val operation: String, // "upsert" | "delete"
    val payloadJson: String,
    val createdAt: Long,
    val attemptCount: Int = 0,
    val lastError: String? = null,
)

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(entry: OutboxEntity)

    @Query("SELECT * FROM mutation_outbox ORDER BY createdAt")
    fun observePending(): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM mutation_outbox ORDER BY createdAt")
    suspend fun getPending(): List<OutboxEntity>

    @Query("DELETE FROM mutation_outbox WHERE id = :id")
    suspend fun remove(id: String)

    @Query("UPDATE mutation_outbox SET attemptCount = attemptCount + 1, lastError = :error WHERE id = :id")
    suspend fun recordFailure(id: String, error: String?)
}
