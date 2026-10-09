package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.core.database.ViewingCompletionAliasEntity
import work.kumarfamilynet.cinemarchive.core.database.ViewingEntity
import work.kumarfamilynet.cinemarchive.core.model.ViewingDraft

internal const val VIEWING_OPENING = "viewingOpening"
internal const val VIEWING_REVIEW_INTENT = "viewingIntent"

/** Captured while opening the form, never recomputed from Room when Save is retried. */
internal data class ViewingGuard(val revision: String? = null, val operationId: String? = null) {
    init { require(revision == null || operationId == null); revision?.let(Instant::parse); operationId?.let(UUID::fromString) }
    val known get() = revision != null || operationId != null
    fun json() = JSONObject().put("revision", revision ?: JSONObject.NULL).put("operationId", operationId ?: JSONObject.NULL)
}

internal fun captureViewingGuard(previous: ViewingEntity, alias: ViewingCompletionAliasEntity?, pending: List<OutboxEntity>, ownerId: String? = null): ViewingGuard {
    require(alias == null || (alias.canonicalViewingId == previous.id && alias.titleId == previous.titleId))
    val completion = pending.firstOrNull { it.entityType == "outing_completion" && it.operation == OUTING_COMPLETION &&
        runCatching { completionCommand(it).provisionalViewingId == previous.id }.getOrDefault(false) }
    val related = pending.filter { (it.entityType == "viewing" && it.entityId in setOf(previous.id, alias?.provisionalViewingId)) ||
        (isBackupImport(it) && it.entityId == previous.titleId) }
    var predecessor: String? = null
    if (related.any { entry -> runCatching {
            if (isBackupImport(entry)) {
                importPredecessorFor(entry, "viewings", previous.id, checkNotNull(ownerId))?.let { predecessor = it }
            } else {
            val it = entry
            require(it.entityId == previous.id)
            if (it.operation == AWAITING_COMPLETION && completion != null) checkedCompletionIntent(it, completion)
            else viewingCommandOperations(it)
            require(JSONObject(it.payloadJson).getString("titleId") == previous.titleId)
            predecessor = it.id
            }
        }.isFailure }) return ViewingGuard()
    predecessor?.let { return ViewingGuard(operationId = it) }
    completion?.let { return ViewingGuard(operationId = it.id) }
    if (pending.any { it.entityType == "outing_completion" && it.entityId == (alias?.outingId ?: previous.outingId) }) return ViewingGuard()
    if (previous.updatedAt != null) return ViewingGuard(revision = previous.updatedAt)
    if (alias != null) {
        return if (alias.canonicalViewingVersion != null) ViewingGuard(operationId = alias.completionOperationId) else ViewingGuard()
    }
    return ViewingGuard(revision = previous.updatedAt)
}

internal fun captureViewingTitleGuard(title: TitleEntity, pending: List<OutboxEntity>, ownerId: String): ViewingGuard {
    var guard = runCatching { ViewingGuard(revision = title.updatedAt) }.getOrDefault(ViewingGuard())
    for (entry in pending) {
        if (entry.entityType == "outing_completion") {
            val payload = runCatching { JSONObject(entry.payloadJson) }.getOrNull() ?: return ViewingGuard()
            if (payload.optString("titleId") != title.id) continue
            if (entry.operation != OUTING_COMPLETION || payload.optString("ownerId") != ownerId) return ViewingGuard()
            completionCommand(entry)
            // The server accepts this dependency only if completion actually changed the title.
            // A web-first/no-effect completion is reviewed at ACK, never rebased to its current row.
            if (payload.optBoolean("localTitleChanged")) guard = ViewingGuard(operationId = entry.id)
        } else if (entry in pendingTitleIntents(listOf(entry), title.id)) {
            val valid = runCatching {
                if (entry.operation == AWAITING_COMPLETION) {
                    val effect = checkNotNull(viewingTitleEntry(entry, ownerId))
                    titleMetadataOperation(effect, ownerId)
                } else checkedTitlePredecessor(entry, ownerId)
            }.isSuccess
            if (!valid) return ViewingGuard()
            guard = ViewingGuard(operationId = entry.id)
        }
    }
    return guard
}

