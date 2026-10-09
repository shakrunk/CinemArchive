package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

/** Mutable queue status, never part of immutable operation JSON. Only a definite association
 * RPC rejection creates this evidence; receipt lookup/refresh/network failures cannot. */
internal fun ticketRejection(entry: OutboxEntity, code: String): String = JSONObject()
    .put("kind", "ticket-rejection-v1").put("operationId", entry.id).put("payloadJson", entry.payloadJson)
    .put("code", code).put("message", "The outing or ticket changed before this capture was accepted. Compare the current ticket before resolving it.").toString()

internal fun checkedTicketRejection(entry: OutboxEntity): JSONObject? = runCatching {
    require(entry.entityType == TICKET_COMMAND_ENTITY && entry.operation == "review")
    val proof = JSONObject(checkNotNull(entry.lastError))
    proof.exactTicketKeys("kind", "operationId", "payloadJson", "code", "message")
    require(proof.ticketString("kind") == "ticket-rejection-v1" && proof.ticketString("operationId") == entry.id &&
        proof.ticketString("payloadJson") == entry.payloadJson && proof.ticketString("code") in setOf("40001", "23505"))
    ticketCommand(entry.copy(operation = TICKET_COMMAND_OPERATION))
    proof.ticketString("message")
    proof
}.getOrNull()

internal fun ticketStatusMessage(entry: OutboxEntity): String? = checkedTicketRejection(entry)?.ticketString("message") ?: entry.lastError
