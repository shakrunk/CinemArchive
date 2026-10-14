package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.model.MediaDetails

/** No provisional editable parents: provider data waits durably for canonical server IDs. */
internal suspend fun enqueueMissingEpisodeCatalog(
    database: LibraryDatabase, outbox: MutationOutbox, titleId: String, ownerId: String, fresh: MediaDetails,
): Boolean {
    val seasons = database.seasonDao().observeSeasons(titleId).first()
    if (seasons.isEmpty()) return false // Match web refresh: only an existing season graph is enriched.
    if (outbox.pendingEntries().any { it.entityType == "title_catalog" && it.entityId == titleId }) return true
    val episodes = database.episodeDao().observeEpisodes(titleId).first()
    val candidates = fresh.seasons.filter { remote ->
        remote.episodes.isNotEmpty() && seasons.firstOrNull { it.seasonNumber == remote.seasonNumber }.let { local ->
            if (local == null) remote.seasonNumber == 0 else episodes.none { it.seasonId == local.id }
        }
    }.distinctBy { it.seasonNumber }
    if (candidates.isEmpty()) return false
    val operations = JSONArray().put(JSONObject().put("table", "titles").put("action", "update")
        .put("key", JSONObject().put("id", titleId)).put("values", JSONObject()))
    val catalog = JSONArray()
    candidates.forEach { season ->
        require(season.seasonNumber >= 0)
        val fetched = season.episodes.distinctBy { it.episodeNumber }
        val key = JSONObject().put("title_id", titleId).put("season_number", season.seasonNumber)
        operations.put(JSONObject().put("table", "seasons").put("action", "ensure").put("key", key)
            .put("values", JSONObject().put("episode_count", fetched.size).put("air_year", season.airYear ?: JSONObject.NULL)))
        val episodeCredits = JSONArray()
        fetched.forEach { episode ->
            require(episode.episodeNumber >= 0)
            operations.put(JSONObject().put("table", "episodes").put("action", "ensure")
                .put("key", JSONObject(key.toString()).put("episode_number", episode.episodeNumber))
                .put("values", JSONObject().put("episode_name", episode.name ?: JSONObject.NULL)
                    .put("air_date", episode.airDate ?: JSONObject.NULL).put("runtime", episode.runtime ?: JSONObject.NULL)
                    .put("synopsis", episode.synopsis ?: JSONObject.NULL).put("still_url", episode.stillUrl ?: JSONObject.NULL)))
            episodeCredits.put(JSONObject().put("episodeNumber", episode.episodeNumber).put("crew", JSONArray(
                episode.crew.distinctBy { it.tmdbPersonId to it.job }.map {
                    JSONObject().put("personId", it.tmdbPersonId).put("name", it.name).put("job", it.job)
                })))
        }
        catalog.put(JSONObject().put("seasonNumber", season.seasonNumber)
            .put("localSeasonId", seasons.firstOrNull { it.seasonNumber == season.seasonNumber }?.id ?: JSONObject.NULL)
            .put("episodes", episodeCredits).put("cast", JSONArray(season.cast.distinctBy { it.tmdbPersonId }.map {
                JSONObject().put("personId", it.tmdbPersonId).put("name", it.name)
                    .put("character", it.characterName ?: JSONObject.NULL).put("order", it.order)
                    .apply { it.profileUrl?.let { value -> put("profileUrl", value) }; it.episodeCount?.let { value -> put("episodeCount", value) } }
            })))
    }
    outbox.enqueue("title_catalog", titleId, "ensure", JSONObject().put("ownerId", ownerId).put("titleId", titleId)
        .put("operations", operations).put("catalog", catalog))
    return true
}

internal fun episodeCatalogOperations(entry: OutboxEntity, ownerId: String): JSONArray {
    require(entry.entityType == "title_catalog" && entry.operation == "ensure")
    val payload = JSONObject(entry.payloadJson)
    require(payload.getString("ownerId") == ownerId && payload.getString("titleId") == entry.entityId)
    val operations = payload.getJSONArray("operations")
    require(operations.length() > 1)
    for (index in 0 until operations.length()) {
        val op = operations.getJSONObject(index)
        if (index == 0) require(op.getString("table") == "titles" && op.getString("action") == "update" &&
            op.getJSONObject("key").getString("id") == entry.entityId && op.getJSONObject("values").length() == 0)
        else if (op.getString("action") == "update") {
            require(op.getString("table") == "episodes")
            require(op.getJSONObject("key").keys().asSequence().toSet() == setOf("id"))
            java.util.UUID.fromString(op.getJSONObject("key").getString("id"))
            val values = op.getJSONObject("values")
            require(values.length() > 0 && values.keys().asSequence().all { it in setOf("episode_name", "air_date", "runtime", "synopsis", "still_url") })
            values.keys().forEach { key ->
                val value = values.get(key)
                if (key == "runtime") require(value == JSONObject.NULL || value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() > 0)
                else require(value == JSONObject.NULL || value is String)
            }
        } else require(op.getString("table") in setOf("seasons", "episodes") && op.getString("action") == "ensure" &&
            op.getJSONObject("key").getString("title_id") == entry.entityId)
    }
    return operations
}

/** Current rows must be a subset of the confirmed parents, never a replacement natural identity. */
internal fun currentEpisodeCatalogRows(entry: OutboxEntity, envelope: JSONObject, ownerId: String): List<JSONObject> {
    val confirmed = checkedLibraryCommandReceipt(entry.id, episodeCatalogOperations(entry, ownerId), envelope.getJSONObject("receipt"), ownerId)
        .filter { it.getString("table") != "titles" }
    val current = envelope.getJSONArray("currentRows")
    val seen = mutableSetOf<Pair<String, String>>()
    return (0 until current.length()).map { index ->
        current.getJSONObject(index).also { item ->
            val row = item.getJSONObject("row")
            val table = item.getString("table")
            val original = confirmed.singleOrNull { it.getString("table") == table && sameCommandJson(it.getJSONObject("key"), item.getJSONObject("key")) }
                ?: error("Current catalog row was not requested")
            require(row.getString("id") == original.getJSONObject("row").getString("id") && row.getString("user_id") == ownerId)
            if (item.getJSONObject("key").has("id")) require(row.getString("title_id") == entry.entityId)
            val key = item.getJSONObject("key")
            key.keys().forEach { require(sameCommandJson(key.get(it), row.opt(it))) }
            require(seen.add(table to row.getString("id"))) { "Duplicate current catalog row" }
        }
    }
}