internal fun ViewingDraft.viewingFields(companionsJson: String? = null) = JSONObject().put("date", date ?: JSONObject.NULL)
    .put("rating", rating ?: JSONObject.NULL).put("notes", notes ?: JSONObject.NULL).put("venue", venue ?: JSONObject.NULL)
    .put("companions", companionObjects(companionsJson, companions))

internal fun viewingOpening(ownerId: String, titleId: String, requestedId: String?, original: ViewingDraft,
    guard: ViewingGuard, titleGuard: ViewingGuard, linkedOutings: List<String>, companionsJson: String? = null,
    completion: OutboxEntity? = null): String = canonicalViewingJson(JSONObject()
    .put("version", 1).put("ownerId", ownerId).put("titleId", titleId).put("id", original.id)
    .put("requestedId", requestedId ?: JSONObject.NULL).put("isNew", requestedId == null)
    .put("token", UUID.randomUUID().toString()).put("original", original.viewingFields(companionsJson))
    .put("companionsKnown", companionsJson != null || original.companions.isEmpty() || requestedId == null)
    .put("guard", guard.json()).put("titleGuard", titleGuard.json()).put("linkedOutings", JSONArray(linkedOutings.sorted()))
    .also { if (completion != null) it.put("completion", capturedCompletion(completion)) })

internal data class CapturedViewing(val ownerId: String, val titleId: String, val id: String, val isNew: Boolean,
    val token: String, val original: JSONObject, val guard: ViewingGuard, val titleGuard: ViewingGuard,
    val linkedOutings: List<String>, val raw: String, val companionsKnown: Boolean, val completion: OutboxEntity? = null)

internal fun checkedViewingOpening(draft: ViewingDraft, ownerId: String, titleId: String): CapturedViewing {
    val raw = checkNotNull(draft.openingContext) { "Reopen the viewing editor to capture its original version." }
    val json = JSONObject(raw)
    val required = setOf("version", "ownerId", "titleId", "id", "requestedId", "isNew", "token", "original", "guard", "titleGuard", "linkedOutings")
    require(json.keys().asSequence().toSet().let { it.containsAll(required) && it.all { key -> key in required + setOf("companionsKnown", "completion") } })
    if (json.has("companionsKnown")) require(json.get("companionsKnown") is Boolean)
    require(json.getInt("version") == 1 && json.getString("ownerId") == ownerId && json.getString("titleId") == titleId &&
        json.getString("id") == draft.id) { "This viewing editor belongs to another account or title." }
    listOf(ownerId, titleId, draft.id, json.getString("token")).forEach(UUID::fromString)
    fun guard(key: String) = json.getJSONObject(key).let {
        require(it.keys().asSequence().toSet() == setOf("revision", "operationId"))
        ViewingGuard(if (it.isNull("revision")) null else it.getString("revision"), if (it.isNull("operationId")) null else it.getString("operationId"))
    }
    val original = json.getJSONObject("original").also(::viewingWireValues)
    require(original.keys().asSequence().toSet() == setOf("date", "rating", "notes", "venue", "companions"))
    require(json.get("isNew") is Boolean && json.getBoolean("isNew") == json.isNull("requestedId"))
    if (!json.isNull("requestedId")) UUID.fromString(json.getString("requestedId"))
    val linked = json.getJSONArray("linkedOutings").let { values -> (0 until values.length()).map { values.getString(it).also(UUID::fromString) } }
    require(linked.size == linked.distinct().size)
    return CapturedViewing(ownerId, titleId, draft.id, json.getBoolean("isNew"), json.getString("token"), original,
        guard("guard"), guard("titleGuard"), linked, raw,
        json.optBoolean("companionsKnown", json.getBoolean("isNew") || original.getJSONArray("companions").length() == 0),
        json.optJSONObject("completion")?.let(::restoredCompletion)?.also { entry ->
            val command = completionCommand(entry)
            require(command.titleId == titleId && command.provisionalViewingId == draft.id)
            require(JSONObject(entry.payloadJson).getString("ownerId") == ownerId)
        })
}

