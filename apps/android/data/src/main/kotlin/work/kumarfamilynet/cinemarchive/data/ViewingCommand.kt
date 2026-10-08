package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

internal const val VIEWING_COMMAND = "command_v2"
internal const val VIEWING_COMMAND_DATA = "viewingCommand"
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
    require(operations.length() == 1)
    val operation = operations.getJSONObject(0)
    val baseline = if (operation.has("expectedUpdatedAt")) operation.getString("expectedUpdatedAt") else null
    val predecessor = if (operation.has("expectedOperationId")) operation.getString("expectedOperationId") else null
    require(predecessor != entry.id)
    val expected = viewingCommandPayload(entry.entityId, payload.getString("titleId"), operation.getString("action"),
        payload.getJSONObject("fields"), baseline, predecessor).getJSONObject(VIEWING_COMMAND_DATA).getJSONArray("operations")
    require(sameCommandJson(operations, expected)) { "Saved viewing command differs from its retained intent." }
    return operations
}
