package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LocalTransactor
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.database.TicketAttachmentDao
import work.kumarfamilynet.cinemarchive.core.database.TicketAssociationEntity
import work.kumarfamilynet.cinemarchive.core.database.TicketIntentEntity
import work.kumarfamilynet.cinemarchive.core.database.TicketOriginalEntity
import work.kumarfamilynet.cinemarchive.core.model.TicketAssociation
import work.kumarfamilynet.cinemarchive.core.model.TicketAttachment
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

/** Local durability boundary. The immutable original must be fsynced before this transaction.
 * All visible state, immutable intent and queued delivery commit together, or none do.
 * Network delivery and UI integration are separate consumers of this persisted contract. */
class TicketAttachmentsRepository(
    private val scope: TicketOwnerScope,
    private val dao: TicketAttachmentDao,
    private val transactor: LocalTransactor,
    private val files: TicketAttachmentFiles,
    private val isCurrentOwner: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    init { checkedTicketScope(scope); require(files.scope == scope) }

    fun observe(outingId: String): Flow<TicketAssociation?> {
        checkedTicketUuid(outingId)
        return dao.observeAssociation(scope.projectId, scope.ownerId, outingId).map { row ->
            if (!isCurrentOwner() || row == null) null else {
                val attachment = row.attachmentId?.let { descriptor(it) }
                if (isCurrentOwner()) TicketAssociation(outingId, attachment) else null
            }
        }
    }

    suspend fun attach(outingId: String, original: TicketAttachment, operationId: String): TicketAttachmentCommand = withContext(Dispatchers.IO) {
        current(); checkedTicketUuid(outingId); checkedTicketUuid(operationId)
        checkedTicketAttachment(scope, original)
        files.read(original) // Do not trust a caller-supplied path or metadata without original bytes.
        current()
        admit(outingId, original, operationId)
    }

    suspend fun detach(outingId: String, operationId: String): TicketAttachmentCommand = withContext(Dispatchers.IO) {
        current(); checkedTicketUuid(outingId); checkedTicketUuid(operationId)
        admit(outingId, null, operationId)
    }

    suspend fun readOriginal(outingId: String): StoredTicketOriginal? = withContext(Dispatchers.IO) {
        current(); checkedTicketUuid(outingId)
        val association = dao.association(scope.projectId, scope.ownerId, outingId) ?: return@withContext null
        val attachment = association.attachmentId?.let { descriptor(it) } ?: return@withContext null
        val file = files.read(attachment)
        current()
        check(dao.association(scope.projectId, scope.ownerId, outingId) == association && dao.outingExists(outingId)) {
            "The ticket changed while opening its photo."
        }
        current()
        StoredTicketOriginal(attachment, file)
    }

    private suspend fun admit(outingId: String, attachment: TicketAttachment?, operationId: String): TicketAttachmentCommand {
        val command = transactor.run {
            current()
            val existing = dao.intent(operationId)
            if (existing != null) {
                val saved = ticketCommandFromJson(JSONObject(existing.payloadJson))
                require(existing.projectId == scope.projectId && existing.ownerId == scope.ownerId && existing.outingId == outingId &&
                    saved.operationId == operationId && saved.scope == scope && saved.outingId == outingId && saved.attachment == attachment) {
                    "This ticket operation ID already belongs to different intent."
                }
                val queued = dao.queued(operationId)
                check(existing.acknowledgedAt != null || (queued != null && queued.payloadJson == existing.payloadJson &&
                    queued.entityType == TICKET_COMMAND_ENTITY && queued.entityId == outingId)) { "Ticket recovery data is incomplete." }
                current()
                return@run saved
            }
            require(dao.queued(operationId) == null) { "This operation ID already belongs to another queued change." }
            check(dao.outingExists(outingId)) { "This outing is no longer available." }
            val previous = dao.association(scope.projectId, scope.ownerId, outingId)
            val saved = checkedTicketCommand(TicketAttachmentCommand(operationId, scope, outingId, attachment, previous?.attachmentId))
            if (attachment != null) {
                // A detached/replaced attachment may already be retired remotely. Only the
                // original immutable operation above may reuse its ID; new intent needs new bytes identity.
                require(dao.original(scope.projectId, scope.ownerId, attachment.id) == null) {
                    "This attachment ID was already used. Save the photo with a new attachment ID."
                }
                dao.insertOriginal(TicketOriginalEntity(scope.projectId, scope.ownerId, attachment.id, outingId,
                    attachment.toTicketJson().toString(), now()))
            }
            val payload = saved.toTicketJson().toString()
            val timestamp = now()
            dao.insertIntent(TicketIntentEntity(operationId, scope.projectId, scope.ownerId, outingId, payload, timestamp))
            dao.insertCommand(OutboxEntity(operationId, TICKET_COMMAND_ENTITY, outingId, TICKET_COMMAND_OPERATION, payload, timestamp))
            dao.putAssociation(TicketAssociationEntity(scope.projectId, scope.ownerId, outingId, attachment?.id))
            current()
            saved
        }
        // A completed commit remains in its original account even if sign-out raced its return.
        current()
        return command
    }

    private suspend fun descriptor(id: String): TicketAttachment {
        val original = checkNotNull(dao.original(scope.projectId, scope.ownerId, id)) { "Ticket descriptor is unavailable." }
        return ticketAttachmentFromJson(scope, JSONObject(original.descriptorJson)).also { require(it.id == id) }
    }

    private fun current() { check(isCurrentOwner()) { "This sign-in has ended. Ticket work remains with its original account." } }
}
