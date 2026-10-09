package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.database.ViewingCompletionAliasEntity

internal const val OUTING_COMPLETION = "complete_v1"
internal const val COMPLETION_COMMAND_DATA = "completionCommand"

/** Captured once before optimistic completion; retries never replace its zone or precondition. */
internal data class OutingCompletionCommand(
    val outingId: String,
    val titleId: String,
    val provisionalViewingId: String,
    val expectedUpdatedAt: String?,
    val expectedOperationId: String?,
    val timezone: String,
) {
    init {
        listOf(outingId, titleId, provisionalViewingId).forEach(::completionUuid)
        require((expectedUpdatedAt != null) != (expectedOperationId != null))
        expectedUpdatedAt?.let(Instant::parse)
        expectedOperationId?.let(::completionUuid)
        ZoneId.of(timezone)
    }

    fun persisted() = JSONObject().put("version", 1).put("outingId", outingId).put("titleId", titleId)
        .put("provisionalViewingId", provisionalViewingId).put("expectedUpdatedAt", expectedUpdatedAt ?: JSONObject.NULL)
        .put("expectedOperationId", expectedOperationId ?: JSONObject.NULL).put("timezone", timezone)

    fun request() = JSONObject(persisted().toString()).apply { remove("version"); remove("titleId"); put("kind", "outing.complete") }

    fun rpc(operationId: String) = JSONObject().put("p_outing_id", outingId).put("p_operation_id", operationId)
        .put("p_provisional_viewing_id", provisionalViewingId).put("p_expected_updated_at", expectedUpdatedAt ?: JSONObject.NULL)
        .put("p_expected_operation_id", expectedOperationId ?: JSONObject.NULL).put("p_tz", timezone)
}

internal fun completionCommand(entry: OutboxEntity): OutingCompletionCommand {
    require(entry.entityType == "outing_completion" && entry.operation == OUTING_COMPLETION)
    completionUuid(entry.id)
    val json = JSONObject(entry.payloadJson).getJSONObject(COMPLETION_COMMAND_DATA)
    require(json.keys().asSequence().toSet() == setOf("version", "outingId", "titleId", "provisionalViewingId", "expectedUpdatedAt", "expectedOperationId", "timezone"))
    require(json.getInt("version") == 1)
    return OutingCompletionCommand(json.getString("outingId"), json.getString("titleId"), json.getString("provisionalViewingId"),
        json.nullableString("expectedUpdatedAt"), json.nullableString("expectedOperationId"), json.getString("timezone")).also {
        require(it.outingId == entry.entityId && it.expectedOperationId != entry.id)
    }
}

internal data class CompletionAcknowledgment(
    val status: String,
    val alias: ViewingCompletionAliasEntity?,
    val outing: JSONObject?,
    val viewing: JSONObject?,
    val title: JSONObject?,
    val completionOutingVersion: String? = null,
    val completionTitleVersion: String? = null,
)

