package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LocalTransactor
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.database.TicketAttachmentDao
import work.kumarfamilynet.cinemarchive.core.database.TicketAssociationEntity
import work.kumarfamilynet.cinemarchive.core.database.TicketIntentEntity
import work.kumarfamilynet.cinemarchive.core.database.TicketOriginalEntity
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

/** A stale download/descriptor response must not roll back a newer local ticket save or ACK. */
internal data class TicketProjectionToken(
    val associations: List<TicketAssociationEntity>,
    val intents: List<TicketIntentEntity>,
    val pending: List<OutboxEntity>,
)

/** Descriptor refresh only. Runtime activation and command ACK/removal remain separate integration. */
internal class TicketProjectionRepository(
    private val scope: TicketOwnerScope,
    private val dao: TicketAttachmentDao,
    private val transactor: LocalTransactor,
    private val isCurrentOwner: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun captureToken(): TicketProjectionToken = withContext(Dispatchers.IO) {
        transactor.run { current(); token().also { current() } }
    }

    suspend fun apply(read: TicketDescriptorRead, captured: TicketProjectionToken): Boolean = withContext(Dispatchers.IO) {
        current()
        if (!read.authoritative) return@withContext false // Never erase managed clears on an old backend.
        val applied = transactor.run {
            current()
            if (token() != captured) return@run false
            require(read.associations.map { it.outingId }.distinct().size == read.associations.size)
            val pendingOutings = captured.pending.filter { it.entityType == TICKET_COMMAND_ENTITY }.map { entry ->
                ticketCommand(entry).also { require(it.scope == scope) }.outingId
            }.toSet()
            for (association in read.associations) {
                checkedTicketUuid(association.outingId)
                association.attachment?.let { checkedTicketAttachment(scope, it) }
                if (association.outingId in pendingOutings || !dao.outingExists(association.outingId)) continue
                association.attachment?.let { descriptor ->
                    val existing = dao.original(scope.projectId, scope.ownerId, descriptor.id)
                    if (existing == null) dao.insertOriginal(TicketOriginalEntity(scope.projectId, scope.ownerId, descriptor.id,
                        association.outingId, descriptor.toTicketJson().toString(), now()))
                    else require(existing.outingId == association.outingId &&
                        ticketAttachmentFromJson(scope, JSONObject(existing.descriptorJson)) == descriptor) { "Ticket descriptor identity changed." }
                }
                dao.putAssociation(TicketAssociationEntity(scope.projectId, scope.ownerId, association.outingId, association.attachment?.id))
            }
            current(); true
        }
        current(); applied
    }

    private suspend fun token() = TicketProjectionToken(dao.associations(scope.projectId, scope.ownerId),
        dao.intents(scope.projectId, scope.ownerId), dao.pending())
    private fun current() { check(isCurrentOwner()) { "Ticket account changed." } }
}
