package work.kumarfamilynet.cinemarchive.core.database

import androidx.room.RoomDatabase
import androidx.room.withTransaction

/**
 * Runs a block as ONE database transaction. Repositories use it so a local write and the outbox
 * entry that describes it commit (or fail) together, and the sync pull uses it so "which rows
 * are pending" and "apply the remote page" can't be interleaved with a user edit: Room
 * serializes write transactions, so an edit lands entirely before (and is then seen as pending
 * and protected) or entirely after the pull page (and so wins).
 */
interface LocalTransactor {
    suspend fun <T> run(block: suspend () -> T): T
}

/** No transaction — for unit tests whose DAOs are fakes. */
object PassthroughTransactor : LocalTransactor {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

/** Room-backed; nested calls join the outer transaction. */
class RoomTransactor(private val database: RoomDatabase) : LocalTransactor {
    override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction { block() }
}
