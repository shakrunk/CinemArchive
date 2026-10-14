package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

interface ProviderMergeRemote {
    suspend fun push(entry: OutboxEntity): PushResult
    suspend fun current(titleId: String): JSONObject
}

class ProviderMergeTransport(private val client: SupabaseRestClient, private val session: SessionSource,
    private val owner: TicketOwnerScope, private val active: () -> Boolean) : ProviderMergeRemote {
    private fun fence() { check(active() && session.currentSession()?.userId == owner.ownerId) { "This sign-in has ended." } }
    override suspend fun current(titleId: String): JSONObject = withContext(Dispatchers.IO) {
        fence(); val token = checkNotNull(session.currentSession()).accessToken
        val titles = exactMetadataArray(client.get("titles", "id=eq.$titleId&user_id=eq.${owner.ownerId}&select=*", token))
        fence(); require(titles.length() <= 1)
        val title = if (titles.length() == 0) null else checkedCurrentTitle(titles.getJSONObject(0), titleId, owner.ownerId)
        val viewings = JSONArray(); var after: String? = null
        if (title != null) while (true) {
            fence()
            val page = exactMetadataArray(client.get("viewings", "title_id=eq.$titleId&user_id=eq.${owner.ownerId}&select=*&order=id.asc&limit=500" +
                (after?.let { "&id=gt.$it" } ?: ""), token))
            fence(); if (page.length() == 0) break
            page.importObjects().forEach { row ->
                require(row.getString("user_id") == owner.ownerId && row.getString("title_id") == titleId)
                val id = row.getString("id").also(java.util.UUID::fromString)
                require(after == null || id > after!!); row.toCompletionViewing()
                viewings.put(row); after = id
            }
        }
        JSONObject().put("currentTitle", title ?: JSONObject.NULL).put("currentViewings", viewings)
    }
    override suspend fun push(entry: OutboxEntity): PushResult = withContext(Dispatchers.IO) {
        if (entry.operation == "review") return@withContext PushResult.Retry("Review this provider import in Import & sync.")
        var accepted = false
        try {
            val command = checkedProviderMerge(entry, owner)
            fence(); val token = checkNotNull(session.currentSession()).accessToken
            val raw = client.rpc("apply_library_command", metadataJson(JSONObject().put("p_operation_id", entry.id)
                .put("p_operations", command.operations)), token)
            accepted = true; fence()
            val receipt = exactMetadataObject(raw)
            checkedLibraryCommandReceipt(entry.id, command.operations, receipt, owner.ownerId)
            PushResult.Applied(current(command.titleId).put("receipt", receipt))
        } catch (error: CancellationException) { throw error }
        catch (error: SupabaseHttpException) {
            if (!accepted && error.postgresCode in setOf("40001", "23505", "23514", "22023", "23503"))
                PushResult.Review("The server rejected this atomic provider import. Compare its saved changes in Import & sync.")
            else PushResult.Retry("Confirmation is unavailable. Retry preserves the same provider import and cannot duplicate it.")
        } catch (_: Exception) { PushResult.Retry("The saved provider import remains available for exact retry.") }
    }
}
