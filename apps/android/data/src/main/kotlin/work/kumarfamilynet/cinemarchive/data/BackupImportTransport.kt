package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

interface BackupImportRemote {
    suspend fun push(entry: OutboxEntity): PushResult
}

class BackupImportTransport(private val client: SupabaseRestClient, private val session: SessionSource,
    private val owner: TicketOwnerScope, private val current: () -> Boolean) : BackupImportRemote {
    override suspend fun push(entry: OutboxEntity): PushResult = withContext(Dispatchers.IO) {
        if (entry.operation == "review") return@withContext PushResult.Retry("Open Import & sync > Restore JSON to review this rejected import.")
        var accepted = false
        try {
            val command = checkedImportCommand(entry, owner)
            val captured = checkNotNull(session.currentSession())
            fun fence() { check(current() && captured.userId == owner.ownerId && session.currentSession()?.userId == owner.ownerId) { "This sign-in has ended." } }
            fence()
            val raw = client.rpc("apply_library_command", metadataJson(JSONObject().put("p_operation_id", entry.id)
                .put("p_operations", command.mapping.operations)), captured.accessToken)
            accepted = true
            ensureActive(); fence()
            val receipt = exactMetadataObject(raw)
            checkedLibraryCommandReceipt(entry.id, command.mapping.operations, receipt, owner.ownerId)
            val titleRows = exactMetadataArray(client.get("titles", "id=eq.${entry.entityId}&user_id=eq.${owner.ownerId}&select=*", captured.accessToken))
            ensureActive(); fence(); require(titleRows.length() <= 1)
            val title = if (titleRows.length() == 0) null else titleRows.getJSONObject(0).also { checkedCurrentTitle(it, entry.entityId, owner.ownerId) }
            suspend fun children(table: String): JSONArray {
                val result = JSONArray(); var after: String? = null
                while (true) {
                    fence()
                    val query = "title_id=eq.${entry.entityId}&user_id=eq.${owner.ownerId}&select=*&order=id.asc&limit=500" +
                        (after?.let { "&id=gt.$it" } ?: "")
                    val page = exactMetadataArray(client.get(table, query, captured.accessToken))
                    ensureActive(); fence()
                    if (page.length() == 0) return result
                    page.importObjects().forEach { row ->
                        val id = row.getString("id").also { require(java.util.UUID.fromString(it).toString() == it) }
                        require(after == null || id > after!!)
                        require(row.getString("title_id") == entry.entityId && row.getString("user_id") == owner.ownerId)
                        if (table == "viewings") row.toCompletionViewing() else row.toRecoveryOuting()
                        result.put(row); after = id
                    }
                }
            }
            val viewings = if (title == null) JSONArray() else children("viewings")
            val outings = if (title == null) JSONArray() else children("cinema_outings")
            fence()
            PushResult.Applied(JSONObject().put("receipt", receipt).put("currentTitle", title ?: JSONObject.NULL)
                .put("currentViewings", viewings).put("currentOutings", outings))
        } catch (error: CancellationException) { throw error }
        catch (error: SupabaseHttpException) {
            if (!accepted && error.postgresCode in setOf("40001", "23505", "23514", "22023", "23503"))
                PushResult.Review("The server rejected this complete title import. None of its rows were saved by this attempt. Review it in Restore JSON.")
            else PushResult.Retry("Import confirmation is unavailable. Retry keeps the same saved operation; it cannot duplicate the graph.")
        } catch (_: Exception) {
            PushResult.Retry("Import confirmation is unavailable. Your original graph remains saved for exact retry.")
        }
    }
}
