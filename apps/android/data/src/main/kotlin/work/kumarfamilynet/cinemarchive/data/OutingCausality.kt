package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

internal sealed interface OutingPrecondition {
    data class Literal(val updatedAt: String) : OutingPrecondition
    data class Operation(val operationId: String) : OutingPrecondition
    data class Review(val reason: String) : OutingPrecondition
}

/** Read the owner queue inside the same transaction that admits the new intent.
 * Queue order, not wall-clock timestamps, defines causality. An earlier unresolved
 * intent cannot be hidden by a newer command that happens to parse successfully. */
internal fun resolveOutingPrecondition(
    outing: CinemaOutingEntity,
    pendingEntries: List<OutboxEntity>,
    ownerScope: TicketOwnerScope?,
): OutingPrecondition {
    val related = pendingEntries.filter { (it.entityId == outing.id &&
        it.entityType in setOf("cinema_outing", "outing_completion", "outing_reversal", TICKET_COMMAND_ENTITY)) ||
        (isBackupImport(it) && it.entityId == outing.titleId) }
    var predecessor: String? = null
    for (entry in related) {
        if (isBackupImport(entry)) {
            val proof = runCatching {
                val scope = requireNotNull(ownerScope) { "The import owner could not be verified." }
                checkedImportCommand(entry, scope)
                importPredecessorFor(entry, "cinema_outings", outing.id, scope.ownerId)
            }.getOrElse { return OutingPrecondition.Review(it.message ?: "Review the earlier import first.") }
            if (proof != null) {
                if (predecessor != null) return OutingPrecondition.Review("The import does not follow the earlier outing change.")
                predecessor = proof
            }
            continue
        }
        val dependency = runCatching {
            when (entry.entityType) {
                "cinema_outing" -> {
                    val operation = outingCommandOperations(entry).getJSONObject(0)
                    if (operation.has("expectedOperationId")) operation.getString("expectedOperationId") else null
                }
                TICKET_COMMAND_ENTITY -> {
                    val ticket = ticketCommand(entry)
                    require(ownerScope != null && ticket.scope == ownerScope) { "Ticket owner or project could not be verified." }
                    require(ticket.revisionGuarded) { "The saved ticket change did not check the outing revision." }
                    ticket.expectedOperationId
                }
                "outing_reversal" -> {
                    val reversal = reversalCommand(entry)
                    require(ownerScope != null && org.json.JSONObject(entry.payloadJson).getString("ownerId") == ownerScope.ownerId)
                    require(reversal.titleId == outing.titleId)
                    reversal.outingGuard.operationId
                }
                else -> error("Wait for completion confirmation before changing this outing.")
            }
        }.getOrElse {
            return OutingPrecondition.Review(it.message ?: "Review the earlier saved outing change first.")
        }
        if (predecessor != null && dependency != predecessor) {
            return OutingPrecondition.Review("The saved changes do not share a proven outing revision. Review them before continuing.")
        }
        predecessor = entry.id
    }
    if (predecessor != null) return OutingPrecondition.Operation(predecessor)
    return runCatching { Instant.parse(outing.updatedAt); OutingPrecondition.Literal(outing.updatedAt) }
        .getOrElse { OutingPrecondition.Review("The original outing revision is unavailable. Refresh and review the plan first.") }
}
