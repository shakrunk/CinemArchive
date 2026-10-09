package work.kumarfamilynet.cinemarchive.data

import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

internal object OutingCommandFixture {
    const val owner = "10000000-0000-4000-8000-000000000001"
    const val title = "20000000-0000-4000-8000-000000000001"
    const val outing = "30000000-0000-4000-8000-000000000001"
    const val operation = "40000000-0000-4000-8000-000000000001"
    const val baseline = "2026-10-08T12:00:00Z"
    fun entity() = CinemaOutingEntity(outing, title, "2099-10-08T19:00:00Z", 20, 90,
        "2099-10-08T20:50:00Z", "Cinema", listOf("Sam"), "IMAX", 12.5,
        notes = "Original note", createdAt = baseline, updatedAt = baseline)
    fun row() = outingWireBody(entity().mutationPayload(), owner, true)
    fun patch(id: String = operation, predecessor: String? = null, notes: String? = null): OutboxEntity {
        val intent = JSONObject().put("id", outing).put("updatedAt", "2026-10-08T13:00:00Z")
        if (notes != null) intent.put("notes", notes) else intent.put("venue", "Saved cinema")
        return OutboxEntity(id, "cinema_outing", outing, OUTING_COMMAND,
            outingCommandPayload(intent, false, baseline.takeIf { predecessor == null }, predecessor).toString(), 1)
    }
    fun create() = OutboxEntity(operation, "cinema_outing", outing, OUTING_COMMAND,
        outingCommandPayload(entity().mutationPayload(), true, null, null).toString(), 1)
    fun applied(entry: OutboxEntity): JSONObject = row().apply {
        val values = outingCommandOperations(entry).getJSONObject(0).getJSONObject("values")
        values.keys().forEach { put(it, values.get(it)) }
        put("updated_at", "2026-10-08T14:00:00Z")
    }
    fun receipt(entry: OutboxEntity, row: JSONObject = applied(entry)) = JSONObject().put("operationId", entry.id)
        .put("rows", JSONArray().put(JSONObject().put("table", "cinema_outings")
            .put("key", JSONObject().put("id", outing)).put("row", row)))
    fun envelope(entry: OutboxEntity, current: JSONObject? = applied(entry)) = JSONObject().put("receipt", receipt(entry))
        .put("current", current ?: JSONObject.NULL)
}
