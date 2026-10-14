package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

internal class OutingReversalTransport(private val client: SupabaseRestClient, private val sessions: () -> SupabaseSession?) {
    suspend fun push(entry: OutboxEntity): PushResult = withContext(Dispatchers.IO) {
        var accepted = false
        try {
            val session = sessions() ?: error("Account changed before reversal.")
            val command = reversalCommand(entry)
            fun active() { check(sessions()?.userId == session.userId) { "Account changed during reversal." } }
            val response = JSONObject(client.rpc("revert_cinema_outing", command.rpc(entry.id).toString(), session.accessToken))
            currentCoroutineContext().ensureActive(); active()
            if (checkedReversalResponse(entry, response, session.userId) != "applied") return@withContext PushResult.Review(
                "This outing or viewing changed. Compare the saved reversal before trying again.")
            accepted = true
            val outings = fetchViewingLinkedOutings(client, session, listOf(command.outingId), ::active)
            val viewing = command.viewingId?.let { id ->
                val rows = JSONArray(client.get("viewings", "id=eq.$id&user_id=eq.${session.userId}&select=*", session.accessToken))
                require(rows.length() <= 1)
                if (rows.length() == 0) null else rows.getJSONObject(0)
            }
            currentCoroutineContext().ensureActive(); active()
            val title = fetchViewingTitle(client, session, command.titleId, ::active)
            val envelope = JSONObject().put("receipt", response).put("currentOutings", outings)
                .put("currentViewing", viewing ?: JSONObject.NULL).put("currentTitle", title ?: JSONObject.NULL)
            checkedReversalCurrent(entry, envelope, session.userId)
            PushResult.Applied(envelope)
        } catch (error: CancellationException) { throw error }
        catch (error: SupabaseHttpException) {
            if (!accepted && error.postgresCode in setOf("40001", "23505")) PushResult.Review("The saved reversal needs comparison with current history.")
            else PushResult.Retry(error.message ?: "Could not confirm reversal. Retry the original saved command.")
        } catch (error: Exception) { PushResult.Retry(error.message ?: "Could not confirm reversal.") }
    }
}

internal fun checkedReversalCurrent(entry: OutboxEntity, envelope: JSONObject, ownerId: String): OutingReversalCommand {
    val command = reversalCommand(entry)
    require(checkedReversalResponse(entry, envelope.getJSONObject("receipt"), ownerId) == "applied")
    checkedViewingLinkedOutings(listOf(command.outingId), command.titleId, ownerId, envelope.getJSONObject("currentOutings"))
    require(envelope.has("currentViewing") && envelope.has("currentTitle"))
    if (!envelope.isNull("currentViewing")) envelope.getJSONObject("currentViewing").let { row ->
        require(row.getString("id") == command.viewingId && row.getString("user_id") == ownerId && row.getString("title_id") == command.titleId)
        require(row.isNull("outing_id") || row.getString("outing_id") == command.outingId)
        row.toCompletionViewing()
    }
    if (!envelope.isNull("currentTitle")) checkedCurrentTitle(envelope.getJSONObject("currentTitle"), command.titleId, ownerId)
    return command
}
