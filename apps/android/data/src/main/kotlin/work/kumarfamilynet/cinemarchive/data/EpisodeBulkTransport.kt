package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

data class EpisodeBulkSnapshot(val title: JSONObject?, val seasons: List<JSONObject>, val watchedEpisodeIds: Set<String>)
interface EpisodeBulkRemote {
    suspend fun current(titleId: String): EpisodeBulkSnapshot
    suspend fun push(entry: OutboxEntity): PushResult
}

class EpisodeBulkTransport(private val client: SupabaseRestClient, private val sessions: SessionSource) : EpisodeBulkRemote {
    override suspend fun current(titleId: String): EpisodeBulkSnapshot = withContext(Dispatchers.IO) {
        java.util.UUID.fromString(titleId)
        val session = sessions.currentSession() ?: error("This sign-in has ended.")
        suspend fun ownedRows(table: String, query: String): List<JSONObject> {
            val result = mutableListOf<JSONObject>()
            val seen = mutableSetOf<String>()
            var offset = 0
            do {
                val page = JSONArray(client.get(table, "$query&user_id=eq.${session.userId}&limit=500&offset=$offset", session.accessToken))
                ensureActive(); check(sessions.currentSession()?.userId == session.userId)
                for (i in 0 until page.length()) result += page.getJSONObject(i).also { require(it.getString("user_id") == session.userId && seen.add(it.getString("id"))) { "Repeated or foreign-owned page row." } }
                offset += page.length()
            } while (page.length() == 500)
            return result
        }
        val title = ownedRows("titles", "id=eq.$titleId&select=*").also { require(it.size <= 1) }.singleOrNull()
        title?.let { checkedCurrentTitle(it, titleId, session.userId) }
        val seasons = ownedRows("seasons", "title_id=eq.$titleId&select=*&order=id").onEach { require(it.getString("title_id") == titleId) }
        val watches = ownedRows("episode_watch_events", "select=id,user_id,episode_id,episodes!inner(title_id)&episodes.title_id=eq.$titleId&order=id")
        EpisodeBulkSnapshot(title, seasons, watches.map { row ->
            require(row.getJSONObject("episodes").getString("title_id") == titleId)
            row.getString("episode_id")
        }.toSet())
    }

    override suspend fun push(entry: OutboxEntity): PushResult = withContext(Dispatchers.IO) {
        if (entry.operation == "review") return@withContext PushResult.Retry("Open the series or Profile saved title changes to review its pre-platform watches.")
        var received = false
        try {
            require(entry.operation == EPISODE_BULK_COMMAND)
            val session = sessions.currentSession() ?: error("This sign-in has ended.")
            val operations = bulkOperations(entry, session.userId)
            val receipt = JSONObject(client.rpc("apply_library_command", JSONObject().put("p_operation_id", entry.id)
                .put("p_operations", operations).toString(), session.accessToken))
            received = true
            ensureActive(); check(sessions.currentSession()?.userId == session.userId)
            checkedBulkReceipt(entry, receipt, session.userId)
            val snapshot = current(entry.entityId)
            ensureActive(); check(sessions.currentSession()?.userId == session.userId)
            PushResult.Applied(JSONObject().put("receipt", receipt).put("title", snapshot.title ?: JSONObject.NULL)
                .put("seasons", JSONArray(snapshot.seasons)))
        } catch (error: CancellationException) { throw error }
        catch (error: SupabaseHttpException) {
            if (!received && error.postgresCode in setOf("40001", "P0002", "23505"))
                PushResult.Review("This series changed. Compare the saved pre-platform watches before applying them.")
            else PushResult.Retry("Couldn't confirm the saved watches. Retry sync with the same request.")
        } catch (_: Exception) { PushResult.Retry("Couldn't confirm the saved watches. Retry sync with the same request.") }
    }
}
