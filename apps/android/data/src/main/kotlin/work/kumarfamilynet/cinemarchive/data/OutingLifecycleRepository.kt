package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.ViewingEntity
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.model.PostShowOpening
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.OutingTransition

/** One durable command owns the optimistic completion and its provisional history. */
class OutingLifecycleRepository(
    private val database: LibraryDatabase,
    private val outbox: MutationOutbox,
    private val ownerId: String,
    private val isCurrentOwner: () -> Boolean,
) {
    internal fun active() {
        check(isCurrentOwner() && outbox.outingOwnerScope?.ownerId == ownerId) { "This sign-in has ended. Reopen the outing." }
    }

    suspend fun revert(opening: PostShowOpening) {
        active()
        val raw = checkNotNull(opening.reversalContext) { "This viewing cannot be reversed as an outing completion." }
        val (operation, payload) = reversalFromOpening(raw, ownerId)
        val saved = payload.getJSONObject("reversalOpening")
        require(saved.getString("viewingId") == opening.viewing.id && opening.viewing.rating == null)
        val originalBytes = canonicalViewingJson(payload)
        val id = capturedViewingOperationId(ownerId, saved.getString("token"), "reversal", originalBytes)
        outbox.atomically {
            active()
            if (outbox.pendingEntries().any { it.id == id }) {
                outbox.enqueueCaptured(id, "outing_reversal", saved.getString("outingId"), operation, originalBytes)
                return@atomically
            }
            var entry = OutboxEntity(id, "outing_reversal", saved.getString("outingId"), operation, originalBytes, 0)
            val alias = database.viewingCompletionAliasDao().byProvisionalId(opening.viewing.id)
            if (operation == AWAITING_COMPLETION && alias != null) entry = runCatching {
                convertCapturedReversal(entry, alias, ownerId)
            }.getOrElse { entry.copy(operation = "review") }
            val current = database.cinemaOutingDao().getById(entry.entityId)
            val viewingId = alias?.canonicalViewingId ?: opening.viewing.id
            val viewing = database.viewingDao().getById(viewingId)
            require(viewing == null || viewing.titleId == saved.getString("titleId"))
            check(viewing?.rating == null) { "A rated viewing cannot be undone as a missed outing." }
            outbox.enqueueCaptured(id, entry.entityType, entry.entityId, entry.operation, entry.payloadJson)
            if (current != null && current.status == "COMPLETED" && current.completedViewingId == viewingId) {
                database.cinemaOutingDao().upsert(current.copy(status = "MISSED", completedViewingId = null))
                database.viewingDao().deleteById(viewingId)
            }
            // The server alone can prove whether the old title status may be restored.
            active()
        }
    }

    suspend fun completeDue(now: Instant, zone: ZoneId = ZoneId.systemDefault()): List<OutingTransition> {
        active()
        val candidates = database.cinemaOutingDao().getScheduledOutings().map { it.id }
        return candidates.mapNotNull { id -> outbox.atomically {
            active()
            val outing = database.cinemaOutingDao().getById(id) ?: return@atomically null
            if (outing.status != "SCHEDULED" || Instant.parse(outing.endsAt) > now) return@atomically null
            val title = database.titleDao().getById(outing.titleId) ?: return@atomically null
            val pending = outbox.pendingEntries()
            if (pending.any { it.entityType == "outing_completion" && it.entityId == id }) return@atomically null
            // Independent historical events are not evidence that this scheduled plan completed.
            // Leave them intact and let explicit recovery expose the ambiguity.
            val prior = database.viewingDao().getByOutingId(id)
            val guard = resolveOutingPrecondition(outing, pending, outbox.outingOwnerScope)
            val provisional = UUID.randomUUID().toString()
            val operationId = UUID.randomUUID().toString()
            val command = if (guard !is OutingPrecondition.Review) OutingCompletionCommand(id, title.id, provisional,
                (guard as? OutingPrecondition.Literal)?.updatedAt, (guard as? OutingPrecondition.Operation)?.operationId, zone.id) else null
            val payload = JSONObject().put("ownerId", ownerId).put("titleId", title.id)
                .put("provisionalViewingId", provisional).put("timezone", zone.id)
                .put("originalOuting", outing.mutationPayload(null)).put("originalTitleStatus", title.status)
                .put("originalTitleVersion", title.updatedAt)
                .put("localTitleChanged", title.status != "WATCHED")
            if (command != null) payload.put(COMPLETION_COMMAND_DATA, command.persisted())
            else payload.put("reviewReason", (guard as OutingPrecondition.Review).reason)
            if (prior != null) payload.put("reviewReason", "This plan already has independent history. Compare it before completing the outing.")
            val admitted = outbox.enqueueCaptured(operationId, "outing_completion", id,
                if (command != null && prior == null) OUTING_COMPLETION else "review", payload.toString())
            check(admitted)
            // Unknown predecessors still retain a visible provisional event; they never create
            // an HTTP-capable mutation from an invented timestamp.
            database.viewingDao().upsert(ViewingEntity(provisional, title.id,
                Instant.parse(outing.showtime).atZone(zone).toLocalDate().toString(), null, null, outing.venue,
                companions = outing.companions, companionsJson = outing.companionsJson, outingId = id))
            database.cinemaOutingDao().upsert(outing.copy(status = "COMPLETED", previousStatus = title.status,
                completedViewingId = provisional))
            if (title.status != "WATCHED") database.titleDao().upsertAll(listOf(title.copy(status = "WATCHED")))
            active()
            OutingTransition(id, title.id, title.title, title.posterUrl, provisional, LibraryStatus.WATCHED,
                LibraryStatus.valueOf(title.status))
        } }
    }
}

/** Both the provisional graph and the exact causal title revision remain protected until ACK. */
fun outingLifecycleProtectionKeys(entries: List<work.kumarfamilynet.cinemarchive.core.database.OutboxEntity>, ownerId: String): Set<String> = buildSet {
    entries.filter { it.entityType in setOf("outing_completion", "outing_reversal") }.forEach { entry ->
        val payload = JSONObject(entry.payloadJson)
        require(payload.getString("ownerId") == ownerId)
        add("outing_lifecycle:${entry.entityId}")
        add("cinema_outing:${entry.entityId}")
        add("title:${payload.getString("titleId")}")
        // Until canonical identity is acknowledged, an earlier web completion may have
        // a different viewing ID. Delay its live projection without deleting local history.
        add("outing_lifecycle_title:${payload.getString("titleId")}")
        payload.optString("provisionalViewingId").takeIf { it.isNotBlank() }?.let { add("viewing:$it"); add("viewing_history:$it") }
        payload.optString("canonicalViewingId").takeIf { it.isNotBlank() }?.let { add("viewing:$it"); add("viewing_history:$it") }
        payload.optJSONObject("reversalOpening")?.optString("viewingId")?.takeIf { it.isNotBlank() }?.let { add("viewing:$it"); add("viewing_history:$it") }
        payload.optJSONObject("reversalCommand")?.optString("viewingId")?.takeIf { it.isNotBlank() && it != "null" }?.let { add("viewing:$it"); add("viewing_history:$it") }
    }
}
