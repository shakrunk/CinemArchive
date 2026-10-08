package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

/** Original command identity survives every unknown outcome; current state is read separately. */
internal class ViewingCommandTransport(
    private val client: SupabaseRestClient,
    private val sessionProvider: () -> SupabaseSession?,
) {
    suspend fun push(entry: OutboxEntity): PushResult = withContext(Dispatchers.IO) {
        var accepted = false
        try {
            val session = sessionProvider() ?: error("Account changed before viewing sync.")
            fun checkOwner() { check(sessionProvider()?.userId == session.userId) { "Account changed during viewing sync." } }
            val operations = viewingCommandOperations(entry)
            checkOwner()
            val receipt = JSONObject(client.rpc("apply_library_command", JSONObject().put("p_operation_id", entry.id)
                .put("p_operations", operations).toString(), session.accessToken))
            accepted = true
            currentCoroutineContext().ensureActive(); checkOwner()
            if (!viewingReceiptConfirmsIntent(entry, receipt, session.userId)) {
                if (operations.getJSONObject(0).getString("action") == "insert") return@withContext PushResult.Review(
                    "This viewing ID already belongs to different saved history. Review the original before changing it.")
                error("Viewing receipt does not confirm the saved fields. Retry the same command.")
            }
            val rows = JSONArray(client.get("viewings", "id=eq.${entry.entityId}&user_id=eq.${session.userId}&select=*", session.accessToken))
            require(rows.length() <= 1)
            currentCoroutineContext().ensureActive(); checkOwner()
            val envelope = JSONObject().put("receipt", receipt).put("current", if (rows.length() == 0) JSONObject.NULL else rows.getJSONObject(0))
            currentViewingCommandRow(entry, envelope, session.userId)
            PushResult.Applied(envelope)
        } catch (error: CancellationException) { throw error }
        catch (error: SupabaseHttpException) {
            if (!accepted && error.postgresCode in setOf("40001", "P0002", "23505"))
                PushResult.Review("This viewing changed or was removed. Review your saved change before applying it again.")
            else PushResult.Retry(error.message ?: "Could not confirm viewing sync. Retry the same saved command.")
        } catch (error: Exception) {
            PushResult.Retry(error.message ?: "Could not confirm viewing sync. Retry the same saved command.")
        }
    }
}

/** False denotes a valid identity receipt whose values differ, not an unverified response. */
internal fun viewingReceiptConfirmsIntent(entry: OutboxEntity, receipt: JSONObject, ownerId: String): Boolean {
    val operations = viewingCommandOperations(entry)
    val result = checkedLibraryCommandReceipt(entry.id, operations, receipt, ownerId).single()
    val operation = operations.getJSONObject(0)
    if (operation.getString("action") == "delete") return true
    val row = result.getJSONObject("row")
    validateViewingCommandRow(entry, row, ownerId)
    val values = operation.getJSONObject("values")
    return values.keys().asSequence().all { row.has(it) && sameCommandJson(values.get(it), row.get(it)) }
}

internal fun currentViewingCommandRow(entry: OutboxEntity, envelope: JSONObject, ownerId: String): JSONObject? {
    require(viewingReceiptConfirmsIntent(entry, envelope.getJSONObject("receipt"), ownerId)) { "Viewing receipt differs from the saved intent." }
    require(envelope.has("current")) { "Current viewing state is missing." }
    if (envelope.isNull("current")) return null
    return envelope.getJSONObject("current").also { validateViewingCommandRow(entry, it, ownerId) }
}

private fun validateViewingCommandRow(entry: OutboxEntity, row: JSONObject, ownerId: String) {
    require(row.getString("id") == entry.entityId && row.getString("user_id") == ownerId &&
        row.getString("title_id") == JSONObject(entry.payloadJson).getString("titleId")) { "Viewing identity or owner changed." }
    row.toCompletionViewing()
}
