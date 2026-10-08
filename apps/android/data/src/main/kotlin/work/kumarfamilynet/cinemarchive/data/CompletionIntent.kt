package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

internal const val AWAITING_COMPLETION = "await_completion_v1"
private val outingIntentFields = setOf("showtime", "previewsMinutes", "runtimeMinutes", "endsAt", "venue", "companions", "format",
    "ticketPrice", "seat", "auditorium", "seatRow", "seats", "bookingRef", "notes", "completedViewingId", "followUpDismissedAt")

/** This payload cannot be sent. Only a validated completion ACK may assign its canonical target. */
internal fun awaitingCompletionPayload(completion: OutboxEntity, entityType: String, action: String, fields: JSONObject): JSONObject {
    val command = completionCommand(completion)
    validateAwaitingFields(entityType, action, fields)
    return JSONObject().put("completionIntent", JSONObject().put("version", 1).put("completionOperationId", completion.id)
        .put("outingId", command.outingId).put("titleId", command.titleId).put("provisionalViewingId", command.provisionalViewingId)
        .put("action", action).put("fields", JSONObject(fields.toString())))
}

internal data class CompletionIntent(val entry: OutboxEntity, val action: String, val fields: JSONObject)

internal fun checkedCompletionIntent(entry: OutboxEntity, completion: OutboxEntity): CompletionIntent {
    require(entry.operation == AWAITING_COMPLETION && entry.attemptCount == 0 && entry.id != completion.id)
    val command = completionCommand(completion)
    val saved = JSONObject(entry.payloadJson).getJSONObject("completionIntent")
    require(saved.keys().asSequence().toSet() == setOf("version", "completionOperationId", "outingId", "titleId", "provisionalViewingId", "action", "fields"))
    require(saved.getInt("version") == 1 && saved.getString("completionOperationId") == completion.id &&
        saved.getString("outingId") == command.outingId && saved.getString("titleId") == command.titleId &&
        saved.getString("provisionalViewingId") == command.provisionalViewingId)
    require(entry.entityId == when (entry.entityType) { "viewing" -> command.provisionalViewingId; "cinema_outing" -> command.outingId; else -> error("Unsupported completion intent") })
    val action = saved.getString("action")
    val fields = saved.getJSONObject("fields")
    validateAwaitingFields(entry.entityType, action, fields)
    return CompletionIntent(entry, action, fields)
}

private fun validateAwaitingFields(entityType: String, action: String, fields: JSONObject) {
    when (entityType) {
        "viewing" -> {
            require(action in setOf("update", "delete"))
            if (action == "delete") require(fields.length() == 0)
            else { require(fields.length() > 0); viewingWireValues(fields) }
        }
        "cinema_outing" -> {
            require(action == "update" && fields.length() > 0 && fields.keys().asSequence().all { it in outingIntentFields })
            if (fields.has("completedViewingId")) require(fields.isNull("completedViewingId"))
            outingWireBody(fields, "unused", false)
        }
        else -> error("Unsupported completion intent")
    }
}
