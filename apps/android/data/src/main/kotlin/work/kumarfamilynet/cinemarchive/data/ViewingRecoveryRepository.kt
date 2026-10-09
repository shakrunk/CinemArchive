package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

interface ViewingRecoveryRemote {
    suspend fun fetch(session: SupabaseSession, viewingId: String): JSONObject?
    suspend fun confirm(entry: OutboxEntity): PushResult
    suspend fun linkedOutings(session: SupabaseSession, ids: List<String>): JSONObject = error("Linked outing refresh is unavailable. Retry without discarding the saved change.")
    suspend fun title(session: SupabaseSession, titleId: String): JSONObject? = error("Current title refresh is unavailable. Retry without discarding the saved viewing action.")
}

/** Original bytes and attempted commands live in a separate, owner-scoped recovery store.
 * Resolving one event never rewrites a later HTTP-capable command or creates missing history. */
class ViewingRecoveryRepository(
    private val database: LibraryDatabase,
    private val ownerId: String,
    private val sessionProvider: () -> SupabaseSession?,
    private val outbox: MutationOutbox,
    private val archive: OutingRecoveryArchive,
    private val remote: ViewingRecoveryRemote,
    private val replayBoundary: suspend (suspend () -> Unit) -> Unit,
) : OutingRecoverySource {
    private val dao get() = database.outboxDao()
    override val changes = combine(database.outboxDao().observePending(), archive.records) { _, _ -> Unit }
    override fun isActive() = sessionProvider()?.userId == ownerId
    private suspend fun active(): SupabaseSession {
        currentCoroutineContext().ensureActive()
        return sessionProvider()?.takeIf { it.userId == ownerId } ?: error("Account changed. Reopen Profile.")
    }
    override suspend fun pendingAttempt(id: String): Boolean {
        active()
        return archive.record(id)?.optJSONObject("attempt") != null || pendingCommand(id) != null
    }
    override suspend fun items(): List<OutingRecoveryCard> {
        active()
        val records = archive.records.first().toMutableMap()
        val pending = dao.getPending()
        pending.filter(::reviewable).forEach { records.putIfAbsent(it.id, it.originalRecord().toString()) }
        val result = records.map { (id, raw) ->
            val record = runCatching { viewingRecoveryRecord(id, raw) }.getOrNull()
            if (record == null) OutingRecoveryCard(id, "Unreadable saved viewing change", false,
                "Preserved raw recovery data. Export it for recovery; this entry cannot be applied or discarded.")
            else OutingRecoveryCard(id, title(record), record.getString("state") != "pending" ||
                (record.optJSONObject("attempt") == null && pending.none { it.id == id }))
        }.sortedBy { it.resolved }
        active()
        return result
    }
    override suspend fun review(id: String): OutingRecoveryReview = outbox.withFlushPaused {
        val record = retain(id)
        val original = recoveryEntry(record.getJSONObject("original"))
        val intent = runCatching { ownedIntent(original) }.getOrNull()
        val target = target(original, intent?.titleId)
        val current = remote.fetch(active(), target.id)?.also { validateCurrent(it, target) }
        val currentTitle = fetchCompoundTitle(original)
        active()
        val fields = mutableListOf<OutingRecoveryField>()
        if (intent?.action == "delete") {
            viewingRecoveryFields.forEach { (key, label) ->
                fields += OutingRecoveryField(key, label, "Not recorded in the deletion request",
                    current?.let { viewingDisplay(it.opt(viewingRecoveryColumns.getValue(key)) ?: JSONObject.NULL) } ?: "Not available", false)
            }
            fields += OutingRecoveryField("delete", "Delete this viewing", "Remove only this event",
                if (current == null) "Already unavailable" else "This event is still in your history", current != null)
        } else if (intent != null) {
            viewingRecoveryFields.forEach { (key, label) ->
                if (intent.fields.has(key)) fields += OutingRecoveryField(key, label, viewingDisplay(intent.fields.get(key)),
                    current?.let { viewingDisplay(it.opt(viewingRecoveryColumns.getValue(key)) ?: JSONObject.NULL) } ?: "Not available", current != null)
            }
        }
        if (viewingTitleEntry(original, ownerId) != null) {
            fields += OutingRecoveryField("titleEffect", "Title rating and status", "Selecting the saved rating also updates the title rating. Reapplying a new viewing marks the title watched.",
                currentTitle?.let { "Status: ${it.getString("status")}; rating: ${viewingDisplay(it.get("rating"))}" } ?: "Title unavailable", false)
        }
        val canApply = current != null && (viewingTitleEntry(original, ownerId) == null || currentTitle != null)
        OutingRecoveryReview(id, title(record), fields, if (canApply) reviewVersion(current!!, currentTitle) else null, canApply,
            record.optJSONObject("attempt") != null || pendingCommand(id) != null, record.getString("state") != "pending",
            when {
                intent == null -> "This saved change cannot be interpreted. Its original data remains available to export."
                current == null -> "The current viewing is unavailable. No replacement will be created. Export the draft before discarding it if you want to log a separate viewing."
                intent.action == "delete" -> "Review this exact event before selecting deletion. Other viewings and the title's status and rating are preserved."
                intent.action == "insert" -> "The original was a new viewing. This ID already exists: applying selected fields edits that current event. To record a separate event, use Add viewing instead."
                else -> "Select only the saved fields to reapply. The current event must still have the revision shown here."
            })
    }
    override suspend fun apply(id: String, expectedVersion: String?, selected: Set<String>): OutingRecoveryOutcome = mutate {
        val record = retain(id)
        check(record.getString("state") == "pending") { "This change has already been resolved." }
        pendingCommand(id)?.let { original ->
            check(dao.getPending().firstOrNull()?.id == id) { "Sync or review earlier pending changes before confirming this command." }
            when (val result = remote.confirm(original)) {
                is PushResult.Applied -> {
                    active()
                    val current = currentViewingCommandRow(original, result.receipt, ownerId)
                    val outings = currentViewingLinkedOutings(original, result.receipt, ownerId)
                    val title = currentViewingTitle(original, result.receipt, ownerId)
                    finish(id, record, current, "applied", restoreObserved = false, currentOutings = outings, currentTitle = title)
                    return@mutate OutingRecoveryOutcome.CONFIRMED
                }
                is PushResult.Review -> {
                    active(); dao.markForReview(id, result.reason)
                    return@mutate OutingRecoveryOutcome.CHANGED
                }
                is PushResult.Retry -> error(result.reason)
                else -> error("Could not confirm the original viewing command. Retry the same attempt.")
            }
        }
        val original = recoveryEntry(record.getJSONObject("original"))
        var attempt = record.optJSONObject("attempt")
        if (attempt == null) {
            require(!expectedVersion.isNullOrBlank() && selected.isNotEmpty()) { "Review the current event and select an action first." }
            val saved = ownedIntent(original)
            val versions = readReviewVersion(original, expectedVersion)
            val target = target(original, saved.titleId)
            val current = remote.fetch(active(), target.id)?.also { validateCurrent(it, target) }
                ?: return@mutate OutingRecoveryOutcome.MISSING
            val currentTitle = fetchCompoundTitle(original)
            if (viewingTitleEntry(original, ownerId) != null && currentTitle == null) return@mutate OutingRecoveryOutcome.MISSING
            active()
            val deleting = saved.action == "delete"
            require(if (deleting) selected == setOf("delete") else selected.all { it in viewingRecoveryFields && saved.fields.has(it) })
            val fields = JSONObject()
            if (!deleting) selected.forEach { fields.put(it, saved.fields.get(it)) }
            val payload = viewingCommandPayload(target.id, current.getString("title_id"), if (deleting) "delete" else "update", fields, versions.first, null)
            selectedTitlePatch(original, fields)?.let { patch ->
                if (saved.action == "insert") payload.put("reviewedInsert", true)
                attachViewingTitleEffect(payload, titleMetadataPayload(ownerId, current.getString("title_id"), patch, checkNotNull(versions.second), null))
            }
            if (deleting && JSONObject(original.payloadJson).has("linkedOutings")) {
                val originalPayload = JSONObject(original.payloadJson)
                payload.put("linkedOutings", originalPayload.getJSONArray("linkedOutings"))
                    .put(VIEWING_OPENING, originalPayload.getJSONObject(VIEWING_OPENING))
                val opening = originalPayload.getJSONObject(VIEWING_OPENING)
                if (opening.getString("id") != target.id) {
                    val alias = checkNotNull(database.viewingCompletionAliasDao().byProvisionalId(opening.getString("id")))
                    val completion = restoredCompletion(opening.getJSONObject("completion"))
                    val command = completionCommand(completion)
                    require(alias.canonicalViewingId == target.id && alias.titleId == target.titleId &&
                        alias.completionOperationId == completion.id && alias.outingId == command.outingId &&
                        command.provisionalViewingId == opening.getString("id"))
                    payload.put("completionSource", originalPayload.optJSONObject("completionSource") ?: originalPayload)
                        .put("completionCanonicalViewingId", target.id)
                }
            }
            val command = OutboxEntity(UUID.randomUUID().toString(), "viewing", target.id, VIEWING_COMMAND, payload.toString(), System.currentTimeMillis())
            attempt = command.originalRecord().getJSONObject("original")
            record.put("attempt", attempt)
            active(); archive.put(id, record.toString()) // durable before the first HTTP request
        }
        val attempted = checkedAttempt(original, attempt)
        active()
        dao.markForReview(id, "Open Profile > Saved viewing changes to finish this review.")
        when (val result = remote.confirm(attempted)) {
            is PushResult.Applied -> {
                active()
                val current = currentViewingCommandRow(attempted, result.receipt, ownerId)
                val outings = currentViewingLinkedOutings(attempted, result.receipt, ownerId)
                val title = if (viewingTitleEntry(attempted, ownerId) != null) currentViewingTitle(attempted, result.receipt, ownerId) else fetchCompoundTitle(original)
                finish(id, record, current, "applied", restoreObserved = true, currentOutings = outings, currentTitle = title)
                OutingRecoveryOutcome.APPLIED
            }
            is PushResult.Review -> {
                active(); record.remove("attempt"); archive.put(id, record.toString())
                OutingRecoveryOutcome.CHANGED
            }
            is PushResult.Retry -> error(result.reason)
            else -> error("Could not verify this viewing resolution. Confirm the same attempt before changing it.")
        }
    }
    override suspend fun discard(id: String) = mutate {
        val record = retain(id)
        check(record.getString("state") == "pending") { "This change has already been resolved." }
        check(record.optJSONObject("attempt") == null && pendingCommand(id) == null) { "Confirm the pending attempt before discarding this change." }
        val original = recoveryEntry(record.getJSONObject("original"))
        val saved = runCatching { ownedIntent(original) }.getOrNull()
        val target = target(original, saved?.titleId)
        val current = remote.fetch(active(), target.id)?.also { validateCurrent(it, target) }
        val ids = viewingLinkedOutingIds(original, ownerId)
        val outings = if (ids.isEmpty()) emptyMap() else checkedViewingLinkedOutings(ids, checkNotNull(saved?.titleId), ownerId, remote.linkedOutings(active(), ids))
        val title = fetchCompoundTitle(original)
        active()
        finish(id, record, current, "discarded", restoreObserved = true, currentOutings = outings, currentTitle = title)
    }
    override suspend fun exportOriginal(id: String): String = outbox.withFlushPaused {
        active()
        val raw = archive.records.first()[id]
        if (raw != null && runCatching { viewingRecoveryRecord(id, raw) }.isFailure) {
            active(); return@withFlushPaused raw
        }
        val record = retain(id)
        active()
        JSONObject().put("version", 1).put("kind", "CinemArchive saved viewing change")
            .put("original", record.getJSONObject("original")).toString(2)
    }
    private suspend fun retain(id: String): JSONObject {
        active()
        archive.records.first()[id]?.let { raw ->
            val record = viewingRecoveryRecord(id, raw)
            if (record.getString("state") == "pending" && record.optJSONObject("attempt") == null && dao.getPending().none { it.id == id }) {
                record.put("state", "acknowledged"); archive.put(id, record.toString())
            }
            return record
        }
        val entry = dao.getPending().firstOrNull { it.id == id && reviewable(it) } ?: error("This saved viewing change is no longer pending.")
        return entry.originalRecord().also { archive.put(id, it.toString()); active() }
    }
    private suspend fun <T> mutate(action: suspend () -> T): T {
        var result: ViewingRecoveryValue<T>? = null
        replayBoundary { result = ViewingRecoveryValue(outbox.withFlushPaused(action)) }
        return checkNotNull(result).value
    }
    private suspend fun finish(id: String, record: JSONObject, current: JSONObject?, state: String, restoreObserved: Boolean,
        currentOutings: Map<String, JSONObject?> = emptyMap(), currentTitle: JSONObject? = null) {
        val original = recoveryEntry(record.getJSONObject("original"))
        val target = target(original, runCatching { ownedIntent(original).titleId }.getOrNull())
        current?.let { validateCurrent(it, target) }
        outbox.atomically {
            active()
            val queue = dao.getPending()
            val index = queue.indexOfFirst { it.id == id }
            val later = (if (index < 0) queue else queue.drop(index + 1)).filter {
                it.entityType == "viewing" && it.entityId in setOf(original.entityId, target.id)
            }
            if (current == null) {
                database.viewingDao().deleteById(target.id)
                database.completionQueueDao().clearViewingLink(target.id)
            }
            else if (database.titleDao().getById(current.getString("title_id")) != null &&
                (restoreObserved || database.viewingDao().getById(target.id) != null)) {
                val projection = runCatching {
                    val row = JSONObject(current.toString())
                    var deleted = false
                    later.forEach { pending ->
                        val saved = ownedIntent(pending)
                        require(saved.titleId == null || saved.titleId == current.getString("title_id"))
                        require(saved.action != "insert" && !deleted)
                        if (saved.action == "delete") deleted = true
                        else viewingWireValues(saved.fields).let { patch -> patch.keys().forEach { row.put(it, patch.get(it)) } }
                    }
                    if (deleted) null else row.toCompletionViewing()
                }
                if (projection.isSuccess) {
                    val row = projection.getOrNull()
                    if (row == null) database.viewingDao().deleteById(target.id) else database.viewingDao().upsert(row)
                }
            }
            // The alias proves only this provisional identity. Never remove independent history.
            if (target.id != original.entityId && later.isEmpty()) {
                val provisional = database.viewingDao().getById(original.entityId)
                require(provisional == null || provisional.titleId == target.titleId) { "The provisional viewing identity changed." }
                database.viewingDao().deleteById(original.entityId)
            }
            if (currentOutings.isNotEmpty()) applyViewingLinkedOutings(database, checkNotNull(target.titleId), currentOutings,
                if (index < 0) queue else queue.drop(index + 1))
            if (viewingTitleEntry(original, ownerId) != null) applyCurrentViewingTitle(database, checkNotNull(target.titleId), currentTitle,
                if (index < 0) queue else queue.drop(index + 1), ownerId)
            dao.remove(id)
            active()
        }
        record.put("state", state).remove("attempt")
        active(); archive.put(id, record.toString())
    }
    private suspend fun checkedAttempt(original: OutboxEntity, json: JSONObject): OutboxEntity {
        val saved = ownedIntent(original)
        val target = target(original, saved.titleId)
        val entry = recoveryEntry(json)
        require(entry.id != original.id && entry.entityId == target.id)
        val operation = viewingCommandOperations(entry).getJSONObject(0)
        val payload = JSONObject(entry.payloadJson)
        require(viewingLinkedOutingIds(entry, ownerId) == viewingLinkedOutingIds(original, ownerId))
        require(target.titleId == null || payload.getString("titleId") == target.titleId)
        require(operation.has("expectedUpdatedAt") && !operation.has("expectedOperationId"))
        if (saved.action == "delete") require(operation.getString("action") == "delete")
        else {
            require(operation.getString("action") == "update")
            val fields = payload.getJSONObject("fields")
            require(fields.length() > 0 && fields.keys().asSequence().all {
                saved.fields.has(it) && sameCommandJson(fields.get(it), saved.fields.get(it))
            }) { "The retained resolution differs from the original saved fields." }
        }
        val expectedTitle = selectedTitlePatch(original, payload.getJSONObject("fields"))
        val effect = viewingTitleEntry(entry, ownerId)
        require((expectedTitle == null) == (effect == null)) { "The title effect differs from the selected viewing fields." }
        if (effect != null) {
            require(sameCommandJson(expectedTitle!!, titleMetadataPatch(effect, ownerId)))
            val titleOperation = titleMetadataOperation(effect, ownerId)
            require(titleOperation.has("expectedUpdatedAt") && !titleOperation.has("expectedOperationId"))
        }
        return entry
    }
    private suspend fun fetchCompoundTitle(entry: OutboxEntity): JSONObject? {
        val effect = viewingTitleEntry(entry, ownerId) ?: return null
        val current = remote.title(active(), effect.entityId)
        active()
        return current?.let { checkedCurrentTitle(it, effect.entityId, ownerId) }
    }
    private fun selectedTitlePatch(original: OutboxEntity, fields: JSONObject): JSONObject? {
        val effect = viewingTitleEntry(original, ownerId) ?: return null
        val saved = titleMetadataPatch(effect, ownerId)
        return JSONObject().apply {
            if (saved.has("status") && fields.length() > 0) put("status", saved.get("status"))
            if (saved.has("rating") && fields.has("rating")) put("rating", saved.get("rating"))
        }.takeIf { it.length() > 0 }
    }
    private fun reviewVersion(viewing: JSONObject, title: JSONObject?): String = if (title == null) viewing.getString("updated_at") else
        JSONObject().put("viewing", viewing.getString("updated_at")).put("title", title.getString("updated_at")).toString()
    private fun readReviewVersion(original: OutboxEntity, value: String): Pair<String, String?> {
        if (viewingTitleEntry(original, ownerId) == null) return value.also(Instant::parse) to null
        val versions = JSONObject(value)
        require(versions.keys().asSequence().toSet() == setOf("viewing", "title"))
        return versions.getString("viewing").also(Instant::parse) to versions.getString("title").also(Instant::parse)
    }
    private suspend fun title(record: JSONObject): String {
        val original = recoveryEntry(record.getJSONObject("original"))
        val known = runCatching { ownedIntent(original).titleId }.getOrNull()
            ?: database.viewingDao().getById(original.entityId)?.titleId
        return known?.let { database.titleDao().getById(it)?.title } ?: "Viewing history"
    }
    private suspend fun pendingCommand(id: String) = dao.getPending().firstOrNull {
        it.id == id && it.entityType == "viewing" && it.operation == VIEWING_COMMAND
    }
    private fun ownedIntent(entry: OutboxEntity): SavedViewingIntent {
        JSONObject(entry.payloadJson).optJSONObject(VIEWING_OPENING)?.let { require(it.getString("ownerId") == ownerId) }
        return intent(entry)
    }
    private data class Target(val id: String, val titleId: String?)
    private suspend fun target(original: OutboxEntity, titleId: String?): Target {
        val alias = database.viewingCompletionAliasDao().byProvisionalId(original.entityId)
        require(alias == null || titleId == null || alias.titleId == titleId) { "Saved viewing belongs to another title." }
        return Target(alias?.canonicalViewingId ?: original.entityId, alias?.titleId ?: titleId)
    }
    private fun validateCurrent(row: JSONObject, target: Target) {
        require(row.getString("id") == target.id && row.getString("user_id") == ownerId &&
            (target.titleId == null || row.getString("title_id") == target.titleId)) { "Viewing identity or owner changed." }
        row.toCompletionViewing()
    }
    companion object {
        private fun reviewable(entry: OutboxEntity) = entry.entityType == "viewing" &&
            (entry.operation in setOf("upsert", "update", "delete", "review") || (entry.operation == VIEWING_COMMAND && entry.attemptCount > 0))
        fun remote(client: SupabaseRestClient, ownerId: String, sessionProvider: () -> SupabaseSession?) = object : ViewingRecoveryRemote {
            override suspend fun fetch(session: SupabaseSession, viewingId: String): JSONObject? = withContext(Dispatchers.IO) {
                require(session.userId == ownerId)
                val rows = JSONArray(client.get("viewings", "id=eq." + viewingId + "&user_id=eq." + ownerId + "&select=*", session.accessToken))
                require(rows.length() <= 1)
                if (rows.length() == 0) null else rows.getJSONObject(0)
            }
            override suspend fun confirm(entry: OutboxEntity) = ViewingCommandTransport(client, sessionProvider).push(entry)
            override suspend fun title(session: SupabaseSession, titleId: String) = withContext(Dispatchers.IO) {
                require(session.userId == ownerId)
                fetchViewingTitle(client, session, titleId) { check(sessionProvider()?.userId == ownerId) { "Viewing account changed." } }
            }
            override suspend fun linkedOutings(session: SupabaseSession, ids: List<String>) = withContext(Dispatchers.IO) {
                require(session.userId == ownerId)
                fetchViewingLinkedOutings(client, session, ids) { check(sessionProvider()?.userId == ownerId) { "Viewing account changed." } }
            }
        }
    }
}

