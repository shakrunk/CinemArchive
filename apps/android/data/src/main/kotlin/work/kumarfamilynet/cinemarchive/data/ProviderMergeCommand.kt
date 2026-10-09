package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.database.ViewingEntity
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

internal const val PROVIDER_MERGE = "provider_merge"
internal const val PROVIDER_MERGE_COMMAND = "merge_v1"
internal data class ProviderMergeCommand(val owner: TicketOwnerScope, val titleId: String, val title: String,
    val patch: JSONObject, val viewings: List<ViewingEntity>, val operations: JSONArray)

internal fun providerMergePayload(owner: TicketOwnerScope, titleId: String, title: String, patch: JSONObject,
    viewings: List<ViewingEntity>, link: ProviderTitleLink, at: String, guard: ViewingGuard): String {
    val body = JSONObject().put("version", 1).put("ownerId", owner.ownerId).put("projectId", owner.projectId)
        .put("titleId", titleId).put("title", title).put("patch", patch).put("at", at).put("guard", guard.json())
        .put("link", link.json()).put("viewings", JSONArray(viewings.map { JSONObject().put("id", it.id).put("date", it.date) }))
    return metadataJson(body)
}

internal fun checkedProviderMerge(entry: OutboxEntity, scope: TicketOwnerScope? = null): ProviderMergeCommand {
    require(entry.entityType == PROVIDER_MERGE && entry.operation in setOf(PROVIDER_MERGE_COMMAND, "review"))
    UUID.fromString(entry.id)
    val body = exactMetadataObject(entry.payloadJson)
    require(body.keys().asSequence().toSet() == setOf("version", "ownerId", "projectId", "titleId", "title", "patch", "at", "guard", "link", "viewings"))
    require(body.getInt("version") == 1 && body.getString("titleId") == entry.entityId)
    val owner = TicketOwnerScope(body.getString("projectId"), body.getString("ownerId"))
    require(scope == null || owner == scope); UUID.fromString(owner.ownerId); UUID.fromString(entry.entityId)
    val at = body.getString("at").also(Instant::parse)
    val patch = body.getJSONObject("patch")
    require(patch.keys().asSequence().all { it in setOf("status", "rating") })
    if (patch.length() > 0) checkedTitlePatch(patch)
    patch.opt("rating")?.takeIf { it != JSONObject.NULL }?.let { require((it as Number).toString().toBigDecimal().stripTrailingZeros().scale() <= 1) }
    val rawGuard = body.getJSONObject("guard")
    require(rawGuard.keys().asSequence().toSet() == setOf("revision", "operationId"))
    val guard = ViewingGuard(if (rawGuard.isNull("revision")) null else rawGuard.getString("revision"),
        if (rawGuard.isNull("operationId")) null else rawGuard.getString("operationId"))
    require(guard.operationId != entry.id && (if (patch.length() > 0) guard.known else !guard.known))
    val rows = body.getJSONArray("viewings").importObjects().map {
        require(it.keys().asSequence().toSet() == setOf("id", "date"))
        val id = it.getString("id").also(UUID::fromString)
        val date = it.getString("date").also { value -> require(value.length == 10); LocalDate.parse(value) }
        ViewingEntity(id, entry.entityId, date, null, null, null, companionsJson = "[]")
    }
    require(rows.map { it.id }.distinct().size == rows.size && rows.map { it.date }.distinct().size == rows.size)
    val link = checkedProviderLinks(JSONArray().put(body.getJSONObject("link"))).single()
    val operations = JSONArray()
    if (patch.length() > 0) {
        val payload = titleMetadataPayload(owner.ownerId, entry.entityId, patch, guard.revision, guard.operationId)
        operations.put(payload.getJSONObject(TITLE_METADATA_DATA).getJSONObject("operation"))
    }
    rows.forEach { operations.put(importOperation("viewings", JSONObject().put("id", it.id),
        importValues("title_id" to entry.entityId, "viewed_at" to it.date, "rating" to null, "notes" to null,
            "venue" to null, "companions" to JSONArray(), "outing_id" to null, "created_at" to at))) }
    operations.put(providerLinkOperation(link, entry.entityId)); assertImportOperations(operations)
    return ProviderMergeCommand(owner, entry.entityId, body.getString("title"), patch, rows, operations)
}

internal fun providerMergePredecessor(entry: OutboxEntity, table: String, id: String, ownerId: String): String? {
    val command = checkedProviderMerge(entry)
    require(command.owner.ownerId == ownerId)
    if (command.operations.importObjects().none { it.getString("table") == table && it.getJSONObject("key").optString("id") == id }) return null
    require(entry.operation == PROVIDER_MERGE_COMMAND) { "Review the saved provider import first." }
    return entry.id
}

fun providerMergeProtectionKeys(entries: List<OutboxEntity>, owner: TicketOwnerScope): Set<String> = buildSet {
    entries.filter { it.entityType == PROVIDER_MERGE }.forEach {
        val command = checkedProviderMerge(it, owner)
        add("provider_merge:${it.entityId}")
        if (command.patch.length() > 0) add("title:${it.entityId}")
        command.viewings.forEach { row -> add("viewing:${row.id}") }
    }
}
