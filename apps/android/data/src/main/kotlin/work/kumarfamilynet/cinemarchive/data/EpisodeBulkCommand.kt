package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

internal const val EPISODE_BULK = "episode_bulk"
internal const val EPISODE_BULK_COMMAND = "pre_platform_v1"

internal fun bulkPayload(entry: OutboxEntity, owner: String): JSONObject = JSONObject(entry.payloadJson).also {
    require(entry.entityType == EPISODE_BULK && it.getInt("version") == 1)
    require(it.getString("ownerId") == owner && it.getString("titleId") == entry.entityId && it.getString("operationId") == entry.id)
    UUID.fromString(entry.id); UUID.fromString(entry.entityId)
}

internal fun bulkTitleEntry(entry: OutboxEntity): OutboxEntity? {
    if (entry.entityType != EPISODE_BULK) return null
    val payload = JSONObject(entry.payloadJson)
    val effect = payload.optJSONObject("titleEffect") ?: return null
    return entry.copy(entityType = "title", operation = TITLE_METADATA_COMMAND, payloadJson = effect.toString())
}

internal fun bulkOperations(entry: OutboxEntity, owner: String, requireGuards: Boolean = true): JSONArray {
    val payload = bulkPayload(entry, owner)
    val operations = JSONArray()
    val ids = mutableSetOf<String>()
    val watches = payload.getJSONArray("watches")
    for (i in 0 until watches.length()) {
        val watch = watches.getJSONObject(i)
        val id = watch.getString("id"); UUID.fromString(id); require(ids.add(id))
        UUID.fromString(watch.getString("episodeId"))
        operations.put(JSONObject().put("table", "episode_watch_events").put("action", "insert")
            .put("key", JSONObject().put("id", id)).put("values", JSONObject().put("episode_id", watch.getString("episodeId"))
                .put("watched_at", JSONObject.NULL).put("notes", JSONObject.NULL).put("color_mode", JSONObject.NULL)))
    }
    val seasons = payload.getJSONArray("seasons")
    for (i in 0 until seasons.length()) {
        val season = seasons.getJSONObject(i)
        val id = season.getString("id"); UUID.fromString(id); require(ids.add(id))
        require(season.getInt("count") >= 0)
        val operation = JSONObject().put("table", "seasons").put("action", "update")
            .put("key", JSONObject().put("id", id)).put("values", JSONObject().put("episodes_watched", season.getInt("count")))
        if (!season.isNull("baseline")) { Instant.parse(season.getString("baseline")); operation.put("expectedUpdatedAt", season.getString("baseline")) }
        else check(!requireGuards) { "Sync this season before reviewing its progress." }
        operations.put(operation)
    }
    bulkTitleEntry(entry)?.let { title ->
        require(payload.isNull("seasonNumber"))
        val patch = titleMetadataPatch(title, owner)
        require(sameCommandJson(patch, JSONObject().put("status", "watched")))
        val effect = JSONObject(title.payloadJson).getJSONObject(TITLE_METADATA_DATA)
        if (effect.has("operation")) operations.put(titleMetadataOperation(title, owner))
        else check(!requireGuards) { "Sync or review the earlier title edit first." }
    }
    require(operations.length() in 1..50000) { "This series is too large to save in one change." }
    require(operations.toString().toByteArray().size <= 16 * 1024 * 1024)
    return operations
}

internal fun bulkProtectionKeys(entries: List<OutboxEntity>): Set<String> = buildSet {
    entries.filter { it.entityType == EPISODE_BULK }.forEach { entry ->
        add("episode_bulk:${entry.entityId}")
        val payload = JSONObject(entry.payloadJson)
        if (payload.has("titleEffect")) add("title:${entry.entityId}")
        for ((field, type) in listOf("watches" to "episode_watch_event", "seasons" to "season")) {
            val rows = payload.getJSONArray(field)
            for (i in 0 until rows.length()) add("$type:${rows.getJSONObject(i).getString("id")}")
        }
    }
}

internal fun checkedBulkReceipt(entry: OutboxEntity, receipt: JSONObject, owner: String) {
    val operations = bulkOperations(entry, owner)
    checkedLibraryCommandReceipt(entry.id, operations, receipt, owner).forEachIndexed { index, result ->
        val values = operations.getJSONObject(index).getJSONObject("values")
        val row = result.getJSONObject("row")
        if (result.getString("table") == "seasons") require(row.getString("title_id") == entry.entityId)
        require(values.keys().asSequence().all { row.has(it) && sameCommandJson(values.get(it), row.get(it)) }) {
            "Bulk receipt does not confirm the saved values."
        }
    }
}
