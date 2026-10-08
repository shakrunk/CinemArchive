package work.kumarfamilynet.cinemarchive.data

import java.net.URI
import java.util.UUID
import java.time.Instant
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
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
    val expectedUpdatedAt: String? = null,
    val expectedOperationId: String? = null,
) {
    val kind: String get() = if (attachment == null) "ticket.detach" else "ticket.attach"
    val revisionGuarded: Boolean get() = expectedUpdatedAt != null || expectedOperationId != null
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
    require(it.expectedUpdatedAt == null || it.expectedOperationId == null) { "Ticket intent has two outing guards." }
    it.expectedUpdatedAt?.let(::ticketInstant)
    it.expectedOperationId?.let { id -> checkedTicketUuid(id); require(id != command.operationId) { "Ticket cannot depend on itself." } }
}

internal fun ticketInstant(value: String): Instant {
    require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?(?:Z|[+-]\\d{2}:\\d{2})"))) { "Invalid ticket revision timestamp." }
    return Instant.parse(value)
}

internal fun TicketAttachmentCommand.toTicketJson(): JSONObject {
    checkedTicketCommand(this)
    return JSONObject().put("version", TICKET_COMMAND_VERSION).put("operationId", operationId)
        .put("projectId", scope.projectId).put("ownerId", scope.ownerId).put("kind", kind).put("outingId", outingId)
        .put("expectedAttachmentId", expectedAttachmentId ?: JSONObject.NULL)
        .put("attachment", attachment?.toTicketJson() ?: JSONObject.NULL)
        .apply {
            expectedUpdatedAt?.let { put("expectedUpdatedAt", it) }
            expectedOperationId?.let { put("expectedOperationId", it) }
        }
}

internal fun ticketCommandFromJson(json: JSONObject): TicketAttachmentCommand {
    val guards = listOf("expectedUpdatedAt", "expectedOperationId").filter(json::has)
    require(guards.size <= 1)
    json.exactTicketKeys("version", "operationId", "projectId", "ownerId", "kind", "outingId", "expectedAttachmentId", "attachment", *guards.toTypedArray())
    require(json.get("version") == TICKET_COMMAND_VERSION) { "Unsupported ticket command version." }
    val scope = checkedTicketScope(TicketOwnerScope(json.ticketString("projectId"), json.ticketString("ownerId")))
    val attachment = if (json.get("attachment") === JSONObject.NULL) null else ticketAttachmentFromJson(scope, json.getJSONObject("attachment"))
    return checkedTicketCommand(TicketAttachmentCommand(json.ticketString("operationId"), scope, json.ticketString("outingId"),
        attachment, json.ticketNullableString("expectedAttachmentId"),
        if (json.has("expectedUpdatedAt")) json.ticketString("expectedUpdatedAt") else null,
        if (json.has("expectedOperationId")) json.ticketString("expectedOperationId") else null)).also {
        require(json.ticketString("kind") == it.kind) { "Ticket command kind does not match its content." }
    }
}

internal fun ticketCommand(entry: OutboxEntity): TicketAttachmentCommand {
    require(entry.entityType == TICKET_COMMAND_ENTITY && entry.operation == TICKET_COMMAND_OPERATION)
    return ticketCommandFromJson(JSONObject(entry.payloadJson)).also {
        require(it.operationId == entry.id && it.outingId == entry.entityId) { "Ticket queue identity differs from its saved intent." }
    }
}

internal fun TicketAttachmentCommand.receiptRequest(): JSONObject = JSONObject().put("kind", kind).put("outingId", outingId)
    .put("attachmentId", attachment?.id ?: JSONObject.NULL).put("expectedAttachmentId", expectedAttachmentId ?: JSONObject.NULL)
    .apply { if (revisionGuarded) {
        put("expectedUpdatedAt", expectedUpdatedAt ?: JSONObject.NULL)
        put("expectedOperationId", expectedOperationId ?: JSONObject.NULL)
    } }

internal fun TicketAttachmentCommand.finalizeArgs(): JSONObject = JSONObject().put("p_operation_id", operationId)
    .put("p_outing_id", outingId).put("p_expected_attachment_id", expectedAttachmentId ?: JSONObject.NULL)
    .apply {
        attachment?.let { put("p_attachment_id", it.id) }
        if (revisionGuarded) {
            put("p_expected_updated_at", expectedUpdatedAt ?: JSONObject.NULL)
            put("p_expected_operation_id", expectedOperationId ?: JSONObject.NULL)
        }
    }
