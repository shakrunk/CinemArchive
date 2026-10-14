package work.kumarfamilynet.cinemarchive.data

import java.util.UUID
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingDao
import work.kumarfamilynet.cinemarchive.core.database.OutboxDao
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleDao

interface OutingRecoveryRemote {
    suspend fun fetch(session: SupabaseSession, outingId: String): JSONObject?
    suspend fun apply(session: SupabaseSession, outingId: String, attempt: JSONObject): JSONObject
    suspend fun confirmCommand(session: SupabaseSession, entry: OutboxEntity): PushResult =
        error("Saved command confirmation is not configured.")
}

/** Explicit recovery is serialized with pushes; a Room transaction is never held across network IO. */
class OutingRecoveryRepository(
    private val ownerId: String,
    private val sessionProvider: () -> SupabaseSession?,
    private val outboxDao: OutboxDao,
    private val outings: CinemaOutingDao,
    private val titles: TitleDao,
    private val outbox: MutationOutbox,
    private val archive: OutingRecoveryArchive,
    private val remote: OutingRecoveryRemote,
) : OutingRecoverySource {
    override val changes = combine(outboxDao.observePending(), archive.records) { _, _ -> Unit }
    override fun isActive() = sessionProvider()?.userId == ownerId
    override suspend fun pendingAttempt(id: String): Boolean {
        active()
        return archive.record(id)?.optJSONObject("attempt") != null || pendingCommand(id) != null
    }
    private suspend fun active(): SupabaseSession {
        currentCoroutineContext().ensureActive()
        return sessionProvider()?.takeIf { it.userId == ownerId } ?: error("Account changed. Reopen Profile.")
    }

    override suspend fun items(): List<OutingRecoveryCard> {
        active()
        val records = archive.records.first().toMutableMap()
        val pending = outboxDao.getPending()
        pending.filter(::reviewable).forEach { records.putIfAbsent(it.id, it.originalRecord().toString()) }
        val cards = records.map { (id, raw) ->
            val record = runCatching { recoveryRecord(id, raw) }.getOrNull()
            if (record == null) OutingRecoveryCard(id, "Unreadable saved outing change", false,
                "Preserved raw recovery data. Export it for recovery; this entry cannot be applied or discarded.")
            else OutingRecoveryCard(id, title(record), record.getString("state") != "pending" ||
                (record.optJSONObject("attempt") == null && pending.none { it.id == id }))
        }.sortedBy { it.resolved }
        active()
        return cards
    }

    override suspend fun review(id: String): OutingRecoveryReview = outbox.withFlushPaused {
        val record = retain(id)
        val original = record.getJSONObject("original")
        val row = remote.fetch(active(), original.getString("entityId"))?.also { validateRow(it, original.getString("entityId")) }
        active()
        val wire = runCatching { outingWireBody(JSONObject(original.getString("payloadJson")), ownerId, false) }.getOrNull()
        val fields = (outingReviewFields + outingReadOnlyReviewFields).mapNotNull { (key, label) ->
            if (wire?.has(key) != true) null else OutingRecoveryField(key, label, display(wire.get(key)),
                if (row == null) "Not available" else display(row.opt(key) ?: JSONObject.NULL), row != null && key in outingReviewFields)
        }
        OutingRecoveryReview(id, title(record), fields, row?.getString("updated_at"), row != null,
            record.optJSONObject("attempt") != null || pendingCommand(id) != null, record.getString("state") != "pending",
            if (wire == null) "This saved change cannot be interpreted. Its original data can still be exported or explicitly discarded."
            else if (row == null) "The current plan is unavailable. It will not be recreated automatically."
            else "Select only the saved fields you want to reapply. Lifecycle and ticket attachment data are retained in the original export.")
    }

    override suspend fun apply(id: String, expectedVersion: String?, selected: Set<String>): OutingRecoveryOutcome = outbox.withFlushPaused {
        val record = retain(id)
        check(record.getString("state") == "pending") { "This change has already been resolved." }
        pendingCommand(id)?.let { command ->
            // Unknown delivery must be settled using the original immutable operation before
            // the user can replace its intent or discard it. A definite conflict enables review.
            when (val result = remote.confirmCommand(active(), command)) {
                is PushResult.Applied -> {
                    active()
                    val current = currentOutingCommandRow(command, result.receipt, ownerId)
                    finish(id, record, current, "applied")
                    return@withFlushPaused OutingRecoveryOutcome.CONFIRMED
                }
                is PushResult.Review -> {
                    active()
                    outboxDao.markForReview(id, result.reason)
                    return@withFlushPaused OutingRecoveryOutcome.CHANGED
                }
                is PushResult.Retry -> error(result.reason)
                else -> error("Could not verify the original outing command. Retry the same attempt.")
            }
        }
        val original = record.getJSONObject("original")
        val outingId = original.getString("entityId")
        var attempt = record.optJSONObject("attempt")
        val isRetry = attempt != null
        if (attempt == null) {
            require(!expectedVersion.isNullOrBlank() && selected.isNotEmpty()) { "Refresh the current plan and select fields first." }
            require(selected.all(outingReviewFields::containsKey)) { "Unsupported field selection." }
            val saved = outingWireBody(JSONObject(original.getString("payloadJson")), ownerId, false)
            val patch = JSONObject()
            selected.forEach { key -> require(saved.has(key)); patch.put(key, saved.get(key)) }
            attempt = JSONObject().put("operationId", UUID.randomUUID().toString())
                .put("expectedVersion", expectedVersion).put("patch", patch)
            record.put("attempt", attempt)
            active()
            archive.put(id, record.toString()) // durable BEFORE dispatch, including after process death
        }
        active()
        outboxDao.markForReview(id, "Open Profile > Saved outing changes to finish this review.")
        val response = try { remote.apply(active(), outingId, attempt) }
        catch (error: SupabaseHttpException) {
            active()
            if (!isRetry && error.status in 400..499 && error.status != 409) {
                // A definite rejected request did not commit. Allow a corrected review.
                record.remove("attempt")
                archive.put(id, record.toString())
            }
            throw error
        }
        active()
        validateResponse(response, outingId, attempt)
        when (response.getString("status")) {
            "applied" -> {
                // A receipt can describe an older applied row. Refresh so it cannot resurrect a
                // subsequently deleted outing or roll back changes committed after that receipt.
                val current = remote.fetch(active(), outingId)?.also { validateRow(it, outingId) }
                active()
                finish(id, record, current, "applied")
                OutingRecoveryOutcome.APPLIED
            }
            "conflict", "missing" -> {
                record.remove("attempt")
                archive.put(id, record.toString())
                if (response.getString("status") == "missing") OutingRecoveryOutcome.MISSING else OutingRecoveryOutcome.CHANGED
            }
            else -> error("Could not confirm the resolution. Retry to check the same attempt.")
        }
    }

    override suspend fun discard(id: String) = outbox.withFlushPaused {
        val record = retain(id)
        check(record.optJSONObject("attempt") == null && pendingCommand(id) == null) { "Confirm the pending attempt before discarding this change." }
        val outingId = record.getJSONObject("original").getString("entityId")
        val current = remote.fetch(active(), outingId)?.also { validateRow(it, outingId) }
        active()
        finish(id, record, current, "discarded")
    }

    override suspend fun exportOriginal(id: String): String = outbox.withFlushPaused {
        active()
        val raw = archive.records.first()[id]
        if (raw != null && runCatching { recoveryRecord(id, raw) }.isFailure) {
            active()
            return@withFlushPaused raw // preserve unreadable data byte-for-byte, without interpreting it
        }
        val record = retain(id)
        active()
        JSONObject().put("version", 1).put("kind", "CinemArchive saved outing change")
            .put("original", record.getJSONObject("original")).toString(2)
    }

    private suspend fun retain(id: String): JSONObject {
        active()
        archive.records.first()[id]?.let { raw ->
            val record = recoveryRecord(id, raw)
            if (record.getString("state") == "pending" && record.optJSONObject("attempt") == null &&
                outboxDao.getPending().none { it.id == id }) {
                record.put("state", "acknowledged")
                archive.put(id, record.toString())
            }
            return record
        }
        val entry = outboxDao.getPending().firstOrNull { it.id == id && reviewable(it) }
            ?: error("This saved change is no longer pending.")
        val record = entry.originalRecord()
        archive.put(id, record.toString())
        active()
        return record
    }

    private suspend fun finish(id: String, record: JSONObject, current: JSONObject?, state: String) {
        val outingId = record.getJSONObject("original").getString("entityId")
        outbox.atomically {
            active()
            val queue = outboxDao.getPending()
            val index = queue.indexOfFirst { it.id == id }
            // If process death followed a successful local commit, all surviving entries are later.
            val later = (if (index < 0) queue else queue.drop(index + 1))
                .filter { it.entityType == "cinema_outing" && it.entityId == outingId }
            if (current != null && outings.getById(outingId) != null && titles.getById(current.getString("title_id")) != null) {
                val projection = runCatching {
                    val row = JSONObject(current.toString())
                    later.forEach { entry ->
                        val patch = outingWireBody(JSONObject(entry.payloadJson), ownerId, false)
                        patch.keys().forEach { key -> row.put(key, patch.get(key)) }
                    }
                    row.toRecoveryOuting()
                }.getOrNull()
                // A malformed later intent must remain reviewable without preventing this
                // independent resolution. Retain its existing local projection and exact queue.
                if (projection != null) outings.upsert(projection) else check(later.isNotEmpty())
            } else if (current == null && later.isEmpty()) {
                // Removing the plan does not delete independent viewing history or queued commands.
                outings.deleteById(outingId)
            }
            outboxDao.remove(id)
        }
        // Original remains exportable even after the queue entry has been resolved.
        record.put("state", state).remove("attempt")
        active()
        archive.put(id, record.toString())
    }

    private suspend fun title(record: JSONObject): String {
        val original = record.getJSONObject("original")
        val local = outings.getById(original.getString("entityId"))
        val titleId = local?.titleId ?: runCatching { JSONObject(original.getString("payloadJson")).getString("titleId") }.getOrNull()
        return titleId?.let { titles.getById(it)?.title } ?: "Cinema outing"
    }

    private suspend fun pendingCommand(id: String): OutboxEntity? = outboxDao.getPending()
        .firstOrNull { it.id == id && it.entityType == "cinema_outing" && it.operation == OUTING_COMMAND }

    private fun validateRow(row: JSONObject, id: String) {
        require(row.getString("id") == id && row.getString("user_id") == ownerId) { "Invalid owner-scoped outing response." }
        row.toRecoveryOuting() // validate the complete projection before changing the queue
    }

    private fun validateResponse(response: JSONObject, outingId: String, attempt: JSONObject) {
        val request = response.getJSONObject("request")
        require(response.getString("operationId") == attempt.getString("operationId") &&
            request.getString("kind") == "outing.resolve" && request.getString("outingId") == outingId &&
            Instant.parse(request.getString("expectedUpdatedAt")) == Instant.parse(attempt.getString("expectedVersion")) &&
            sameRecoveryJson(request.getJSONObject("patch"), attempt.getJSONObject("patch"))) {
            "Could not verify the resolution receipt. Retry to confirm the same attempt."
        }
    }

    companion object {
        private fun reviewable(entry: OutboxEntity) = entry.entityType == "cinema_outing" &&
            (entry.operation in setOf("upsert", "review") || (entry.operation in setOf("insert", "update", OUTING_COMMAND) && entry.attemptCount > 0))

        fun remote(client: SupabaseRestClient, ownerId: String, sessionProvider: () -> SupabaseSession?) = object : OutingRecoveryRemote {
            private fun session() = sessionProvider()?.takeIf { it.userId == ownerId } ?: error("Account changed. Reopen Profile.")
            override suspend fun fetch(session: SupabaseSession, outingId: String): JSONObject? = withContext(Dispatchers.IO) {
                val rows = JSONArray(client.get("cinema_outings", "id=eq.$outingId&user_id=eq.$ownerId&select=*", session().accessToken))
                require(rows.length() <= 1)
                if (rows.length() == 0) null else rows.getJSONObject(0)
            }
            override suspend fun apply(session: SupabaseSession, outingId: String, attempt: JSONObject): JSONObject = withContext(Dispatchers.IO) {
                JSONObject(client.rpc("resolve_outing_fields", JSONObject().put("p_outing_id", outingId)
                    .put("p_expected_updated_at", attempt.getString("expectedVersion")).put("p_patch", attempt.getJSONObject("patch"))
                    .put("p_operation_id", attempt.getString("operationId")).toString(), session().accessToken))
            }
            override suspend fun confirmCommand(session: SupabaseSession, entry: OutboxEntity): PushResult =
                OutingCommandTransport(client, sessionProvider).push(entry)
        }
    }
}

