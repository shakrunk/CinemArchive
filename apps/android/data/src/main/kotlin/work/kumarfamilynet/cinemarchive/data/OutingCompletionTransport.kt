package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

/** The shared RPC returns immutable proof plus current owned state in one response. */
internal class OutingCompletionTransport(
    private val client: SupabaseRestClient,
    private val sessionProvider: () -> SupabaseSession?,
) {
    suspend fun push(entry: OutboxEntity): PushResult = withContext(Dispatchers.IO) {
        var accepted = false
        try {
            val command = completionCommand(entry)
            val session = sessionProvider() ?: error("Account changed before completion sync.")
            val response = JSONObject(client.rpc("complete_cinema_outing", command.rpc(entry.id).toString(), session.accessToken))
            currentCoroutineContext().ensureActive()
            check(sessionProvider()?.userId == session.userId) { "Account changed during completion sync." }
            val checked = checkedCompletionResponse(entry, response, session.userId)
            when (checked.status) {
                "applied", "already_completed" -> {
                    accepted = true
                    val canonical = checked.alias?.canonicalViewingId ?: return@withContext PushResult.Review(
                        "The completed outing has no proven viewing identity. Your provisional history is saved for review.")
                    // Deleting an outing unlinks its viewing. The RPC's scoped null therefore
                    // does not prove event deletion: fetch the exact owned identity separately.
                    val rows = JSONArray(client.get("viewings", "id=eq.$canonical&user_id=eq.${session.userId}&select=*", session.accessToken))
                    currentCoroutineContext().ensureActive()
                    check(sessionProvider()?.userId == session.userId) { "Account changed during completion confirmation." }
                    require(rows.length() <= 1)
                    val envelope = JSONObject().put("receipt", response)
                        .put("currentViewing", if (rows.length() == 0) JSONObject.NULL else rows.getJSONObject(0))
                    checkedCompletionEnvelope(entry, envelope, session.userId)
                    PushResult.Applied(envelope)
                }
                else -> PushResult.Review("The outing changed or is no longer available. Your provisional history is saved for review.")
            }
        } catch (error: CancellationException) { throw error }
        catch (error: SupabaseHttpException) {
            if (!accepted && error.postgresCode in setOf("40001", "23505"))
                PushResult.Review("Completion needs review. Your provisional history and pending edits are preserved.")
            else PushResult.Retry(error.message ?: "Could not confirm completion. Retry the same saved command.")
        } catch (error: Exception) {
            PushResult.Retry(error.message ?: "Could not confirm completion. Retry the same saved command.")
        }
    }
}
