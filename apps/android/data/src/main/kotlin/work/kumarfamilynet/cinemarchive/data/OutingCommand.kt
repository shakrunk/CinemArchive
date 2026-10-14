package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity

internal const val OUTING_COMMAND = "command_v2"
internal const val OUTING_COMMAND_DATA = "outingCommand"

/** Every outing producer shares the same causal admission rule, inside its Room transaction. */
internal suspend fun MutationOutbox.enqueueOutingCommand(entity: CinemaOutingEntity, previous: CinemaOutingEntity? = null) {
    val payload = entity.mutationPayload(previous)
    if (previous != null && payload.length() == 2) return
    val pending = pendingEntries()
    val completion = pending.firstOrNull { it.entityType == "outing_completion" && it.entityId == entity.id && it.operation == OUTING_COMPLETION }
    if (previous != null && completion != null) {
        val fields = JSONObject(payload.toString()).also { it.remove("id"); it.remove("updatedAt") }
        val typed = runCatching { awaitingCompletionPayload(completion, "cinema_outing", "update", fields) }.getOrNull()
        if (typed != null) {
            typed.put("id", entity.id).put("titleId", entity.titleId)
            enqueue("cinema_outing", entity.id, AWAITING_COMPLETION, typed)
        } else enqueue("cinema_outing", entity.id, "review", payload)
        return
    }
    require(outingOwnerScope != null || pending.none { it.entityType == TICKET_COMMAND_ENTITY && it.entityId == entity.id }) {
        "The ticket owner scope is unavailable; this change was not saved."
    }
    val guard = previous?.let { resolveOutingPrecondition(it, pending, outingOwnerScope) }
    enqueue("cinema_outing", entity.id, if (guard is OutingPrecondition.Review) "review" else OUTING_COMMAND,
        if (guard is OutingPrecondition.Review) payload else outingCommandPayload(payload, previous == null,
            (guard as? OutingPrecondition.Literal)?.updatedAt, (guard as? OutingPrecondition.Operation)?.operationId))
}

/** The wire request is captured once, alongside the optimistic row in its Room transaction. */
internal fun outingCommandPayload(intent: JSONObject, create: Boolean, baseline: String?, predecessor: String?): JSONObject {
    require(create || (baseline != null) != (predecessor != null))
    val values = outingWireBody(intent, "unused", insert = create)
    listOf("id", "user_id", "updated_at").forEach(values::remove)
    val operation = JSONObject().put("table", "cinema_outings").put("action", if (create) "insert" else "update")
        .put("key", JSONObject().put("id", intent.getString("id"))).put("values", values)
    if (!create) {
        if (predecessor != null) operation.put("expectedOperationId", predecessor)
        else { Instant.parse(baseline); operation.put("expectedUpdatedAt", baseline) }
    }
    return JSONObject(intent.toString()).put(OUTING_COMMAND_DATA,
        JSONObject().put("version", 2).put("operations", JSONArray().put(operation)))
}

/** Reject malformed persisted commands rather than regenerating different intent on retry. */
internal fun outingCommandOperations(entry: OutboxEntity): JSONArray {
    require(entry.entityType == "cinema_outing" && entry.operation == OUTING_COMMAND)
    val payload = JSONObject(entry.payloadJson)
    require(payload.getString("id") == entry.entityId)
    val envelope = payload.getJSONObject(OUTING_COMMAND_DATA)
    require(envelope.getInt("version") == 2)
    val operations = envelope.getJSONArray("operations")
    require(operations.length() == 1)
    val op = operations.getJSONObject(0)
    require(op.getString("table") == "cinema_outings" && op.getJSONObject("key").getString("id") == entry.entityId)
    require(op.getJSONObject("key").length() == 1)
    require(op.getString("action") in setOf("insert", "update"))
    require(op.keys().asSequence().all { it in setOf("table", "action", "key", "values", "expectedUpdatedAt", "expectedOperationId") })
    if (op.getString("action") == "update") {
        require(op.has("expectedUpdatedAt") != op.has("expectedOperationId"))
        if (op.has("expectedUpdatedAt")) Instant.parse(op.getString("expectedUpdatedAt"))
        else require(op.getString("expectedOperationId").isNotBlank() && op.getString("expectedOperationId") != entry.id)
    } else require(!op.has("expectedUpdatedAt") && !op.has("expectedOperationId"))
    val expected = outingWireBody(payload, "unused", insert = op.getString("action") == "insert")
    listOf("id", "user_id", "updated_at").forEach(expected::remove)
    require(sameCommandJson(expected, op.getJSONObject("values"))) { "Saved outing command differs from its retained intent." }
    return operations
}
