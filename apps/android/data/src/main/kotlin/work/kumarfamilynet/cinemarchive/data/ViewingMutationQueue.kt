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

internal fun captureViewingGuard(previous: ViewingEntity, alias: ViewingCompletionAliasEntity?, pending: List<OutboxEntity>): ViewingGuard {
    require(alias == null || (alias.canonicalViewingId == previous.id && alias.titleId == previous.titleId))
    val related = pending.filter { it.entityType == "viewing" && it.entityId in setOf(previous.id, alias?.provisionalViewingId) }
    if (related.any { it.entityId != previous.id || it.operation != VIEWING_COMMAND || runCatching {
            viewingCommandOperations(it)
            require(JSONObject(it.payloadJson).getString("titleId") == previous.titleId)
        }.isFailure }) return ViewingGuard()
    related.lastOrNull()?.let { return ViewingGuard(operationId = it.id) }
    if (pending.any { it.entityType == "outing_completion" && it.entityId == (alias?.outingId ?: previous.outingId) }) return ViewingGuard()
    if (previous.updatedAt != null) return ViewingGuard(revision = previous.updatedAt)
    if (alias != null) {
        return if (alias.canonicalViewingVersion != null) ViewingGuard(operationId = alias.completionOperationId) else ViewingGuard()
    }
    return ViewingGuard(revision = previous.updatedAt)
}

internal fun captureViewingTitleGuard(title: TitleEntity, pending: List<OutboxEntity>, ownerId: String): ViewingGuard {
    val related = pending.filter { it.entityType == "title" && it.entityId == title.id }
    if (related.any { it.operation != TITLE_METADATA_COMMAND || runCatching { titleMetadataOperation(it, ownerId) }.isFailure }) return ViewingGuard()
    // Completion title effects need the later joint lifecycle cutover. Never borrow its current title snapshot here.
    if (pending.any { it.entityType == "outing_completion" && runCatching {
            completionCommand(it.copy(operation = OUTING_COMPLETION)).titleId == title.id
        }.getOrDefault(true) }) return ViewingGuard()
    return related.lastOrNull()?.let { ViewingGuard(operationId = it.id) } ?: ViewingGuard(revision = title.updatedAt)
}

internal fun ViewingDraft.viewingFields() = JSONObject().put("date", date ?: JSONObject.NULL)
    .put("rating", rating ?: JSONObject.NULL).put("notes", notes ?: JSONObject.NULL).put("venue", venue ?: JSONObject.NULL)
    .put("companions", JSONArray().apply { companions.forEach { put(JSONObject().put("name", it)) } })

internal fun viewingOpening(ownerId: String, titleId: String, requestedId: String?, original: ViewingDraft,
    guard: ViewingGuard, titleGuard: ViewingGuard, linkedOutings: List<String>): String = canonicalViewingJson(JSONObject()
    .put("version", 1).put("ownerId", ownerId).put("titleId", titleId).put("id", original.id)
    .put("requestedId", requestedId ?: JSONObject.NULL).put("isNew", requestedId == null)
    .put("token", UUID.randomUUID().toString()).put("original", original.viewingFields())
    .put("guard", guard.json()).put("titleGuard", titleGuard.json()).put("linkedOutings", JSONArray(linkedOutings.sorted())))

internal data class CapturedViewing(val ownerId: String, val titleId: String, val id: String, val isNew: Boolean,
    val token: String, val original: JSONObject, val guard: ViewingGuard, val titleGuard: ViewingGuard,
    val linkedOutings: List<String>, val raw: String)

internal fun checkedViewingOpening(draft: ViewingDraft, ownerId: String, titleId: String): CapturedViewing {
    val raw = checkNotNull(draft.openingContext) { "Reopen the viewing editor to capture its original version." }
    val json = JSONObject(raw)
    require(json.keys().asSequence().toSet() == setOf("version", "ownerId", "titleId", "id", "requestedId", "isNew", "token", "original", "guard", "titleGuard", "linkedOutings"))
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
        guard("guard"), guard("titleGuard"), linked, raw)
}

internal fun CapturedViewing.fields(draft: ViewingDraft): JSONObject {
    val proposed = draft.viewingFields()
    return if (isNew) proposed else JSONObject().apply { proposed.keys().forEach { key ->
        if (!sameCommandJson(original.get(key), proposed.get(key))) put(key, proposed.get(key))
    } }
}

internal fun CapturedViewing.payload(action: String, fields: JSONObject): Pair<String, String> {
    require(action in setOf("insert", "update", "delete") && (action == "insert") == isNew)
    if (action == "delete") require(fields.length() == 0) else viewingWireValues(fields)
    val dispatch = action == "insert" || guard.known
    val payload = if (dispatch) viewingCommandPayload(id, titleId, action, fields,
        if (action == "insert") null else guard.revision, if (action == "insert") null else guard.operationId)
    else JSONObject().put(VIEWING_REVIEW_INTENT, JSONObject().put("version", 1).put("ownerId", ownerId)
        .put("id", id).put("titleId", titleId).put("action", action).put("fields", fields))
    payload.put(VIEWING_OPENING, JSONObject(raw))
    if (action == "delete") payload.put("linkedOutings", JSONArray(linkedOutings))
    return (if (dispatch) VIEWING_COMMAND else "review") to canonicalViewingJson(payload)
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
        opening.getJSONArray("linkedOutings").let { ids -> (0 until ids.length()).forEach {
            val id = ids.getString(it).also(UUID::fromString)
            add("cinema_outing:$id")
        } }
    }
}
