package work.kumarfamilynet.cinemarchive.data

import java.time.OffsetDateTime
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
    if (outbox.pendingEntries().any { it.entityType == "title_catalog" && it.entityId == titleId }) return true
    // Like web refresh: fill stored seasons, append newly listed episodes and add newly announced seasons.
    val additions = catalogAdditions(titleId, storedCatalog(database, titleId), fresh.seasons, fillCoarse = true)
    return enqueueEpisodeCatalog(outbox, titleId, ownerId, additions, emptyList(), castForStored = true)
}

/** The stored season/episode graph of [titleId], for [catalogAdditions] and [episodeScheduleChanges]. */
internal suspend fun storedCatalog(database: LibraryDatabase, titleId: String): List<StoredSeason> {
    val episodes = database.episodeDao().observeEpisodes(titleId).first().groupBy { it.seasonId }
    return database.seasonDao().observeSeasons(titleId).first().map { season ->
        StoredSeason(season.id, season.seasonNumber, season.episodeCount, season.updatedAt,
            episodes[season.id].orEmpty().map { StoredEpisode(it.id, it.episodeNumber, it.episodeName, it.airDate) })
    }
}

/**
 * Queues one atomic catalog command: new season/episode rows under their catalog IDs, a grown
 * season count guarded by its observed revision, and narrow air date/name updates. It never
 * carries watch history; local rows are only written once the server confirms them.
 * [castForStored]: also refresh the cast of a stored season gaining rows (manual refresh).
 */
