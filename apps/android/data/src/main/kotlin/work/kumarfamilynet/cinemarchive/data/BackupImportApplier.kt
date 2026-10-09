package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

class BackupImportApplier(private val database: LibraryDatabase, private val owner: TicketOwnerScope) {
    suspend fun apply(entry: OutboxEntity, envelope: JSONObject) {
        check(database.inTransaction())
        val queue = database.outboxDao().getPending()
        require(queue.firstOrNull() == entry)
        val command = checkedImportCommand(entry, owner)
        val results = checkedLibraryCommandReceipt(entry.id, command.mapping.operations, envelope.getJSONObject("receipt"), owner.ownerId)
        require(envelope.has("currentTitle"))
        // A later local deletion is authoritative locally, even when a saved receipt survives it.
        if (database.titleDao().getById(entry.entityId) == null) return
        if (envelope.isNull("currentTitle")) {
            database.titleDao().deleteById(entry.entityId)
            database.theaterInterestDao().deleteByTitleId(entry.entityId)
            return
        }
        val title = checkedCurrentTitle(envelope.getJSONObject("currentTitle"), entry.entityId, owner.ownerId)
        val later = queue.drop(1)
        applyCurrentViewingTitle(database, entry.entityId, title, later, owner.ownerId)
        fun rows(key: String): Map<String, JSONObject> {
            val rows = envelope.getJSONArray(key).importObjects()
            require(rows.all { it.getString("user_id") == owner.ownerId && it.getString("title_id") == entry.entityId })
            val map = rows.associateBy { it.getString("id") }
            require(map.size == rows.size)
            return map
        }
        val viewings = rows("currentViewings")
        command.mapping.graph.viewings.forEach { imported ->
            if (database.viewingDao().getById(imported.id) != null && later.none { importIntentReferences(it, setOf(imported.id)) }) {
                val current = viewings[imported.id]?.toCompletionViewing()
                if (current == null) database.viewingDao().deleteById(imported.id)
                else database.viewingDao().upsert(current)
            }
        }
        val outings = rows("currentOutings")
        command.mapping.graph.outings.forEach { imported ->
            if (database.cinemaOutingDao().getById(imported.id) != null && later.none { importIntentReferences(it, setOf(imported.id)) }) {
                val current = outings[imported.id]?.toRecoveryOuting()
                if (current == null) database.cinemaOutingDao().deleteById(imported.id)
                else database.cinemaOutingDao().upsert(current)
            }
        }
        // Natural-key credits receive generated canonical IDs, not historical field values.
        val credits = readCreditRows(database, entry.entityId)
        results.filter { it.getString("table") in setOf("title_cast", "title_crew", "season_cast", "episode_crew") }.forEach { result ->
            val row = credits.firstOrNull { it.table == result.getString("table") && sameCommandJson(it.key(), result.getJSONObject("key")) }
                ?: return@forEach
            val id = result.getJSONObject("row").getString("id")
            if (row.id != id) { deleteCreditRow(database, row); writeCreditRows(database, listOf(row.copy(id = id))) }
        }
    }
}

/** Only identity/dependency fields, never matching arbitrary notes or companion names. */
internal fun importIntentReferences(entry: OutboxEntity, identities: Set<String>): Boolean {
    if (entry.entityId in identities) return true
    fun contains(value: Any?, key: String = ""): Boolean = when (value) {
        is JSONObject -> value.keys().asSequence().any { contains(value.opt(it), it) }
        is org.json.JSONArray -> (0 until value.length()).any { contains(value.opt(it), key) }
        is String -> (key == "id" || key.endsWith("Id") || key.endsWith("_id") || key in setOf("linkedOutings", "protectedKeys")) &&
            (value in identities || value.substringAfter(':') in identities)
        else -> false
    }
    return runCatching { contains(exactMetadataObject(entry.payloadJson)) }.getOrDefault(true)
}
