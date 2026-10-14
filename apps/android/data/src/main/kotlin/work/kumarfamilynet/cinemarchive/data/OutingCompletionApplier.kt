package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

/** Runs in the outbox's Room ACK transaction. No network IO or historical row replay. */
class OutingCompletionApplier(private val database: LibraryDatabase, private val ownerId: String) {
    suspend fun apply(entry: OutboxEntity, envelope: JSONObject) {
        check(database.inTransaction()) { "Completion acknowledgment requires one Room transaction." }
        val command = completionCommand(entry)
        val checked = checkedCompletionEnvelope(entry, envelope, ownerId)
        val alias = requireNotNull(checked.alias)
        val queue = database.outboxDao().getPending()
        require(queue.firstOrNull()?.let { it.id == entry.id && it.payloadJson == entry.payloadJson && it.operation == entry.operation } == true)
        val related = queue.drop(1).filter {
            (it.entityType == "viewing" && it.entityId in setOf(command.provisionalViewingId, alias.canonicalViewingId)) ||
                (it.entityType in setOf("cinema_outing", "outing_reversal") && it.entityId == command.outingId)
        }
        // HTTP-capable intent is immutable even if its first response was lost before an
        // attempt counter was stored. Only the never-dispatchable typed state can be rewritten.
        require(related.all { it.operation == "review" || (it.operation == AWAITING_COMPLETION && it.attemptCount == 0) }) {
            "Existing viewing or outing delivery must be resolved before completion can be reconciled."
        }
        val intents = related.filter { it.operation == AWAITING_COMPLETION }.map { checkedCompletionIntent(it, entry) }
        val viewings = database.viewingDao()
        val provisional = viewings.getById(command.provisionalViewingId)
        val canonical = viewings.getById(alias.canonicalViewingId)
        provisional?.let { require(it.titleId == command.titleId &&
            (it.outingId == command.outingId || (it.id == alias.canonicalViewingId && it.outingId == null))) }
        canonical?.let { require(it.titleId == command.titleId && (it.outingId == null || it.outingId == command.outingId)) }
        val localOuting = database.cinemaOutingDao().getById(command.outingId)
        localOuting?.let { require(it.titleId == command.titleId) }
        val currentViewing = checked.viewing?.let { JSONObject(it.toString()) }
        val currentOuting = checked.outing?.let { JSONObject(it.toString()) }
        var viewingDeleted = false
        var retainProvisional = related.any { it.entityType == "viewing" && it.operation == "review" }
        var retainOuting = related.any { it.entityType != "viewing" && it.operation == "review" }
        var viewingPredecessor = entry.id
        var outingPredecessor = entry.id
        var titlePredecessor = entry.id

        val aliases = database.viewingCompletionAliasDao()
        val existing = aliases.byProvisionalId(alias.provisionalViewingId)
        val sameOperation = aliases.byCompletionOperationId(alias.completionOperationId)
        require(existing == null || existing == alias) { "Completion identity cannot be rebound." }
        require(sameOperation == null || sameOperation == alias) { "Completion operation cannot be rebound." }
        if (existing == null) aliases.insert(alias)

        for (intent in intents) {
            val pending = intent.entry
            val isViewing = pending.entityType == "viewing"
            val isReversal = pending.entityType == "outing_reversal"
            val titleEffect = if (isViewing) viewingTitleEntry(pending, ownerId) else null
            val savedOpening = JSONObject(pending.payloadJson).optJSONObject(VIEWING_OPENING)
            val titleGuardValid = titleEffect == null || runCatching { titleMetadataOperation(titleEffect, ownerId) }.isSuccess
            val titleDependsOnCompletion = titleEffect?.takeIf { titleGuardValid }?.let {
                titleMetadataOperation(it, ownerId).optString("expectedOperationId") == entry.id
            } ?: false
            val needsReview = if (isViewing) currentViewing == null || alias.canonicalViewingVersion == null || viewingDeleted ||
                !titleGuardValid || (titleDependsOnCompletion && checked.completionTitleVersion == null) ||
                (savedOpening != null && runCatching { convertCapturedCompletionViewing(pending, alias, ownerId) }.isFailure)
                else currentOuting == null || checked.completionOutingVersion == null ||
                    (isReversal && (currentViewing == null || alias.canonicalViewingVersion == null || viewingDeleted ||
                        runCatching { convertCapturedReversal(pending, alias, ownerId) }.isFailure))
            if (needsReview) {
                if (isViewing) retainProvisional = true else retainOuting = true
                check(database.completionQueueDao().resolveAwaiting(pending.id, pending.payloadJson, pending.entityId,
                    "review", pending.payloadJson, "The original completion revision or current event is unavailable. Compare the preserved intent before applying it.") == 1)
                continue
            }
            val payload: JSONObject
            val target: String
            val operation: String
            if (isReversal) {
                val original = JSONObject(pending.payloadJson)
                val opening = original.getJSONObject("reversalOpening")
                val saved = reversalFromOpening(canonicalViewingJson(opening), ownerId).second
                val reversal = reversalCommand(pending.copy(operation = OUTING_REVERSAL, payloadJson = saved.toString()))
                payload = JSONObject(saved.toString()).put("reversalCommand", reversal.copy(viewingId = alias.canonicalViewingId).persisted())
                payload.remove("completionIntent")
                target = command.outingId
                operation = OUTING_REVERSAL
                outingPredecessor = pending.id
                currentOuting!!.put("status", "missed").put("completed_viewing_id", JSONObject.NULL)
                viewingDeleted = true
            } else if (isViewing) {
                target = alias.canonicalViewingId
                payload = if (savedOpening != null) JSONObject(convertCapturedCompletionViewing(pending, alias, ownerId).payloadJson)
                    else viewingCommandPayload(target, command.titleId, intent.action, intent.fields, null, viewingPredecessor)
                if (titleEffect != null && savedOpening == null) {
                    attachViewingTitleEffect(payload, titleMetadataPayload(ownerId, command.titleId,
                        titleMetadataPatch(titleEffect, ownerId), null, titlePredecessor))
                    titlePredecessor = pending.id
                }
                val original = JSONObject(pending.payloadJson)
                for (key in listOf(VIEWING_OPENING, "linkedOutings")) if (original.has(key)) payload.put(key, original.get(key))
                operation = VIEWING_COMMAND
                viewingPredecessor = pending.id
                if (intent.action == "delete") viewingDeleted = true
                else viewingWireValues(intent.fields).let { patch -> patch.keys().forEach { currentViewing!!.put(it, patch.get(it)) } }
            } else {
                target = command.outingId
                payload = outingCommandPayload(JSONObject(intent.fields.toString()).put("id", target), false, null, outingPredecessor)
                operation = OUTING_COMMAND
                outingPredecessor = pending.id
                outingWireBody(intent.fields, ownerId, false).let { patch -> patch.keys().forEach { currentOuting!!.put(it, patch.get(it)) } }
            }
            payload.put("completionSource", JSONObject(pending.payloadJson))
            check(database.completionQueueDao().resolveAwaiting(pending.id, pending.payloadJson, target, operation, canonicalViewingJson(payload), null) == 1)
        }

        // Parse every new projection before touching local history. A malformed current row
        // rolls back alias insertion and every queue rewrite with the enclosing ACK.
        val projectedViewing = currentViewing?.takeUnless { viewingDeleted }?.toCompletionViewing()
        val projectedOuting = currentOuting?.toRecoveryOuting()
        val title = database.titleDao().getById(command.titleId)
        if (!retainProvisional && command.provisionalViewingId != alias.canonicalViewingId && provisional != null) {
            viewings.deleteById(command.provisionalViewingId)
        }
        if (title != null) {
            if (projectedViewing == null) {
                // Null comes from the exact owned GET, or an explicit typed pending delete.
                viewings.deleteById(alias.canonicalViewingId)
            } else if ((provisional != null || canonical != null) && !(retainProvisional && alias.canonicalViewingId == command.provisionalViewingId)) {
                viewings.upsert(projectedViewing)
            }
            if (localOuting != null && !retainOuting) {
                if (projectedOuting != null) database.cinemaOutingDao().upsert(projectedOuting)
                else database.cinemaOutingDao().deleteById(command.outingId)
            }
            // The exact owner viewing GET may be newer than the RPC's outing snapshot.
            if (checked.viewing == null || viewingDeleted) database.completionQueueDao().clearViewingLink(alias.canonicalViewingId)
            if (checked.title != null && queue.drop(1).none { (it.entityType == "title" && it.entityId == command.titleId) || hasViewingTitleEffect(it, command.titleId) }) {
                database.titleDao().updateStatus(command.titleId, checked.title.getString("status").uppercase(), checked.title.getString("updated_at"))
            }
        }
    }
}
