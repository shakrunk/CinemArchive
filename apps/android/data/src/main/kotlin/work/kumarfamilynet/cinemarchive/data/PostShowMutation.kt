package work.kumarfamilynet.cinemarchive.data

import java.util.UUID
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.database.ViewingEntity
import work.kumarfamilynet.cinemarchive.core.database.ViewingCompletionAliasEntity
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

/** Both sides of reversal are captured when the sheet opens, not on its asynchronous Save. */
internal fun captureReversalContext(outing: CinemaOutingEntity, viewing: ViewingEntity, queue: List<OutboxEntity>,
    owner: TicketOwnerScope): String? {
    if (outing.status != "COMPLETED" || outing.completedViewingId != viewing.id || viewing.rating != null) return null
    require(viewing.titleId == outing.titleId && (viewing.outingId == null || viewing.outingId == outing.id))
    val completion = queue.firstOrNull { it.entityType == "outing_completion" && it.entityId == outing.id && it.operation == OUTING_COMPLETION }
    val relatedOutings = queue.filter { it.entityType == "cinema_outing" && it.entityId == outing.id }
    val outingGuard = if (completion != null) {
        if (relatedOutings.all { it.operation == AWAITING_COMPLETION && runCatching { checkedCompletionIntent(it, completion) }.isSuccess })
            ViewingGuard(operationId = relatedOutings.lastOrNull()?.id ?: completion.id) else ViewingGuard()
    } else when (val guard = resolveOutingPrecondition(outing, queue, owner)) {
        is OutingPrecondition.Literal -> ViewingGuard(revision = guard.updatedAt)
        is OutingPrecondition.Operation -> ViewingGuard(operationId = guard.operationId)
        is OutingPrecondition.Review -> ViewingGuard()
    }
    val viewingGuard = captureViewingGuard(viewing, null, queue, owner.ownerId)
    val payload = JSONObject().put("ownerId", owner.ownerId).put("titleId", outing.titleId)
        .put("token", UUID.randomUUID().toString()).put("outingId", outing.id).put("viewingId", viewing.id)
        .put("outingGuard", outingGuard.json()).put("viewingGuard", viewingGuard.json())
        .put("originalOuting", outing.mutationPayload()).put("originalViewing", JSONObject()
            .put("id", viewing.id).put("titleId", viewing.titleId).put("rating", viewing.rating ?: JSONObject.NULL))
    if (completion != null) payload.put("completion", capturedCompletion(completion))
    return canonicalViewingJson(payload)
}

internal fun reversalFromOpening(raw: String, ownerId: String): Pair<String, JSONObject> {
    val saved = JSONObject(raw)
    require(saved.getString("ownerId") == ownerId)
    for (key in listOf("token", "outingId", "titleId", "viewingId")) UUID.fromString(saved.getString(key))
    fun guard(key: String) = saved.getJSONObject(key).let { value ->
        require(value.keys().asSequence().toSet() == setOf("revision", "operationId"))
        ViewingGuard(if (value.isNull("revision")) null else value.getString("revision"), if (value.isNull("operationId")) null else value.getString("operationId"))
    }
    val outingGuard = guard("outingGuard")
    val viewingGuard = guard("viewingGuard")
    val payload = JSONObject().put("ownerId", ownerId).put("titleId", saved.getString("titleId")).put("reversalOpening", saved)
    val completion = saved.optJSONObject("completion")?.let(::restoredCompletion)
    if (completion != null) {
        require(JSONObject(completion.payloadJson).getString("ownerId") == ownerId)
        val command = completionCommand(completion)
        require(command.outingId == saved.getString("outingId") && command.titleId == saved.getString("titleId") && command.provisionalViewingId == saved.getString("viewingId"))
        payload.put("completionIntent", awaitingCompletionPayload(completion, "outing_reversal", "revert", JSONObject()).getJSONObject("completionIntent"))
    }
    if (outingGuard.known && viewingGuard.known) payload.put("reversalCommand", OutingReversalCommand(saved.getString("outingId"), saved.getString("titleId"),
        saved.getString("viewingId"), outingGuard, viewingGuard).persisted())
    return (if (completion != null) AWAITING_COMPLETION else if (payload.has("reversalCommand")) OUTING_REVERSAL else "review") to payload
}

internal fun convertCapturedReversal(entry: OutboxEntity, alias: ViewingCompletionAliasEntity, ownerId: String): OutboxEntity {
    val source = JSONObject(entry.payloadJson)
    val opening = source.getJSONObject("reversalOpening")
    val completion = restoredCompletion(opening.getJSONObject("completion"))
    require(alias.completionOperationId == completion.id && alias.provisionalViewingId == opening.getString("viewingId") &&
        alias.titleId == opening.getString("titleId") && alias.outingId == entry.entityId && alias.canonicalViewingVersion != null)
    checkedCompletionIntent(entry.copy(operation = AWAITING_COMPLETION, attemptCount = 0), completion)
    val payload = reversalFromOpening(canonicalViewingJson(opening), ownerId).second
    val command = reversalCommand(entry.copy(operation = OUTING_REVERSAL, payloadJson = payload.toString()))
    payload.put("reversalCommand", command.copy(viewingId = alias.canonicalViewingId).persisted())
    payload.remove("completionIntent")
    payload.put("completionSource", source)
    return entry.copy(operation = OUTING_REVERSAL, payloadJson = canonicalViewingJson(payload))
}