internal fun CapturedViewing.fields(draft: ViewingDraft): JSONObject {
    val before = original.getJSONArray("companions")
    val proposed = draft.viewingFields(retainCompanionsJson(if (companionsKnown) before.toString() else null, companionNames(before), draft.companions))
    return if (isNew) proposed else JSONObject().apply { proposed.keys().forEach { key ->
        if (!sameCommandJson(original.get(key), proposed.get(key))) put(key, proposed.get(key))
    } }
}

internal fun CapturedViewing.payload(action: String, fields: JSONObject, titlePatch: JSONObject? = null): Pair<String, String> {
    require(action in setOf("insert", "update", "delete") && (action == "insert") == isNew)
    if (action == "delete") require(fields.length() == 0) else viewingWireValues(fields)
    val dispatch = completion == null && (action == "insert" || guard.known) && (titlePatch == null || titleGuard.known)
    val payload = if (dispatch) viewingCommandPayload(id, titleId, action, fields,
        if (action == "insert") null else guard.revision, if (action == "insert") null else guard.operationId)
    else JSONObject().put("id", id).put("titleId", titleId).put(VIEWING_REVIEW_INTENT, JSONObject().put("version", 1).put("ownerId", ownerId)
        .put("id", id).put("titleId", titleId).put("action", action).put("fields", fields))
    payload.put(VIEWING_OPENING, JSONObject(raw))
    if (completion != null) payload.put("completionIntent", awaitingCompletionPayload(completion, "viewing", action, fields).getJSONObject("completionIntent"))
    if (titlePatch != null) attachViewingTitleEffect(payload, titleMetadataPayload(ownerId, titleId, titlePatch, titleGuard.revision, titleGuard.operationId))
    if (action == "delete") payload.put("linkedOutings", JSONArray(linkedOutings))
    return (if (dispatch) VIEWING_COMMAND else if (completion != null) AWAITING_COMPLETION else "review") to canonicalViewingJson(payload)
}

internal fun capturedViewingOperationId(ownerId: String, token: String, kind: String, payload: String): String =
    UUID.nameUUIDFromBytes((ownerId + "\n" + token + "\n" + kind + "\n" + payload).toByteArray(Charsets.UTF_8)).toString()

/** Stable bytes across JSON implementations and process recreation, including object key order. */
internal fun canonicalViewingJson(value: Any): String = when (value) {
    JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonicalViewingJson(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalViewingJson(value.get(it)) }
    is String -> JSONObject.quote(value)
    is Number -> JSONObject.numberToString(value)
    is Boolean -> value.toString()
    else -> error("Unsupported saved viewing value")
}

fun viewingHistoryProtectionKeys(entries: List<OutboxEntity>, ownerId: String): Set<String> = buildSet {
    entries.filter { it.entityType == "viewing" }.forEach { entry ->
        val payload = runCatching { JSONObject(entry.payloadJson) }.getOrNull() ?: return@forEach
        val opening = payload.optJSONObject(VIEWING_OPENING) ?: return@forEach
        require(opening.getString("ownerId") == ownerId)
        add("viewing_history:${entry.entityId}")
        if (payload.has(VIEWING_TITLE_EFFECT)) add("title:" + opening.getString("titleId"))
        opening.getJSONArray("linkedOutings").let { ids -> (0 until ids.length()).forEach {
            val id = ids.getString(it).also(UUID::fromString)
            add("cinema_outing:$id")
        } }
    }
}