private data class SavedViewingIntent(val titleId: String?, val action: String, val fields: JSONObject)
private data class ViewingRecoveryValue<T>(val value: T)
private fun intent(entry: OutboxEntity): SavedViewingIntent {
    val payload = JSONObject(entry.payloadJson)
    if (payload.has(VIEWING_REVIEW_INTENT)) {
        val saved = payload.getJSONObject(VIEWING_REVIEW_INTENT)
        require(entry.operation == "review" && saved.getInt("version") == 1 && saved.getString("id") == entry.entityId)
        require(saved.getString("ownerId") == payload.getJSONObject(VIEWING_OPENING).getString("ownerId"))
        val action = saved.getString("action")
        val fields = saved.getJSONObject("fields")
        require(action in setOf("insert", "update", "delete"))
        if (action == "delete") require(fields.length() == 0) else viewingWireValues(fields)
        return SavedViewingIntent(saved.getString("titleId"), action, fields)
    }
    if (payload.has("completionIntent")) {
        val saved = payload.getJSONObject("completionIntent")
        require(saved.getString("provisionalViewingId") == entry.entityId)
        val action = saved.getString("action")
        val fields = saved.getJSONObject("fields")
        require(action in setOf("update", "delete"))
        if (action == "delete") require(fields.length() == 0) else viewingWireValues(fields)
        return SavedViewingIntent(saved.getString("titleId"), action, fields)
    }
    if (payload.has(VIEWING_COMMAND_DATA)) {
        val command = entry.copy(operation = VIEWING_COMMAND)
        val op = viewingCommandOperations(command).getJSONObject(0)
        return SavedViewingIntent(payload.getString("titleId"), op.getString("action"), payload.getJSONObject("fields"))
    }
    require(payload.getString("id") == entry.entityId)
    val action = if (entry.operation == "delete") "delete" else if (entry.operation == "upsert") "insert" else "update"
    val fields = JSONObject()
    viewingRecoveryFields.keys.filter(payload::has).forEach { key ->
        val value = payload.get(key)
        fields.put(key, if (key == "companions") JSONArray().apply {
            val input = value as JSONArray
            for (index in 0 until input.length()) {
                val person = input.get(index)
                put(if (person is String) JSONObject().put("name", person) else person)
            }
        } else value)
    }
    viewingWireValues(fields)
    return SavedViewingIntent(if (payload.has("titleId")) payload.getString("titleId") else null, action, fields)
}
private fun recoveryEntry(json: JSONObject) = OutboxEntity(json.getString("id"), json.getString("entityType"),
    json.getString("entityId"), json.getString("operation"), json.getString("payloadJson"), json.getLong("createdAt"),
    json.optInt("attemptCount"), if (json.has("lastError") && !json.isNull("lastError")) json.getString("lastError") else null)
private fun viewingRecoveryRecord(id: String, raw: String) = JSONObject(raw).also {
    require(it.getInt("version") == 1 && it.getString("state") in setOf("pending", "applied", "discarded", "acknowledged"))
    val entry = recoveryEntry(it.getJSONObject("original"))
    require(entry.id == id && entry.entityType == "viewing")
    it.optJSONObject("attempt")?.let { attempt -> viewingCommandOperations(recoveryEntry(attempt)) }
}
private val viewingRecoveryFields = linkedMapOf("date" to "Viewing date", "rating" to "Rating", "notes" to "Notes", "venue" to "Venue", "companions" to "Companions")
private val viewingRecoveryColumns = mapOf("date" to "viewed_at", "rating" to "rating", "notes" to "notes", "venue" to "venue", "companions" to "companions")
private fun viewingDisplay(value: Any): String = when (value) {
    JSONObject.NULL -> "None"
    is JSONArray -> if (value.length() == 0) "None" else (0 until value.length()).joinToString(", ") {
        val item = value.get(it)
        if (item is JSONObject) item.optString("name") + if (item.has("friendUserId")) " (linked friend)" else "" else item.toString()
    }
    else -> value.toString()
}