internal suspend fun enqueueEpisodeCatalog(
    outbox: MutationOutbox, titleId: String, ownerId: String, additions: List<SeasonAddition>,
    changes: List<EpisodeScheduleChange>, castForStored: Boolean,
): Boolean {
    if (additions.isEmpty() && changes.isEmpty()) return false
    val operations = JSONArray().put(JSONObject().put("table", "titles").put("action", "update")
        .put("key", JSONObject().put("id", titleId)).put("values", JSONObject()))
    changes.forEach { change ->
        operations.put(JSONObject().put("table", "episodes").put("action", "update")
            .put("key", JSONObject().put("id", change.episodeId)).put("values", JSONObject().apply {
                change.airDate?.let { put("air_date", it) }; change.name?.let { put("episode_name", it) }
            }))
    }
    val catalog = JSONArray()
    additions.forEach { addition ->
        val season = addition.season
        require(season.seasonNumber >= 0)
        val natural = JSONObject().put("title_id", titleId).put("season_number", season.seasonNumber)
        when {
            addition.newSeasonId != null -> operations.put(JSONObject().put("table", "seasons").put("action", "insert")
                .put("key", JSONObject().put("id", addition.newSeasonId)).put("values", JSONObject(natural.toString())
                    .put("episode_count", addition.episodes.size).put("episodes_watched", 0).put("air_year", season.airYear ?: JSONObject.NULL)))
            addition.storedSeasonId == null -> operations.put(JSONObject().put("table", "seasons").put("action", "ensure").put("key", natural)
                .put("values", JSONObject().put("episode_count", addition.episodes.size).put("air_year", season.airYear ?: JSONObject.NULL)))
            addition.grownCount != null -> operations.put(JSONObject().put("table", "seasons").put("action", "update")
                .put("key", JSONObject().put("id", addition.storedSeasonId)).put("values", JSONObject().put("episode_count", addition.grownCount))
                .put("expectedUpdatedAt", addition.expectedUpdatedAt))
        }
        val episodeCredits = JSONArray()
        addition.episodes.forEach { (id, episode) ->
            require(episode.episodeNumber >= 0)
            operations.put(JSONObject().put("table", "episodes").put("action", "insert")
                .put("key", JSONObject().put("id", id))
                .put("values", JSONObject(natural.toString()).put("episode_number", episode.episodeNumber)
                    .put("episode_name", episode.name ?: JSONObject.NULL)
                    .put("air_date", episode.airDate ?: JSONObject.NULL).put("runtime", episode.runtime?.takeIf { it > 0 } ?: JSONObject.NULL)
                    .put("synopsis", episode.synopsis ?: JSONObject.NULL).put("still_url", episode.stillUrl ?: JSONObject.NULL)))
            episodeCredits.put(JSONObject().put("episodeNumber", episode.episodeNumber).put("crew", JSONArray(
                episode.crew.distinctBy { it.tmdbPersonId to it.job }.map {
                    JSONObject().put("personId", it.tmdbPersonId).put("name", it.name).put("job", it.job)
                })))
        }
        val cast = if (addition.storedSeasonId == null || castForStored) season.cast else emptyList()
        catalog.put(JSONObject().put("seasonNumber", season.seasonNumber)
            .put("localSeasonId", addition.storedSeasonId ?: JSONObject.NULL)
            .put("countUpdated", addition.grownCount != null)
            .put("episodes", episodeCredits).put("cast", JSONArray(cast.distinctBy { it.tmdbPersonId }.map {
                JSONObject().put("personId", it.tmdbPersonId).put("name", it.name)
                    .put("character", it.characterName ?: JSONObject.NULL).put("order", it.order)
                    .apply { it.profileUrl?.let { value -> put("profileUrl", value) }; it.episodeCount?.let { value -> put("episodeCount", value) } }
            })))
    }
    assertImportOperations(operations)
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
        val table = op.getString("table")
        val action = op.getString("action")
        require(!op.has("expectedUpdatedAt") || table == "seasons" && action == "update")
        if (index == 0) require(table == "titles" && action == "update" &&
            op.getJSONObject("key").getString("id") == entry.entityId && op.getJSONObject("values").length() == 0)
        else if (action == "update" && table == "episodes") {
            requireIdKey(op)
            val values = op.getJSONObject("values")
            require(values.length() > 0 && values.keys().asSequence().all { it in EPISODE_CATALOG_FIELDS })
            requireEpisodeCatalogValues(values)
        } else if (action == "update") {
            // A stored season that grew: only its count, guarded by the observed revision.
            require(table == "seasons")
            requireIdKey(op)
            // PostgREST revisions carry an offset (+00:00), which Android's Instant.parse may reject.
            OffsetDateTime.parse(op.getString("expectedUpdatedAt"))
            val values = op.getJSONObject("values")
            require(values.keys().asSequence().toSet() == setOf("episode_count") && values.get("episode_count").let { it is Int && it > 0 })
        } else if (action == "insert") {
            // New rows under their catalog IDs (CatalogRowIds.kt), never with progress or history.
            requireIdKey(op)
            val values = op.getJSONObject("values")
            require(values.getString("title_id") == entry.entityId && values.getInt("season_number") >= 0)
            when (table) {
                "seasons" -> require(values.keys().asSequence().toSet() == setOf("title_id", "season_number", "episode_count", "episodes_watched", "air_year") &&
                    values.getInt("season_number") >= 1 && values.getInt("episode_count") > 0 && values.getInt("episodes_watched") == 0)
                "episodes" -> {
                    require(values.keys().asSequence().toSet() == setOf("title_id", "season_number", "episode_number") + EPISODE_CATALOG_FIELDS &&
                        values.getInt("episode_number") >= 0)
                    requireEpisodeCatalogValues(values)
                }
                else -> error("Unsupported catalog insert")
            }
        } else require(table in setOf("seasons", "episodes") && action == "ensure" &&
            op.getJSONObject("key").getString("title_id") == entry.entityId)
    }
    return operations
}

private val EPISODE_CATALOG_FIELDS = setOf("episode_name", "air_date", "runtime", "synopsis", "still_url")

private fun requireIdKey(op: JSONObject) {
    require(op.getJSONObject("key").keys().asSequence().toSet() == setOf("id"))
    java.util.UUID.fromString(op.getJSONObject("key").getString("id"))
}

private fun requireEpisodeCatalogValues(values: JSONObject) = values.keys().forEach { key ->
    if (key !in EPISODE_CATALOG_FIELDS) return@forEach
    val value = values.get(key)
    if (key == "runtime") require(value == JSONObject.NULL || value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() > 0)
    else require(value == JSONObject.NULL || value is String)
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