private fun recoveryRecord(id: String, raw: String): JSONObject = JSONObject(raw).also { record ->
    require(record.getInt("version") == 1 && record.getString("state") in setOf("pending", "applied", "discarded", "acknowledged"))
    val original = record.getJSONObject("original")
    require(original.getString("id") == id && original.getString("entityType") == "cinema_outing")
    require(original.getString("entityId").isNotBlank() && original.getString("operation").isNotBlank())
    original.getString("payloadJson")
}

private fun sameRecoveryJson(left: Any?, right: Any?): Boolean = when {
    left is JSONObject && right is JSONObject -> {
        val keys = left.keys().asSequence().toSet()
        keys == right.keys().asSequence().toSet() && keys.all { sameRecoveryJson(left.get(it), right.get(it)) }
    }
    left is JSONArray && right is JSONArray -> left.length() == right.length() &&
        (0 until left.length()).all { sameRecoveryJson(left.get(it), right.get(it)) }
    left is Number && right is Number -> left.toString().toBigDecimal().compareTo(right.toString().toBigDecimal()) == 0
    else -> left == right
}

private fun display(value: Any): String = when (value) {
    JSONObject.NULL -> "None"
    is JSONArray -> if (value.length() == 0) "None" else (0 until value.length()).joinToString(", ") {
        val item = value.get(it)
        if (item is JSONObject) item.optString("name") + if (item.has("friendUserId")) " (linked friend)" else "" else item.toString()
    }
    else -> value.toString()
}
