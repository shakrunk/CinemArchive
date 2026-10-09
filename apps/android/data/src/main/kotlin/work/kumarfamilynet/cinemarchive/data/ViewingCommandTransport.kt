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
            val linkedOutings = viewingLinkedOutingIds(entry, session.userId)
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
            if (linkedOutings.isNotEmpty()) {
                val currentOutings = JSONObject()
                for (id in linkedOutings) {
                    currentCoroutineContext().ensureActive(); checkOwner()
                    val outings = JSONArray(client.get("cinema_outings", "id=eq.$id&user_id=eq.${session.userId}&select=*", session.accessToken))
                    require(outings.length() <= 1)
                    currentCoroutineContext().ensureActive(); checkOwner()
                    currentOutings.put(id, if (outings.length() == 0) JSONObject.NULL else outings.getJSONObject(0))
                }
                envelope.put("currentOutings", currentOutings)
                currentViewingLinkedOutings(entry, envelope, session.userId)
            }
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

/** Only exact IDs captured with the original delete may be read or projected by its ACK. */
internal fun viewingLinkedOutingIds(entry: OutboxEntity, ownerId: String): List<String> {
    val payload = JSONObject(entry.payloadJson)
    val opening = payload.optJSONObject(VIEWING_OPENING)
    if (opening != null) {
        require(opening.getString("ownerId") == ownerId && opening.getString("id") == entry.entityId &&
            opening.getString("titleId") == payload.getString("titleId")) { "Saved viewing belongs to another account or title." }
    }
    if (!payload.has("linkedOutings")) return emptyList()
    require(viewingCommandOperations(entry).getJSONObject(0).getString("action") == "delete")
    require(opening != null && sameCommandJson(opening.getJSONArray("linkedOutings"), payload.getJSONArray("linkedOutings")))
    val ids = payload.getJSONArray("linkedOutings").let { values -> (0 until values.length()).map { values.getString(it).also(java.util.UUID::fromString) } }
    require(ids.size == ids.distinct().size)
    return ids
}

internal fun currentViewingLinkedOutings(entry: OutboxEntity, envelope: JSONObject, ownerId: String): Map<String, JSONObject?> {
    val ids = viewingLinkedOutingIds(entry, ownerId)
    if (ids.isEmpty()) {
        require(!envelope.has("currentOutings") || envelope.getJSONObject("currentOutings").length() == 0)
        return emptyMap()
    }
    val rows = envelope.getJSONObject("currentOutings")
    require(rows.keys().asSequence().toSet() == ids.toSet()) { "Current linked outing state is incomplete." }
    val titleId = JSONObject(entry.payloadJson).getString("titleId")
    return ids.associateWith { id ->
        if (rows.isNull(id)) null else rows.getJSONObject(id).also {
            require(it.getString("id") == id && it.getString("user_id") == ownerId && it.getString("title_id") == titleId)
            it.toRecoveryOuting()
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
