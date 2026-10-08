package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

/** The shared RPC returns immutable proof plus current owned state in one response. */
internal class OutingCompletionTransport(
    private val client: SupabaseRestClient,
    private val sessionProvider: () -> SupabaseSession?,
) {
    suspend fun push(entry: OutboxEntity): PushResult = withContext(Dispatchers.IO) {
        try {
            val command = completionCommand(entry)
            val session = sessionProvider() ?: error("Account changed before completion sync.")
            val response = JSONObject(client.rpc("complete_cinema_outing", command.rpc(entry.id).toString(), session.accessToken))
            currentCoroutineContext().ensureActive()
            check(sessionProvider()?.userId == session.userId) { "Account changed during completion sync." }
            val checked = checkedCompletionResponse(entry, response, session.userId)
            when (checked.status) {
                "applied", "already_completed" -> PushResult.Applied(response)
                else -> PushResult.Review("The outing changed or is no longer available. Your provisional history is saved for review.")
            }
        } catch (error: CancellationException) { throw error }
        catch (error: SupabaseHttpException) {
            if (error.postgresCode in setOf("40001", "23505"))
                PushResult.Review("Completion needs review. Your provisional history and pending edits are preserved.")
            else PushResult.Retry(error.message ?: "Could not confirm completion. Retry the same saved command.")
        } catch (error: Exception) {
            PushResult.Retry(error.message ?: "Could not confirm completion. Retry the same saved command.")
        }
    }
}
