package work.kumarfamilynet.cinemarchive.data

import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.TicketAssociation
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

internal data class TicketDescriptorRead(val authoritative: Boolean, val associations: List<TicketAssociation>)

internal fun ticketDescriptors(scope: TicketOwnerScope, json: JSONArray): List<TicketAssociation> {
    val ids = mutableSetOf<String>()
    return (0 until json.length()).map { index ->
        val row = json.getJSONObject(index)
        row.exactTicketKeys("outingId", "managed", "attachment")
        val id = checkedTicketUuid(row.ticketString("outingId"))
        require(ids.add(id) && row.get("managed") == true) { "Malformed managed ticket projection." }
        TicketAssociation(id, if (row.get("attachment") === JSONObject.NULL) null else ticketAttachmentFromJson(scope, row.getJSONObject("attachment")))
    }
}

/** The receipt proves the old operation. Its row must never replace newer current state. */
internal fun checkedTicketReceipt(command: TicketAttachmentCommand, receipt: JSONObject): JSONObject {
    require(receipt.ticketString("operationId") == command.operationId && receipt.ticketString("outingId") == command.outingId)
    val request = JSONObject(receipt.getJSONObject("request").toString())
    if (command.expectedUpdatedAt != null) {
        require(ticketInstant(request.ticketString("expectedUpdatedAt")) == ticketInstant(command.expectedUpdatedAt))
        request.put("expectedUpdatedAt", command.expectedUpdatedAt)
    }
    require(sameCommandJson(command.receiptRequest(), request)) { "Ticket receipt changed its immutable request." }
    require(if (command.revisionGuarded) receipt.opt("outingRevisionGuarded") == true else receipt.opt("outingRevisionGuarded") != true)
    val attachment = if (receipt.get("attachment") === JSONObject.NULL) null else ticketAttachmentFromJson(command.scope, receipt.getJSONObject("attachment"))
    require(attachment == command.attachment)
    val revision = ticketInstant(receipt.ticketString("outingUpdatedAt"))
    val rows = receipt.getJSONArray("rows")
    require(rows.length() == 1)
    val effect = rows.getJSONObject(0)
    require(effect.ticketString("table") == "cinema_outings" && !effect.optBoolean("deleted"))
    effect.getJSONObject("key").exactTicketKeys("id")
    require(effect.getJSONObject("key").ticketString("id") == command.outingId)
    val row = effect.getJSONObject("row")
    checkOwnedTicketOuting(command, row)
    require(ticketInstant(row.ticketString("updated_at")) == revision)
    require(row.get("ticket_attachment_managed") == true && row.ticketNullableString("ticket_attachment_id") == command.attachment?.id)
    return row
}

internal fun checkOwnedTicketOuting(command: TicketAttachmentCommand, row: JSONObject) {
    require(row.ticketString("id") == command.outingId && row.ticketString("user_id") == command.scope.ownerId)
    checkedTicketUuid(row.ticketString("title_id"))
    row.toRecoveryOuting()
}

internal data class TicketAcknowledgment(val currentOuting: JSONObject?, val association: TicketAssociation?)

internal fun checkedTicketEnvelope(command: TicketAttachmentCommand, envelope: JSONObject): TicketAcknowledgment {
    envelope.exactTicketKeys("receipt", "currentOuting", "associations")
    val original = checkedTicketReceipt(command, envelope.getJSONObject("receipt"))
    val current = if (envelope.get("currentOuting") === JSONObject.NULL) null else envelope.getJSONObject("currentOuting").also {
        checkOwnedTicketOuting(command, it)
        require(it.ticketString("title_id") == original.ticketString("title_id"))
    }
    val associations = ticketDescriptors(command.scope, envelope.getJSONArray("associations"))
    val association = associations.singleOrNull { it.outingId == command.outingId }
    if (current == null) require(association == null) { "Ticket graph changed while refreshing; confirm again." }
    else {
        require(current.get("ticket_attachment_managed") == true && association != null)
        require(current.ticketNullableString("ticket_attachment_id") == association.attachment?.id) { "Ticket graph changed while refreshing; confirm again." }
    }
    return TicketAcknowledgment(current, association)
}
