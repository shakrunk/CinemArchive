package work.kumarfamilynet.cinemarchive.data

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

/** Explicit comparison for rejected lifecycle intents, and exact-ID confirmation for unknown outcomes. */
class OutingLifecycleRecovery(
    private val database: LibraryDatabase,
    private val outbox: MutationOutbox,
    private val ownerId: String,
    private val client: SupabaseRestClient,
    private val session: () -> SupabaseSession?,
    private val archive: OutingRecoveryArchive,
    private val replayBoundary: suspend (suspend () -> Unit) -> Unit,
    private val synchronize: suspend () -> Unit,
) : OutingRecoverySource {
    override val changes = combine(database.outboxDao().observePending(), archive.records) { _, _ -> Unit }
    override fun isActive() = session()?.userId == ownerId
    private fun active(): SupabaseSession = checkNotNull(session()?.takeIf { it.userId == ownerId }) { "This sign-in has ended." }
    private fun owns(entry: OutboxEntity) = entry.entityType in setOf("outing_completion", "outing_reversal") &&
        JSONObject(entry.payloadJson).getString("ownerId") == ownerId
    private suspend fun entry(id: String): OutboxEntity = checkNotNull(database.outboxDao().getPending().firstOrNull { it.id == id && owns(it) }) {
        "This saved action is already resolved. Refresh the list."
    }
    private fun title(entry: OutboxEntity) = JSONObject(entry.payloadJson).getString("titleId")
    private fun label(entry: OutboxEntity) = if (entry.entityType == "outing_completion") "Complete outing" else "Undo completion"
    private suspend fun displayTitle(entry: OutboxEntity) = database.titleDao().getById(title(entry))?.title ?: label(entry)
    override suspend fun items(): List<OutingRecoveryCard> {
        active()
        val pending = database.outboxDao().getPending().filter(::owns)
        return pending.map { OutingRecoveryCard(it.id, displayTitle(it), false) } + archive.records.first().mapNotNull { (id, raw) ->
            if (pending.any { it.id == id }) null else {
                val state = runCatching { JSONObject(raw).optString("state") }.getOrNull()
                OutingRecoveryCard(id, "Preserved outing action", true,
                    error = if (state in setOf("reapplied", "discarded", "confirmed")) null else
                        "Recovery was interrupted after preserving this action. Its original data remains available to export.")
            }
        }
    }

    private suspend fun current(entry: OutboxEntity): JSONObject = withContext(Dispatchers.IO) {
        val signedIn = active()
        val outing = fetchViewingLinkedOutings(client, signedIn, listOf(entry.entityId)) { active() }
        val payload = JSONObject(entry.payloadJson)
        val provisional = payload.optString("provisionalViewingId").takeIf(String::isNotBlank)
            ?: payload.optJSONObject("reversalOpening")?.getString("viewingId")
        val alias = provisional?.let { database.viewingCompletionAliasDao().byProvisionalId(it) }
        val viewingId = alias?.canonicalViewingId ?: provisional
        val viewing = if (viewingId == null) null else JSONArray(client.get("viewings", "id=eq.$viewingId&user_id=eq.$ownerId&select=*", signedIn.accessToken)).let {
            require(it.length() <= 1)
            if (it.length() == 0) null else it.getJSONObject(0).also { row ->
                require(row.getString("id") == viewingId && row.getString("user_id") == ownerId && row.getString("title_id") == title(entry))
                row.toCompletionViewing()
            }
        }
        val fullTitle = fetchViewingTitle(client, signedIn, title(entry)) { active() }
        checkedViewingLinkedOutings(listOf(entry.entityId), title(entry), ownerId, outing)
        active()
        JSONObject().put("outings", outing).put("viewingId", viewingId ?: JSONObject.NULL)
            .put("viewing", viewing ?: JSONObject.NULL).put("title", fullTitle ?: JSONObject.NULL)
    }

    override suspend fun pendingAttempt(id: String): Boolean {
        active()
        return database.outboxDao().getPending().firstOrNull { it.id == id }?.let { owns(it) && it.operation !in setOf("review", AWAITING_COMPLETION) } ?: false
    }
    override suspend fun review(id: String): OutingRecoveryReview = outbox.withFlushPaused {
        active()
        val entry = database.outboxDao().getPending().firstOrNull { it.id == id && owns(it) }
        if (entry == null) return@withFlushPaused OutingRecoveryReview(id, "Preserved outing action", emptyList(), null, false, false, true)
        val snapshot = current(entry)
        val row = snapshot.getJSONObject("outings").optJSONObject(entry.entityId)
        val token = canonicalViewingJson(snapshot)
        val unknown = pendingAttempt(id)
        val event = snapshot.optJSONObject("viewing")
        fun eventText(key: String) = if (event == null || event.isNull(key)) "None" else event.get(key).toString()
        OutingRecoveryReview(id, displayTitle(entry), listOf(
            OutingRecoveryField("lifecycleAction", label(entry), label(entry), row?.optString("status") ?: "Plan removed", !unknown && row != null),
            OutingRecoveryField("showtime", "Showtime", JSONObject(entry.payloadJson).optJSONObject("originalOuting")?.optString("showtime").orEmpty(), row?.optString("showtime").orEmpty(), false),
            OutingRecoveryField("date", "Viewing date", "Linked outing viewing", if (event == null) "Not present" else eventText("viewed_at"), false),
            OutingRecoveryField("rating", "Viewing rating", "Preserve other viewing history", eventText("rating"), false),
            OutingRecoveryField("notes", "Viewing notes", "Preserve other viewing history", eventText("notes"), false),
            OutingRecoveryField("venue", "Theater", "Saved outing", row?.optString("venue").orEmpty(), false)),
            token, row != null, unknown, false,
            listOfNotNull(entry.lastError, if (unknown) "Confirm the original operation before changing or discarding it." else
                "Review the current plan. Other saved edits remain preserved for their own review.").joinToString("\n"))
    }

    private suspend fun preserve(entry: OutboxEntity): JSONObject {
        val previous = archive.record(entry.id)
        val record = previous ?: entry.originalRecord()
        active(); archive.put(entry.id, record.toString()); active()
        return record
    }
    private suspend fun finishRecord(id: String, record: JSONObject, state: String) {
        active(); archive.put(id, record.put("state", state).toString()); active()
    }

    override suspend fun apply(id: String, expectedVersion: String?, selected: Set<String>): OutingRecoveryOutcome {
        var result = OutingRecoveryOutcome.CHANGED
        replayBoundary { outbox.withFlushPaused {
            active()
            val original = entry(id)
            require(database.outboxDao().getPending().firstOrNull()?.id == id) { "Resolve the earlier saved action first, then retry this one." }
            val record = preserve(original)
            if (pendingAttempt(id)) {
                val response = if (original.entityType == "outing_completion") OutingCompletionTransport(client, session).push(original)
                    else OutingReversalTransport(client, session).push(original)
                when (response) {
                    is PushResult.Applied -> {
                        outbox.atomically {
                            active(); require(entry(id) == original)
                            if (original.entityType == "outing_completion") OutingCompletionApplier(database, ownerId).apply(original, response.receipt)
                            else OutingReversalApplier(database, ownerId).apply(original, response.receipt)
                            active(); database.outboxDao().remove(id)
                        }
                        finishRecord(id, record, "confirmed"); result = OutingRecoveryOutcome.CONFIRMED
                    }
                    is PushResult.Review -> { active(); database.outboxDao().markForReview(id, response.reason); result = OutingRecoveryOutcome.CHANGED }
                    is PushResult.Retry -> error(response.reason)
                    else -> error("The original action could not be confirmed.")
                }
                return@withFlushPaused
            }
            require(original.operation == "review") { "Confirm the earlier completion before reviewing this dependent action." }
            require(selected == setOf("lifecycleAction")) { "Select the complete action explicitly." }
            val snapshot = current(original)
            if (expectedVersion != canonicalViewingJson(snapshot)) return@withFlushPaused
            val row = snapshot.getJSONObject("outings").optJSONObject(original.entityId)
            if (row == null) { result = OutingRecoveryOutcome.MISSING; return@withFlushPaused }
            val payload = JSONObject(original.payloadJson)
            val newId = UUID.randomUUID().toString()
            val operation: String
            if (original.entityType == "outing_completion") {
                val command = OutingCompletionCommand(original.entityId, title(original), payload.getString("provisionalViewingId"),
                    row.getString("updated_at"), null, payload.getString("timezone"))
                payload.put(COMPLETION_COMMAND_DATA, command.persisted())
                operation = OUTING_COMPLETION
            } else {
                val viewingId = snapshot.getString("viewingId")
                val viewing = snapshot.optJSONObject("viewing")
                require(viewing == null || viewing.isNull("rating")) { "A rated viewing cannot be undone as a missed outing." }
                require(row.isNull("completed_viewing_id") || row.getString("completed_viewing_id") == viewingId) { "The plan now links another viewing. Preserve this action and review that history separately." }
                payload.put("reversalCommand", OutingReversalCommand(original.entityId, title(original), viewingId,
                    ViewingGuard(revision = row.getString("updated_at")), ViewingGuard(revision = viewing?.getString("updated_at"))).persisted())
                operation = OUTING_REVERSAL
            }
            outbox.atomically {
                active(); require(entry(id) == original)
                preserveDependents(original)
                check(database.completionQueueDao().replaceReviewedLifecycle(id, original.entityType, original.payloadJson, newId, operation, canonicalViewingJson(payload)) == 1)
                active()
            }
            finishRecord(id, record, "reapplied"); result = OutingRecoveryOutcome.APPLIED
        } }
        if (result in setOf(OutingRecoveryOutcome.APPLIED, OutingRecoveryOutcome.CONFIRMED)) synchronize()
        return result
    }

    private suspend fun preserveDependents(original: OutboxEntity) {
        database.outboxDao().getPending().filter { it.operation == AWAITING_COMPLETION &&
            JSONObject(it.payloadJson).optJSONObject("completionIntent")?.optString("completionOperationId") == original.id }.forEach {
            check(database.completionQueueDao().resolveAwaiting(it.id, it.payloadJson, it.entityId, "review", it.payloadJson,
                "The original completion was reviewed. Compare this preserved dependent change separately.") == 1)
        }
    }
    override suspend fun discard(id: String) {
        replayBoundary { outbox.withFlushPaused {
            val original = entry(id); require(original.operation == "review") { "Confirm the original operation before discarding it." }
            val snapshot = current(original)
            val record = preserve(original)
            outbox.atomically {
                active(); require(entry(id) == original)
                preserveDependents(original)
                database.outboxDao().remove(id)
                val later = database.outboxDao().getPending()
                val rows = checkedViewingLinkedOutings(listOf(original.entityId), title(original), ownerId, snapshot.getJSONObject("outings"))
                applyViewingLinkedOutings(database, title(original), rows, later)
                applyCurrentViewingTitle(database, title(original), snapshot.optJSONObject("title"), later, ownerId)
                if (original.entityType == "outing_completion") {
                    val provisional = JSONObject(original.payloadJson).getString("provisionalViewingId")
                    if (database.viewingCompletionAliasDao().byProvisionalId(provisional) == null && snapshot.optJSONObject("viewing") == null) {
                        database.viewingDao().getById(provisional)?.takeIf { it.titleId == title(original) && it.outingId == original.entityId }?.let {
                            database.viewingDao().deleteById(provisional)
                        }
                    }
                }
                active()
            }
            finishRecord(id, record, "discarded")
        } }
        synchronize()
    }
    override suspend fun exportOriginal(id: String): String = outbox.withFlushPaused {
        active()
        archive.records.first()[id] ?: preserve(entry(id)).toString()
    }
}
