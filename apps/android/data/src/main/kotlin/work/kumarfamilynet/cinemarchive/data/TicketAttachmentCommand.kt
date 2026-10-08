package work.kumarfamilynet.cinemarchive.data

import java.net.URI
import java.util.UUID
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.TicketAttachment
import work.kumarfamilynet.cinemarchive.core.model.TicketBarcode
import work.kumarfamilynet.cinemarchive.core.model.TicketBarcodeFormat
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

internal const val MAX_TICKET_BYTES = 20L * 1024 * 1024
internal const val TICKET_COMMAND_VERSION = 1
internal const val TICKET_COMMAND_ENTITY = "ticket_attachment"
internal const val TICKET_COMMAND_OPERATION = "ticket_v1"

/** Persist once. The expected association and operation ID cannot change after admission. */
data class TicketAttachmentCommand(
    val operationId: String,
    val scope: TicketOwnerScope,
    val outingId: String,
    val attachment: TicketAttachment?,
    val expectedAttachmentId: String?,
) {
    val kind: String get() = if (attachment == null) "ticket.detach" else "ticket.attach"
}

internal fun checkedTicketUuid(value: String): String = value.also {
    require(runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)) { "Invalid ticket identifier." }
}

internal fun checkedTicketScope(scope: TicketOwnerScope): TicketOwnerScope = scope.also {
    checkedTicketUuid(it.ownerId)
    val uri = URI(it.projectId)
    require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null &&
        uri.query == null && uri.fragment == null && uri.path in listOf("", "/")) { "Invalid ticket project scope." }
}

internal fun checkedTicketAttachment(scope: TicketOwnerScope, value: TicketAttachment): TicketAttachment = value.also {
    checkedTicketScope(scope); checkedTicketUuid(it.id)
    require(it.objectKey == "${scope.ownerId}/${it.id}/original") { "Ticket object belongs to another owner or attachment." }
    require(it.mimeType in setOf("image/jpeg", "image/png", "image/webp")) { "Use an original JPEG, PNG, or WebP ticket image." }
    require(it.byteLength in 1..MAX_TICKET_BYTES) { "Ticket photos must be no larger than 20 MiB." }
    require(it.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid ticket content digest." }
    it.barcode?.let { code ->
        require(code.payload.isNotEmpty() && code.payload.toByteArray(Charsets.UTF_8).size <= 32768) { "Ticket barcode is too large." }
        // Reject unpaired UTF-16 surrogates rather than silently replace their original bytes.
        require(Charsets.UTF_8.newEncoder().canEncode(code.payload)) { "Invalid ticket barcode text." }
    }
}

internal fun TicketAttachment.toTicketJson(): JSONObject = JSONObject().put("id", id).put("objectKey", objectKey)
    .put("mimeType", mimeType).put("byteLength", byteLength).put("sha256", sha256)
    .put("barcode", barcode?.let { JSONObject().put("payload", it.payload).put("format", it.format.name) } ?: JSONObject.NULL)

internal fun JSONObject.exactTicketKeys(vararg keys: String) {
    require(keys().asSequence().toSet() == keys.toSet()) { "Malformed ticket fields." }
}

internal fun JSONObject.ticketString(key: String): String = get(key).let {
    require(it is String) { "Invalid ticket $key." }; it
}

internal fun JSONObject.ticketNullableString(key: String): String? = if (get(key) === JSONObject.NULL) null else ticketString(key)

internal fun ticketAttachmentFromJson(scope: TicketOwnerScope, json: JSONObject): TicketAttachment {
    json.exactTicketKeys("id", "objectKey", "mimeType", "byteLength", "sha256", "barcode")
    val length = json.get("byteLength")
    require(length is Number && length.toString().toBigDecimal().stripTrailingZeros().scale() <= 0) { "Invalid ticket size." }
    val barcode = if (json.get("barcode") === JSONObject.NULL) null else json.getJSONObject("barcode").let {
        it.exactTicketKeys("payload", "format")
        TicketBarcode(it.ticketString("payload"), TicketBarcodeFormat.valueOf(it.ticketString("format")))
    }
    return checkedTicketAttachment(scope, TicketAttachment(json.ticketString("id"), json.ticketString("objectKey"),
        json.ticketString("mimeType"), length.toString().toBigDecimal().longValueExact(), json.ticketString("sha256"), barcode))
}

internal fun checkedTicketCommand(command: TicketAttachmentCommand): TicketAttachmentCommand = command.also {
    checkedTicketScope(it.scope); checkedTicketUuid(it.operationId); checkedTicketUuid(it.outingId)
    it.expectedAttachmentId?.let(::checkedTicketUuid)
    it.attachment?.let { descriptor -> checkedTicketAttachment(it.scope, descriptor) }
    require(it.attachment == null || it.attachment.id != it.expectedAttachmentId) { "Replacement must use a new attachment." }
}

internal fun TicketAttachmentCommand.toTicketJson(): JSONObject {
    checkedTicketCommand(this)
    return JSONObject().put("version", TICKET_COMMAND_VERSION).put("operationId", operationId)
        .put("projectId", scope.projectId).put("ownerId", scope.ownerId).put("kind", kind).put("outingId", outingId)
        .put("expectedAttachmentId", expectedAttachmentId ?: JSONObject.NULL)
        .put("attachment", attachment?.toTicketJson() ?: JSONObject.NULL)
}

internal fun ticketCommandFromJson(json: JSONObject): TicketAttachmentCommand {
    json.exactTicketKeys("version", "operationId", "projectId", "ownerId", "kind", "outingId", "expectedAttachmentId", "attachment")
    require(json.get("version") == TICKET_COMMAND_VERSION) { "Unsupported ticket command version." }
    val scope = checkedTicketScope(TicketOwnerScope(json.ticketString("projectId"), json.ticketString("ownerId")))
    val attachment = if (json.get("attachment") === JSONObject.NULL) null else ticketAttachmentFromJson(scope, json.getJSONObject("attachment"))
    return checkedTicketCommand(TicketAttachmentCommand(json.ticketString("operationId"), scope, json.ticketString("outingId"),
        attachment, json.ticketNullableString("expectedAttachmentId"))).also {
        require(json.ticketString("kind") == it.kind) { "Ticket command kind does not match its content." }
    }
}
