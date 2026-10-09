package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

/** Receipt and current projection are separate: a historical receipt is never a fresh row. */
internal class OutingCommandTransport(
    private val client: SupabaseRestClient,
    private val sessionProvider: () -> SupabaseSession?,
) {
    suspend fun push(entry: OutboxEntity): PushResult = withContext(Dispatchers.IO) {
        var receiptReceived = false
        try {
            val session = sessionProvider() ?: error("Account changed before outing sync.")
            fun checkOwner() { check(sessionProvider()?.userId == session.userId) { "Account changed during outing sync." } }
            val operations = outingCommandOperations(entry)
            checkOwner()
            val receipt = JSONObject(client.rpc("apply_library_command", JSONObject().put("p_operation_id", entry.id)
                .put("p_operations", operations).toString(), session.accessToken))
            receiptReceived = true
            currentCoroutineContext().ensureActive(); checkOwner()
            val receiptRow = checkedLibraryCommandReceipt(entry.id, operations, receipt, session.userId)
                .single().getJSONObject("row")
            receiptRow.toRecoveryOuting()
            val operation = operations.getJSONObject(0)
            val intended = JSONObject(operation.getJSONObject("values").toString()).put("id", entry.entityId)
            if (!outingMatchesRemote(intended, receiptRow)) {
                if (operation.getString("action") == "insert") return@withContext PushResult.Review(
                    "An existing outing has different values. Open Profile > Saved outing changes to compare your saved plan.")
                error("Outing receipt does not match the requested fields. Retry to confirm the same command.")
            }
            val rows = JSONArray(client.get("cinema_outings", "id=eq.${entry.entityId}&user_id=eq.${session.userId}&select=*", session.accessToken))
            require(rows.length() <= 1)
            currentCoroutineContext().ensureActive(); checkOwner()
            val envelope = JSONObject().put("receipt", receipt).put("current", if (rows.length() == 0) JSONObject.NULL else rows.getJSONObject(0))
            currentOutingCommandRow(entry, envelope, session.userId)
            PushResult.Applied(envelope)
        } catch (error: CancellationException) { throw error }
        catch (error: SupabaseHttpException) {
            if (!receiptReceived && error.postgresCode in setOf("40001", "P0002", "23505"))
                PushResult.Review("The outing changed or is no longer available. Open Profile > Saved outing changes to compare your saved fields.")
            else PushResult.Retry(error.message ?: "Could not confirm outing sync. Retry the same saved command.")
        } catch (error: Exception) {
            PushResult.Retry(error.message ?: "Could not confirm outing sync. Retry the same saved command.")
        }
    }
}

internal fun currentOutingCommandRow(entry: OutboxEntity, envelope: JSONObject, ownerId: String): JSONObject? {
    val operations = outingCommandOperations(entry)
    val receiptRow = checkedLibraryCommandReceipt(entry.id, operations, envelope.getJSONObject("receipt"), ownerId)
        .single().getJSONObject("row")
    receiptRow.toRecoveryOuting()
    require(outingMatchesRemote(JSONObject(operations.getJSONObject(0).getJSONObject("values").toString())
        .put("id", entry.entityId), receiptRow)) { "Outing receipt does not confirm the saved fields." }
    require(envelope.has("current")) { "Current outing state is missing." }
    if (envelope.isNull("current")) return null
    return envelope.getJSONObject("current").also {
        require(it.getString("id") == entry.entityId && it.getString("user_id") == ownerId)
        it.toRecoveryOuting()
    }
}
