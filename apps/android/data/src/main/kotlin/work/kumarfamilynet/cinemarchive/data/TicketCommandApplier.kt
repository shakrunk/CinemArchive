package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.database.TicketAssociationEntity
import work.kumarfamilynet.cinemarchive.core.database.TicketOriginalEntity
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

/** Executes inside MutationOutbox's ACK transaction, alongside removal of the exact queue row. */
class TicketCommandApplier(
    private val database: LibraryDatabase,
    private val scope: TicketOwnerScope,
    private val isCurrentOwner: () -> Boolean,
) {
    suspend fun apply(entry: OutboxEntity, envelope: JSONObject) {
        check(isCurrentOwner()) { "Ticket account changed." }
        val command = ticketCommand(entry)
        require(command.scope == scope)
        val confirmed = checkedTicketEnvelope(command, envelope)
        val dao = database.ticketAttachmentDao()
        val intent = checkNotNull(dao.intent(entry.id))
        require(intent.payloadJson == entry.payloadJson && intent.projectId == scope.projectId && intent.ownerId == scope.ownerId)
        val queue = database.outboxDao().getPending()
        val index = queue.indexOfFirst { it.id == entry.id }
        require(index >= 0 && queue[index].payloadJson == entry.payloadJson)
        val later = queue.drop(index + 1).filter { it.entityId == command.outingId &&
            it.entityType in setOf(TICKET_COMMAND_ENTITY, "cinema_outing", "outing_completion") }
        applyCurrentTicketProjection(database, scope, command.outingId, confirmed, later)
        check(dao.acknowledge(entry.id, scope.projectId, scope.ownerId, entry.payloadJson, System.currentTimeMillis()) == 1)
        check(isCurrentOwner()) { "Ticket account changed." }
    }

    fun protectionKeys(entries: List<OutboxEntity>): Set<String> = entries.filter { it.entityType == TICKET_COMMAND_ENTITY }
        .flatMap { listOf("cinema_outing:${it.entityId}", "ticket_attachment:${it.entityId}") }.toSet()
}

/** Shared by receipt ACK and explicit confirmed-rejection recovery, always inside Room TX. */
internal suspend fun applyCurrentTicketProjection(database: LibraryDatabase, scope: TicketOwnerScope, outingId: String,
    confirmed: TicketAcknowledgment, later: List<OutboxEntity>) {
        val dao = database.ticketAttachmentDao()
        val laterTickets = later.filter { it.entityType == TICKET_COMMAND_ENTITY }.map { row ->
            // Review rows retain original immutable intent; they still own local projection.
            ticketCommand(row.copy(operation = TICKET_COMMAND_OPERATION)).also { require(it.scope == scope) }
        }
        val current = confirmed.currentOuting
        val local = database.cinemaOutingDao().getById(outingId)
        if (local != null && later.none { it.entityType != TICKET_COMMAND_ENTITY }) {
            if (current == null) {
                if (later.isEmpty()) database.cinemaOutingDao().deleteById(outingId)
            } else if (database.titleDao().getById(current.ticketString("title_id")) != null) {
                database.cinemaOutingDao().upsert(current.toRecoveryOuting())
            }
        }
        // Canonical receipt rows are historical. Use current descriptors, then keep newer local ticket intent.
        val descriptor = if (laterTickets.isNotEmpty()) laterTickets.last().attachment else confirmed.association?.attachment
        descriptor?.let {
            val old = dao.original(scope.projectId, scope.ownerId, it.id)
            if (old == null) dao.insertOriginal(TicketOriginalEntity(scope.projectId, scope.ownerId, it.id, outingId,
                it.toTicketJson().toString(), System.currentTimeMillis()))
            else require(old.outingId == outingId && ticketAttachmentFromJson(scope, JSONObject(old.descriptorJson)) == it)
        }
        if (laterTickets.isNotEmpty() || confirmed.association != null) {
            dao.putAssociation(TicketAssociationEntity(scope.projectId, scope.ownerId, outingId, descriptor?.id))
        } else dao.removeAssociation(scope.projectId, scope.ownerId, outingId)
}
