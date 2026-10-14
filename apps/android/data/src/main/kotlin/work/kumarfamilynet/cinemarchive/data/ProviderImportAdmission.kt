package work.kumarfamilynet.cinemarchive.data

import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.model.MediaDetails
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

class ProviderImportAdmission(
    private val database: LibraryDatabase,
    private val outbox: MutationOutbox,
    private val owner: TicketOwnerScope,
    private val current: () -> Boolean,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** False means another durable admission already owns this TMDB identity. */
    suspend fun addNew(details: MediaDetails, item: SyncItem): Boolean = withContext(Dispatchers.IO) {
        fun fence() = check(current()) { "The account changed during import." }
        fence()
        val link = ProviderTitleLink(item.provider, item.externalId)
        val at = Instant.now(clock).toString()
        val title = providerImportTitle(details, item, at)
        val entry = OutboxEntity(UUID.randomUUID().toString(), "title", title.getString("id"), BACKUP_IMPORT_COMMAND,
            importPayload(owner, title, emptyList(), at, listOf(link)), clock.millis())
        val command = checkedImportCommand(entry, owner)
        outbox.atomically {
            fence()
            val queue = database.outboxDao().getPending()
            val pending = queue.filter(::isBackupImport).map { checkedImportCommand(it, owner) }
            val existingId = database.titleDao().findIdByTmdbKey(details.tmdbId, details.type.name) ?:
                pending.firstOrNull { it.title.getInt("tmdbId") == details.tmdbId &&
                    it.title.getString("type") == details.type.name.lowercase() }?.title?.getString("id")
            requirePendingProviderTarget(pendingProviderLinks(queue, owner), link, existingId ?: entry.entityId)
            val exists = existingId != null
            if (exists) false else {
                writeImportGraph(database, command.mapping.graph)
                outbox.enqueueCaptured(entry.id, entry.entityType, entry.entityId, entry.operation, entry.payloadJson)
                fence()
                true
            }
        }
    }
}
