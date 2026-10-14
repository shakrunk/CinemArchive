package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

internal const val VIEWING_COMMAND = "command_v2"
internal const val VIEWING_COMMAND_DATA = "viewingCommand"
internal const val VIEWING_TITLE_EFFECT = "viewingTitleEffect"
private val viewingColumns = mapOf("date" to "viewed_at", "rating" to "rating", "notes" to "notes", "venue" to "venue", "companions" to "companions")

internal fun viewingWireValues(fields: JSONObject): JSONObject = JSONObject().apply {
    require(fields.keys().asSequence().all { it in viewingColumns })
    fields.keys().forEach { key ->
        val value = fields.get(key)
        when (key) {
            "date" -> if (value != JSONObject.NULL) LocalDate.parse(value as String)
            "rating" -> if (value != JSONObject.NULL) require(value is Number && value.toDouble().isFinite() && value.toDouble() in 0.0..5.0)
            "notes", "venue" -> require(value == JSONObject.NULL || value is String)
            "companions" -> {
                require(value is JSONArray)
                for (index in 0 until value.length()) {
                    val person = value.getJSONObject(index)
                    require(person.keys().asSequence().all { it in setOf("name", "friendUserId") })
                    require(person.getString("name").isNotBlank())
                    if (person.has("friendUserId") && !person.isNull("friendUserId")) UUID.fromString(person.getString("friendUserId"))
                }
            }
        }
        put(viewingColumns.getValue(key), value)
    }
}

internal fun viewingCommandPayload(id: String, titleId: String, action: String, fields: JSONObject,
    baseline: String?, predecessor: String?): JSONObject {
    UUID.fromString(id); UUID.fromString(titleId)
    require(action in setOf("insert", "update", "delete"))
    require(if (action == "insert") baseline == null && predecessor == null else (baseline != null) != (predecessor != null))
    val operation = JSONObject().put("table", "viewings").put("action", action).put("key", JSONObject().put("id", id))
    if (action == "delete") require(fields.length() == 0)
    else {
        require(fields.length() > 0)
        val values = viewingWireValues(fields)
        if (action == "insert") {
            require(fields.keys().asSequence().toSet() == viewingColumns.keys) { "A new viewing must retain its complete original intent." }
            values.put("title_id", titleId).put("outing_id", JSONObject.NULL)
        }
        operation.put("values", values)
    }
    if (predecessor != null) { UUID.fromString(predecessor); operation.put("expectedOperationId", predecessor) }
    else if (action != "insert") { Instant.parse(baseline); operation.put("expectedUpdatedAt", baseline) }
    return JSONObject().put("id", id).put("titleId", titleId).put("fields", JSONObject(fields.toString()))
        .put(VIEWING_COMMAND_DATA, JSONObject().put("version", 2).put("operations", JSONArray().put(operation)))
}

internal fun viewingCommandOperations(entry: OutboxEntity): JSONArray {
    require(entry.entityType == "viewing" && entry.operation == VIEWING_COMMAND)
    val payload = JSONObject(entry.payloadJson)
    require(payload.getString("id") == entry.entityId)
    val command = payload.getJSONObject(VIEWING_COMMAND_DATA)
    require(command.keys().asSequence().toSet() == setOf("version", "operations"))
    require(command.getInt("version") == 2)
    val operations = command.getJSONArray("operations")
    require(operations.length() == if (payload.has(VIEWING_TITLE_EFFECT)) 2 else 1)
    val operation = operations.getJSONObject(0)
    val baseline = if (operation.has("expectedUpdatedAt")) operation.getString("expectedUpdatedAt") else null
    val predecessor = if (operation.has("expectedOperationId")) operation.getString("expectedOperationId") else null
    require(predecessor != entry.id)
    val expected = viewingCommandPayload(entry.entityId, payload.getString("titleId"), operation.getString("action"),
        payload.getJSONObject("fields"), baseline, predecessor).getJSONObject(VIEWING_COMMAND_DATA).getJSONArray("operations")
    viewingTitleEntry(entry)?.let { expected.put(titleMetadataOperation(it, JSONObject(it.payloadJson).getString("ownerId"))) }
    require(sameCommandJson(operations, expected)) { "Saved viewing command differs from its retained intent." }
    return operations
}

internal fun attachViewingTitleEffect(payload: JSONObject, titleEffect: JSONObject) {
    payload.put(VIEWING_TITLE_EFFECT, titleEffect)
    payload.optJSONObject(VIEWING_COMMAND_DATA)?.getJSONArray("operations")?.put(titleEffect.getJSONObject(TITLE_METADATA_DATA).getJSONObject("operation"))
}

internal fun viewingTitleEntry(entry: OutboxEntity, ownerId: String? = null): OutboxEntity? {
    val payload = JSONObject(entry.payloadJson)
    val effect = payload.optJSONObject(VIEWING_TITLE_EFFECT) ?: return null
    val titleId = payload.getString("titleId")
    require(effect.getString("titleId") == titleId && (ownerId == null || effect.getString("ownerId") == ownerId))
    payload.optJSONObject(VIEWING_OPENING)?.let { require(it.getString("ownerId") == effect.getString("ownerId")) }
    val synthetic = entry.copy(entityType = "title", entityId = titleId, operation = TITLE_METADATA_COMMAND, payloadJson = effect.toString())
    val patch = titleMetadataPatch(synthetic, effect.getString("ownerId"))
    require(patch.keys().asSequence().all { it in setOf("status", "rating") })
    val action = payload.optJSONObject(VIEWING_COMMAND_DATA)?.getJSONArray("operations")?.getJSONObject(0)?.getString("action")
        ?: payload.getJSONObject(VIEWING_REVIEW_INTENT).getString("action")
    require(action != "delete")
    if (patch.has("status")) require(patch.getString("status") == "watched" && (action == "insert" || payload.optBoolean("reviewedInsert")))
    if (patch.has("rating")) {
        val fields = payload.optJSONObject("fields") ?: payload.getJSONObject(VIEWING_REVIEW_INTENT).getJSONObject("fields")
        require(fields.has("rating") && !fields.isNull("rating") && sameCommandJson(fields.get("rating"), patch.get("rating")))
    }
    return synthetic
}

internal fun hasViewingTitleEffect(entry: OutboxEntity, titleId: String): Boolean = entry.entityType == "viewing" && runCatching {
    val payload = JSONObject(entry.payloadJson)
    payload.has(VIEWING_TITLE_EFFECT) && payload.getString("titleId") == titleId
}.getOrDefault(false)
