package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus

internal const val TITLE_METADATA_COMMAND = "metadata_v2"
internal const val TITLE_METADATA_DATA = "titleMetadata"

internal fun checkedTitlePatch(patch: JSONObject): JSONObject {
    require(patch.length() > 0 && patch.keys().asSequence().all { it in catalogFields || it in setOf("tags", "status", "rating", "custom_watch_url", "in_home_collection", "physical_media") })
    validateCatalogPatch(patch)
    if (patch.has("tags")) {
        val tags = patch.getJSONArray("tags")
        require((0 until tags.length()).all { tags.get(it) is String })
    }
    if (patch.has("status")) LibraryStatus.valueOf(patch.getString("status").uppercase())
    if (patch.has("rating") && !patch.isNull("rating")) {
        require(patch.get("rating") is Number && patch.getDouble("rating").isFinite() && patch.getDouble("rating") in 0.0..5.0)
    }
    if (patch.has("custom_watch_url") && !patch.isNull("custom_watch_url")) require(patch.get("custom_watch_url") is String)
    if (patch.has("in_home_collection")) require(patch.get("in_home_collection") is Boolean)
    if (patch.has("physical_media")) {
        val rows = patch.getJSONArray("physical_media")
        val ids = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            require(row.get("id") is String && row.getString("id").isNotBlank() && ids.add(row.getString("id")))
            require(row.get("format") is String && row.getString("format").isNotBlank())
            for (field in listOf("edition", "notes")) if (row.has(field) && !row.isNull(field)) require(row.get(field) is String)
        }
    }
    validateMetadataText(patch)
    return patch
}

/** A legacy predecessor has no receipt. Retain intent for explicit review without guessing a revision. */
internal suspend fun MutationOutbox.enqueueTitleMetadata(previous: TitleEntity, patch: JSONObject, ownerId: String) {
    require(ownerId.isNotBlank())
    checkedTitlePatch(patch)
    val predecessor = pendingTitleIntents(pendingEntries(), previous.id).lastOrNull()
    val receiptPredecessor = predecessor?.takeIf {
        runCatching { checkedTitlePredecessor(it, ownerId) }.isSuccess
    }
    val draft = predecessor != null && receiptPredecessor == null
    val payload = titleMetadataPayload(ownerId, previous.id, patch,
        if (predecessor == null) previous.updatedAt else null, receiptPredecessor?.id)
    enqueue("title", previous.id, if (draft) "review" else TITLE_METADATA_COMMAND, payload)
}

internal fun checkedTitlePredecessor(entry: OutboxEntity, ownerId: String) {
    if (entry.entityType == PROVIDER_MERGE) {
        require(providerMergePredecessor(entry, "titles", entry.entityId, ownerId) == entry.id)
    } else if (isBackupImport(entry)) {
        require(importPredecessorFor(entry, "titles", entry.entityId, ownerId) == entry.id)
    } else if (entry.entityType == "viewing") {
        viewingCommandOperations(entry)
        titleMetadataOperation(checkNotNull(viewingTitleEntry(entry, ownerId)), ownerId)
    } else {
        require(entry.operation == TITLE_METADATA_COMMAND)
        titleMetadataOperation(entry, ownerId)
    }
}

internal fun titleMetadataPayload(ownerId: String, titleId: String, patch: JSONObject, baseline: String?, predecessor: String?): JSONObject {
    require(ownerId.isNotBlank() && titleId.isNotBlank() && !(baseline != null && predecessor != null))
    checkedTitlePatch(patch)
    val metadata = JSONObject().put("version", 1).put("patch", exactMetadataObject(metadataJson(patch)))
    if (baseline != null || predecessor != null) {
        val operation = JSONObject().put("table", "titles").put("action", "update")
            .put("key", JSONObject().put("id", titleId)).put("values", exactMetadataObject(metadataJson(patch)))
        if (baseline != null) { Instant.parse(baseline); operation.put("expectedUpdatedAt", baseline) }
        else operation.put("expectedOperationId", predecessor)
        metadata.put("operation", operation)
    }
    return JSONObject().put("ownerId", ownerId).put("titleId", titleId).put(TITLE_METADATA_DATA, metadata)
}

internal fun titleMetadataPatch(entry: OutboxEntity, ownerId: String): JSONObject {
    require(entry.entityType == "title")
    val payload = exactMetadataObject(entry.payloadJson)
    require(payload.getString("ownerId") == ownerId && payload.getString("titleId") == entry.entityId)
    val metadata = payload.getJSONObject(TITLE_METADATA_DATA)
    require(metadata.getInt("version") == 1)
    return checkedTitlePatch(metadata.getJSONObject("patch"))
}

internal fun titleMetadataOperation(entry: OutboxEntity, ownerId: String): JSONObject {
    val patch = titleMetadataPatch(entry, ownerId)
    val operation = exactMetadataObject(entry.payloadJson).getJSONObject(TITLE_METADATA_DATA).getJSONObject("operation")
    require(operation.keys().asSequence().all { it in setOf("table", "action", "key", "values", "expectedUpdatedAt", "expectedOperationId") })
    require(operation.getString("table") == "titles" && operation.getString("action") == "update")
    require(sameCommandJson(operation.getJSONObject("key"), JSONObject().put("id", entry.entityId)))
    require(sameCommandJson(operation.getJSONObject("values"), patch))
    require(operation.has("expectedUpdatedAt") != operation.has("expectedOperationId"))
    if (operation.has("expectedUpdatedAt")) Instant.parse(operation.getString("expectedUpdatedAt"))
    else require(operation.getString("expectedOperationId").let { it.isNotBlank() && it != entry.id })
    return operation
}

internal fun TitleEntity.withTitleMetadata(patch: JSONObject): TitleEntity {
    checkedTitlePatch(patch)
    return withCatalogMetadata(patch).copy(
        tags = if (patch.has("tags")) patch.getJSONArray("tags").let { values -> (0 until values.length()).map(values::getString) } else tags,
        status = if (patch.has("status")) patch.getString("status").uppercase() else status,
        rating = if (!patch.has("rating")) rating else if (patch.isNull("rating")) null else patch.getDouble("rating"),
        customWatchUrl = if (!patch.has("custom_watch_url")) customWatchUrl else if (patch.isNull("custom_watch_url")) null else patch.getString("custom_watch_url"),
        inHomeCollection = if (!patch.has("in_home_collection")) inHomeCollection else if (patch.isNull("in_home_collection")) null else patch.getBoolean("in_home_collection"),
        physicalMediaJson = if (!patch.has("physical_media")) physicalMediaJson else if (patch.isNull("physical_media")) null else metadataJson(patch.getJSONArray("physical_media")),
        // updatedAt remains an observed server revision, never a locally invented CAS baseline.
    )
}

internal fun mergeTitlePatches(patches: List<JSONObject>): JSONObject = JSONObject().also { merged ->
    patches.forEach { patch -> checkedTitlePatch(patch).keys().forEach { merged.put(it, patch.get(it)) } }
}