/** Validate identity and immutable proof separately from the current (possibly deleted) graph. */
internal fun checkedCompletionResponse(entry: OutboxEntity, response: JSONObject, ownerId: String): CompletionAcknowledgment {
    val command = completionCommand(entry)
    require(response.getString("operationId") == entry.id && response.getString("outingId") == command.outingId)
    val expected = command.request()
    val actual = JSONObject(response.getJSONObject("request").toString())
    // Postgres serializes timestamptz in its own equivalent spelling.
    if (command.expectedUpdatedAt != null) {
        require(Instant.parse(actual.getString("expectedUpdatedAt")) == Instant.parse(command.expectedUpdatedAt))
        actual.put("expectedUpdatedAt", command.expectedUpdatedAt)
    }
    require(sameCommandJson(expected, actual)) { "Completion receipt changed the saved request." }
    val status = response.getString("status")
    require(status in setOf("applied", "already_completed", "conflict", "missing"))
    val canonicalId = response.nullableString("canonicalViewingId")?.also(::completionUuid)
    if (status == "applied") require(canonicalId == command.provisionalViewingId)
    val outing = response.nullableObject("outing")?.also {
        ownedCompletionRow(it, command.outingId, command.titleId, ownerId)
        it.toRecoveryOuting()
    }
    val viewing = response.nullableObject("viewing")?.also {
        require(canonicalId != null && outing != null)
        ownedCompletionRow(it, canonicalId, command.titleId, ownerId)
        require(it.getString("outing_id") == command.outingId)
        Instant.parse(it.getString("updated_at"))
    }
    val title = response.nullableObject("title")?.also {
        require(outing != null && it.getString("id") == command.titleId)
        require(it.getString("status") in setOf("watchlist", "watching", "watched", "dropped"))
        Instant.parse(it.getString("updated_at"))
    }
    if (status == "missing") require(outing == null && viewing == null && title == null)
    if (status !in setOf("applied", "already_completed")) return CompletionAcknowledgment(status, null, outing, viewing, title)

    val effects = response.getJSONArray("rows")
    require(effects.length() in 1..3)
    val outingEffect = effects.getJSONObject(0)
    require(outingEffect.getString("table") == "cinema_outings" && !outingEffect.optBoolean("deleted"))
    require(sameCommandJson(outingEffect.getJSONObject("key"), JSONObject().put("id", command.outingId)))
    val originalOuting = outingEffect.getJSONObject("row")
    ownedCompletionRow(originalOuting, command.outingId, command.titleId, ownerId)
    // An old accepted receipt may contain a full, later outing snapshot. It proves identity
    // but must not silently authorize edits captured before completion was acknowledged.
    val outingVersion = if (response.has("completionOutingVersion")) {
        require(originalOuting.keys().asSequence().toSet() == setOf("id", "user_id", "title_id", "updated_at"))
        response.nullableString("completionOutingVersion").also { version ->
            val effectVersion = originalOuting.nullableString("updated_at")
            if (version == null) require(effectVersion == null)
            else require(effectVersion != null && Instant.parse(version) == Instant.parse(effectVersion))
        }
    } else null
    var nextEffect = 1
    val version = if (nextEffect < effects.length() && effects.getJSONObject(nextEffect).getString("table") == "viewings") {
        require(canonicalId != null)
        val effect = effects.getJSONObject(nextEffect++)
        require(!effect.optBoolean("deleted"))
        require(sameCommandJson(effect.getJSONObject("key"), JSONObject().put("id", canonicalId)))
        val row = effect.getJSONObject("row")
        require(row.keys().asSequence().toSet() == setOf("id", "user_id", "title_id", "outing_id", "updated_at"))
        ownedCompletionRow(row, canonicalId, command.titleId, ownerId)
        require(row.getString("outing_id") == command.outingId)
        row.getString("updated_at").also(Instant::parse)
    } else null // Older receipts prove identity only; never infer a baseline from current viewing.
    // Only the explicit marker and its matching minimal effect prove an actual status
    // change. A current title revision (or old reversal bookkeeping) is not that evidence.
    val titleVersion = if (response.has("completionTitleVersion")) {
        val value = response.get("completionTitleVersion")
        require(value == JSONObject.NULL || value is String)
        (value as? String)?.also(Instant::parse)
    } else null
    if (titleVersion != null) {
        require(nextEffect < effects.length()) { "Completion title effect is missing." }
        val effect = effects.getJSONObject(nextEffect++)
        require(effect.keys().asSequence().toSet() == setOf("table", "key", "row"))
        require(effect.getString("table") == "titles")
        require(sameCommandJson(effect.getJSONObject("key"), JSONObject().put("id", command.titleId)))
        val row = effect.getJSONObject("row")
        require(row.keys().asSequence().toSet() == setOf("id", "user_id", "updated_at"))
        require(row.getString("id") == command.titleId && row.getString("user_id") == ownerId)
        require(Instant.parse(row.getString("updated_at")) == Instant.parse(titleVersion))
    }
    require(nextEffect == effects.length()) { "Completion contains an unproven or unexpected effect." }
    val alias = canonicalId?.let { ViewingCompletionAliasEntity(command.provisionalViewingId, it, command.titleId,
        command.outingId, entry.id, version) }
    return CompletionAcknowledgment(status, alias, outing, viewing, title, outingVersion, titleVersion)
}

private fun ownedCompletionRow(row: JSONObject, id: String, titleId: String, ownerId: String) {
    require(row.getString("id") == id && row.getString("user_id") == ownerId && row.getString("title_id") == titleId)
}

private fun completionUuid(value: String) { require(UUID.fromString(value).toString() == value.lowercase()) }
private fun JSONObject.nullableString(key: String): String? { require(has(key)); return if (isNull(key)) null else getString(key) }
private fun JSONObject.nullableObject(key: String): JSONObject? { require(has(key)); return if (isNull(key)) null else getJSONObject(key) }
