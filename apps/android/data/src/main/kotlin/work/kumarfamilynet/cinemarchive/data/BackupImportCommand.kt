package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

internal const val BACKUP_IMPORT_COMMAND = "import_graph_v1"
internal const val BACKUP_IMPORT_DATA = "libraryImport"

internal data class BackupImportCommand(val scope: TicketOwnerScope, val title: JSONObject,
    val outings: List<JSONObject>, val admittedAt: String, val mapping: BackupGraphMapping)

fun isBackupImport(entry: OutboxEntity): Boolean = entry.entityType == "title" &&
    (entry.operation == BACKUP_IMPORT_COMMAND || runCatching { JSONObject(entry.payloadJson).has(BACKUP_IMPORT_DATA) }.getOrDefault(false))

internal fun importPayload(scope: TicketOwnerScope, title: JSONObject, outings: List<JSONObject>, at: String): String {
    val mapping = mapBackupGraph(title, outings, at)
    return metadataJson(JSONObject().put("ownerId", scope.ownerId).put("projectId", scope.projectId)
        .put("titleId", title.getString("id")).put(BACKUP_IMPORT_DATA, JSONObject().put("version", 1)
            .put("admittedAt", at).put("title", title).put("outings", JSONArray(outings)).put("operations", mapping.operations)))
}

internal fun checkedImportCommand(entry: OutboxEntity, scope: TicketOwnerScope? = null): BackupImportCommand {
    require(isBackupImport(entry) && entry.operation in setOf(BACKUP_IMPORT_COMMAND, "review"))
    UUID.fromString(entry.id)
    val root = exactMetadataObject(entry.payloadJson)
    require(root.keys().asSequence().toSet() == setOf("ownerId", "projectId", "titleId", BACKUP_IMPORT_DATA))
    val owner = TicketOwnerScope(root.getString("projectId"), root.getString("ownerId"))
    require(scope == null || scope == owner) { "The saved import belongs to another account or server." }
    UUID.fromString(owner.ownerId)
    val body = root.getJSONObject(BACKUP_IMPORT_DATA)
    require(body.keys().asSequence().toSet() == setOf("version", "admittedAt", "title", "outings", "operations"))
    require(body.getInt("version") == 1)
    val title = body.getJSONObject("title")
    require(root.getString("titleId") == entry.entityId && title.getString("id") == entry.entityId)
    val at = body.getString("admittedAt").also(Instant::parse)
    val outings = body.getJSONArray("outings").importObjects()
    val mapping = mapBackupGraph(title, outings, at)
    require(sameCommandJson(mapping.operations, body.getJSONArray("operations"))) { "The saved import operations do not match its graph." }
    val identities = mapping.operations.importObjects().mapNotNull { it.getJSONObject("key").opt("id") as? String }
    // Only the final completed-outing pointer update may address the same row twice.
    val insertIds = mapping.operations.importObjects().filter { it.getString("action") == "insert" }
        .mapNotNull { it.getJSONObject("key").opt("id") as? String }
    require(insertIds.distinct().size == insertIds.size)
    identities.forEach { require(UUID.fromString(it).toString() == it) }
    return BackupImportCommand(owner, title, outings, at, mapping)
}

/** Null means unrelated. A relevant malformed/rejected import is never revision evidence. */
internal fun importPredecessorFor(entry: OutboxEntity, table: String, rowId: String, ownerId: String): String? {
    if (!isBackupImport(entry)) return null
    val command = checkedImportCommand(entry)
    require(command.scope.ownerId == ownerId)
    val operations = command.mapping.operations.importObjects().filter {
        it.getString("table") == table && it.getJSONObject("key").optString("id") == rowId
    }
    if (operations.isEmpty()) return null
    require(entry.operation == BACKUP_IMPORT_COMMAND && operations.last().getString("action") != "delete") {
        "Review the saved import before changing its rows."
    }
    return entry.id
}

internal fun importRowKeys(command: BackupImportCommand): Set<String> = buildSet {
    val names = mapOf("titles" to "title", "seasons" to "season", "episodes" to "episode",
        "viewings" to "viewing", "cinema_outings" to "cinema_outing", "episode_watch_events" to "episode_watch_event",
        "episode_ratings" to "episode_rating", "episode_reviews" to "episode_review")
    command.mapping.operations.importObjects().forEach { op ->
        val id = op.getJSONObject("key").opt("id") as? String
        if (id != null) add(checkNotNull(names[op.getString("table")]) + ":" + id)
    }
    command.mapping.graph.let { graph ->
        graph.cast.forEach { add("title_cast:" + it.id) }; graph.crew.forEach { add("title_crew:" + it.id) }
        graph.seasonCast.forEach { add("season_cast:" + it.id) }; graph.episodeCrew.forEach { add("episode_crew:" + it.id) }
    }
}

fun backupImportProtectionKeys(entries: List<OutboxEntity>, owner: TicketOwnerScope): Set<String> = buildSet {
    entries.filter(::isBackupImport).forEach {
        val command = checkedImportCommand(it, owner)
        add("library_import:" + it.entityId); add("title_credits:" + it.entityId)
        addAll(importRowKeys(command))
    }
}
