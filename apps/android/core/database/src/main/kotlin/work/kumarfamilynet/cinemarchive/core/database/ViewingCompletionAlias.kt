package work.kumarfamilynet.cinemarchive.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Accepted completion identity in an account's private database. Deliberately no foreign keys:
 * deleting a title, outing or canonical event must not erase proof and permit its resurrection.
 * Only a validated completion receipt may establish this mapping; existing mappings never change.
 */
@Entity(tableName = "viewing_completion_aliases", indices = [Index("completionOperationId", unique = true)])
data class ViewingCompletionAliasEntity(
    @PrimaryKey val provisionalViewingId: String,
    val canonicalViewingId: String,
    val titleId: String,
    val outingId: String,
    val completionOperationId: String,
    // Historical completion identity may be proven without its original revision. Such a
    // mapping resolves identity, but never authorizes dependent edits without explicit review.
    val canonicalViewingVersion: String?,
)

@Dao
interface ViewingCompletionAliasDao {
    @Query("SELECT * FROM viewing_completion_aliases WHERE provisionalViewingId = :id")
    suspend fun byProvisionalId(id: String): ViewingCompletionAliasEntity?

    @Query("SELECT * FROM viewing_completion_aliases WHERE completionOperationId = :id")
    suspend fun byCompletionOperationId(id: String): ViewingCompletionAliasEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(alias: ViewingCompletionAliasEntity)
}
