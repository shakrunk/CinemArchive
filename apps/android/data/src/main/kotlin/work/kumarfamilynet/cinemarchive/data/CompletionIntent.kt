package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.database.ViewingCompletionAliasEntity

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

internal fun capturedCompletion(entry: OutboxEntity): JSONObject = JSONObject().put("id", entry.id)
    .put("outingId", entry.entityId).put("payload", JSONObject(entry.payloadJson))

internal fun restoredCompletion(saved: JSONObject): OutboxEntity {
    require(saved.keys().asSequence().toSet() == setOf("id", "outingId", "payload"))
    return OutboxEntity(saved.getString("id"), "outing_completion", saved.getString("outingId"), OUTING_COMPLETION,
        saved.getJSONObject("payload").toString(), 0).also(::completionCommand)
}

/** Retarget only a never-dispatched captured form; its original guards and operation ID survive. */
internal fun convertCapturedCompletionViewing(entry: OutboxEntity, alias: ViewingCompletionAliasEntity, ownerId: String): OutboxEntity {
    val source = JSONObject(entry.payloadJson)
    val opening = source.getJSONObject(VIEWING_OPENING)
    val completion = restoredCompletion(opening.getJSONObject("completion"))
    val command = completionCommand(completion)
    require(opening.getString("ownerId") == ownerId && JSONObject(completion.payloadJson).getString("ownerId") == ownerId)
    require(alias.completionOperationId == completion.id && alias.provisionalViewingId == command.provisionalViewingId &&
        alias.titleId == command.titleId && alias.outingId == command.outingId && alias.canonicalViewingVersion != null)
    val intent = checkedCompletionIntent(entry.copy(operation = AWAITING_COMPLETION, attemptCount = 0), completion)
    val guard = opening.getJSONObject("guard")
    require(!guard.isNull("operationId") && guard.isNull("revision")) { "The original viewing revision is unknown." }
    val payload = viewingCommandPayload(alias.canonicalViewingId, command.titleId, intent.action, intent.fields, null, guard.getString("operationId"))
    viewingTitleEntry(entry, ownerId)?.let { effect ->
        // Keep the form's title guard, including a prior independent title command, byte-for-byte.
        titleMetadataOperation(effect, ownerId)
        attachViewingTitleEffect(payload, JSONObject(effect.payloadJson))
    }
    for (key in listOf(VIEWING_OPENING, "linkedOutings")) if (source.has(key)) payload.put(key, source.get(key))
    payload.put("completionSource", source).put("completionCanonicalViewingId", alias.canonicalViewingId)
    return entry.copy(entityId = alias.canonicalViewingId, operation = VIEWING_COMMAND, payloadJson = canonicalViewingJson(payload))
}

internal fun checkedCompletionIntent(entry: OutboxEntity, completion: OutboxEntity): CompletionIntent {
    require(entry.operation == AWAITING_COMPLETION && entry.attemptCount == 0 && entry.id != completion.id)
    val command = completionCommand(completion)
    val saved = JSONObject(entry.payloadJson).getJSONObject("completionIntent")
    require(saved.keys().asSequence().toSet() == setOf("version", "completionOperationId", "outingId", "titleId", "provisionalViewingId", "action", "fields"))
    require(saved.getInt("version") == 1 && saved.getString("completionOperationId") == completion.id &&
        saved.getString("outingId") == command.outingId && saved.getString("titleId") == command.titleId &&
        saved.getString("provisionalViewingId") == command.provisionalViewingId)
    require(entry.entityId == when (entry.entityType) { "viewing" -> command.provisionalViewingId; "cinema_outing", "outing_reversal" -> command.outingId; else -> error("Unsupported completion intent") })
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
        "outing_reversal" -> require(action == "revert" && fields.length() == 0)
        else -> error("Unsupported completion intent")
    }
}
