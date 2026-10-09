package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.util.UUID
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

internal const val OUTING_REVERSAL = "revert_v1"

internal data class OutingReversalCommand(val outingId: String, val titleId: String, val viewingId: String?,
    val outingGuard: ViewingGuard, val viewingGuard: ViewingGuard) {
    init {
        UUID.fromString(outingId); UUID.fromString(titleId); viewingId?.let(UUID::fromString)
        require(outingGuard.known && (viewingId != null || !viewingGuard.known))
    }
    fun persisted() = JSONObject().put("version", 1).put("outingId", outingId).put("titleId", titleId)
        .put("viewingId", viewingId ?: JSONObject.NULL).put("outingGuard", outingGuard.json()).put("viewingGuard", viewingGuard.json())
    fun request() = JSONObject().put("kind", "outing.revert").put("outingId", outingId)
        .put("expectedUpdatedAt", outingGuard.revision ?: JSONObject.NULL)
        .put("expectedViewingId", viewingId ?: JSONObject.NULL).put("expectedViewingUpdatedAt", viewingGuard.revision ?: JSONObject.NULL)
        .also { row -> outingGuard.operationId?.let { row.put("expectedOperationId", it) }
            viewingGuard.operationId?.let { row.put("expectedViewingOperationId", it) } }
    fun rpc(id: String) = JSONObject().put("p_outing_id", outingId).put("p_operation_id", id)
        .put("p_expected_updated_at", outingGuard.revision ?: JSONObject.NULL).put("p_expected_operation_id", outingGuard.operationId ?: JSONObject.NULL)
        .put("p_expected_viewing_id", viewingId ?: JSONObject.NULL).put("p_expected_viewing_updated_at", viewingGuard.revision ?: JSONObject.NULL)
        .put("p_expected_viewing_operation_id", viewingGuard.operationId ?: JSONObject.NULL)
}

internal fun reversalCommand(entry: OutboxEntity): OutingReversalCommand {
    require(entry.entityType == "outing_reversal" && entry.operation == OUTING_REVERSAL)
    UUID.fromString(entry.id)
    val value = JSONObject(entry.payloadJson).getJSONObject("reversalCommand")
    require(value.keys().asSequence().toSet() == setOf("version", "outingId", "titleId", "viewingId", "outingGuard", "viewingGuard"))
    require(value.getInt("version") == 1)
    fun guard(name: String) = value.getJSONObject(name).let {
        require(it.keys().asSequence().toSet() == setOf("revision", "operationId"))
        ViewingGuard(if (it.isNull("revision")) null else it.getString("revision"), if (it.isNull("operationId")) null else it.getString("operationId"))
    }
    return OutingReversalCommand(value.getString("outingId"), value.getString("titleId"),
        if (value.isNull("viewingId")) null else value.getString("viewingId"), guard("outingGuard"), guard("viewingGuard")).also {
        require(it.outingId == entry.entityId && it.outingGuard.operationId != entry.id && it.viewingGuard.operationId != entry.id)
    }
}

internal fun checkedReversalResponse(entry: OutboxEntity, response: JSONObject, ownerId: String): String {
    val command = reversalCommand(entry)
    require(JSONObject(entry.payloadJson).getString("ownerId") == ownerId)
    require(response.getString("operationId") == entry.id && response.getString("outingId") == command.outingId)
    val actual = JSONObject(response.getJSONObject("request").toString())
    for ((key, revision) in listOf("expectedUpdatedAt" to command.outingGuard.revision, "expectedViewingUpdatedAt" to command.viewingGuard.revision)) {
        if (revision != null) { require(Instant.parse(actual.getString(key)) == Instant.parse(revision)); actual.put(key, revision) }
    }
    require(sameCommandJson(actual, command.request())) { "Reversal receipt changed the captured request." }
    val status = response.getString("status").also { require(it in setOf("applied", "conflict", "missing")) }
    if (status == "applied") {
        require((if (response.isNull("canonicalViewingId")) null else response.getString("canonicalViewingId")) == command.viewingId)
        require(response.get("titleStatusRestored") is Boolean)
        val rows = response.getJSONArray("rows")
        require(rows.length() == 2)
        for ((index, table, id) in listOf(Triple(0, "cinema_outings", command.outingId), Triple(1, "titles", command.titleId))) {
            val effect = rows.getJSONObject(index)
            require(effect.getString("table") == table && !effect.optBoolean("deleted") &&
                sameCommandJson(effect.getJSONObject("key"), JSONObject().put("id", id)))
            val row = effect.getJSONObject("row")
            require(row.getString("id") == id && row.getString("user_id") == ownerId)
            Instant.parse(row.getString("updated_at"))
            if (index == 0) require(row.getString("title_id") == command.titleId && row.getString("status") == "missed" && row.isNull("completed_viewing_id"))
        }
    }
    return status
}
