package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

internal class EpisodeCatalogFillTransport(private val client: SupabaseRestClient, private val sessionProvider: () -> SupabaseSession) {
    suspend fun push(entry: OutboxEntity): PushResult = withContext(Dispatchers.IO) {
        val session = sessionProvider()
        fun checkOwner() { check(sessionProvider().userId == session.userId) { "This sign-in has ended" } }
        val operations = episodeCatalogOperations(entry, session.userId)
        val receipt = JSONObject(client.rpc("apply_library_command", JSONObject().put("p_operation_id", entry.id)
            .put("p_operations", operations).toString(), session.accessToken))
        currentCoroutineContext().ensureActive(); checkOwner()
        val confirmed = checkedLibraryCommandReceipt(entry.id, operations, receipt, session.userId)
            .filter { it.getString("table") != "titles" }
        val current = JSONArray()
        confirmed.groupBy { it.getString("table") }.forEach { (table, parents) ->
            parents.chunked(100).forEach { chunk ->
                val ids = chunk.map { it.getJSONObject("row").getString("id") }
                val rows = JSONArray(client.get(table, "id=in.(${ids.joinToString(",")})&user_id=eq.${session.userId}&title_id=eq.${entry.entityId}&select=*&limit=100", session.accessToken))
                currentCoroutineContext().ensureActive(); checkOwner()
                require(rows.length() <= chunk.size)
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val expected = chunk.singleOrNull { it.getJSONObject("row").getString("id") == row.getString("id") }
                        ?: error("Unexpected catalog parent returned")
                    current.put(JSONObject().put("table", table).put("key", expected.getJSONObject("key")).put("row", row))
                }
            }
        }
        val envelope = JSONObject().put("receipt", receipt).put("currentRows", current)
        currentEpisodeCatalogRows(entry, envelope, session.userId)
        PushResult.Applied(envelope)
    }
}
