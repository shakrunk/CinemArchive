package work.kumarfamilynet.cinemarchive.data

import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.model.NOIR_MODES
import work.kumarfamilynet.cinemarchive.core.model.NOIR_PIN_KEY

internal const val TITLE_PIN = "title_pin"
internal const val TITLE_PIN_COMMAND = "pin_v1"
internal fun titlePinPayload(entry: OutboxEntity, owner: String): JSONObject = JSONObject(entry.payloadJson).also {
    require(entry.entityType == TITLE_PIN && entry.operation == TITLE_PIN_COMMAND && it.getInt("version") == 1)
    require(it.getString("ownerId") == owner && it.getString("titleId") == entry.entityId)
    UUID.fromString(entry.id); UUID.fromString(entry.entityId)
    require(it.has("variant") && (it.isNull("variant") || it.getString("variant") in NOIR_MODES))
}
internal fun titlePinOperations(entry: OutboxEntity, owner: String): JSONArray {
    val payload = titlePinPayload(entry, owner)
    return JSONArray().put(JSONObject().put("table", "user_title_pins")
        .put("action", if (payload.isNull("variant")) "delete" else "put")
        .put("key", JSONObject().put("title_id", entry.entityId).put("easter_egg_key", NOIR_PIN_KEY)).also {
            if (!payload.isNull("variant")) it.put("values", JSONObject().put("pinned_variant", payload.getString("variant")))
        })
}
internal fun checkedPinReceipt(entry: OutboxEntity, owner: String, receipt: JSONObject) {
    val operations = titlePinOperations(entry, owner)
    val result = checkedLibraryCommandReceipt(entry.id, operations, receipt, owner).single()
    operations.getJSONObject(0).optJSONObject("values")?.let {
        require(result.getJSONObject("row").getString("pinned_variant") == it.getString("pinned_variant"))
    }
}
internal fun checkedPinRow(row: JSONObject, owner: String, titleId: String? = null): String? {
    require(row.getString("user_id") == owner && row.getString("easter_egg_key") == NOIR_PIN_KEY)
    UUID.fromString(row.getString("title_id"))
    require(titleId == null || row.getString("title_id") == titleId)
    require(row.has("pinned_variant"))
    return if (row.isNull("pinned_variant")) null else row.getString("pinned_variant").also { require(it in NOIR_MODES) }
}
interface TitlePinRemote {
    suspend fun fetch(): Map<String, String>
    suspend fun push(entry: OutboxEntity): PushResult
}

class TitlePinTransport(private val client: SupabaseRestClient, private val sessions: SessionSource) : TitlePinRemote {
    private suspend fun active(owner: String) { currentCoroutineContext().ensureActive(); check(sessions.currentSession()?.userId == owner) { "This sign-in has ended." } }
    override suspend fun fetch(): Map<String, String> = withContext(Dispatchers.IO) {
        val session = checkNotNull(sessions.currentSession())
        val result = mutableMapOf<String, String>(); val seen = mutableSetOf<String>(); var offset = 0
        do {
            val page = JSONArray(client.get("user_title_pins", "select=*&user_id=eq.${session.userId}&easter_egg_key=eq.$NOIR_PIN_KEY&order=title_id&limit=500&offset=$offset", session.accessToken))
            active(session.userId)
            for (i in 0 until page.length()) {
                val row = page.getJSONObject(i); val id = row.getString("title_id")
                require(seen.add(id)) { "Repeated pin page." }
                checkedPinRow(row, session.userId)?.let { result[id] = it }
            }
            offset += page.length()
        } while (page.length() == 500)
        result
    }
    private suspend fun current(titleId: String, session: SupabaseSession): JSONObject? {
        val rows = JSONArray(client.get("user_title_pins", "select=*&user_id=eq.${session.userId}&title_id=eq.$titleId&easter_egg_key=eq.$NOIR_PIN_KEY", session.accessToken))
        active(session.userId); require(rows.length() <= 1)
        return if (rows.length() == 0) null else rows.getJSONObject(0).also { checkedPinRow(it, session.userId, titleId) }
    }
    override suspend fun push(entry: OutboxEntity): PushResult = withContext(Dispatchers.IO) {
        val session = sessions.currentSession() ?: return@withContext PushResult.Retry("Sign in to sync the saved filter.")
        var accepted = false
        try {
            val operations = titlePinOperations(entry, session.userId)
            val receipt = JSONObject(client.rpc("apply_library_command", JSONObject().put("p_operation_id", entry.id).put("p_operations", operations).toString(), session.accessToken))
            accepted = true; active(session.userId); checkedPinReceipt(entry, session.userId, receipt)
            val current = current(entry.entityId, session)
            PushResult.Applied(JSONObject().put("ownerId", session.userId).put("titleId", entry.entityId)
                .put("receipt", receipt).put("current", current ?: JSONObject.NULL))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            // Only a definite no-effect rejection and a fresh absent owned parent can resolve this preference.
            if (!accepted && error is SupabaseHttpException && error.postgresCode in setOf("P0002", "23503")) {
                try {
                    val rows = JSONArray(client.get("titles", "select=id,user_id&id=eq.${entry.entityId}&user_id=eq.${session.userId}", session.accessToken))
                    active(session.userId)
                    if (rows.length() == 0) return@withContext PushResult.Applied(JSONObject()
                        .put("ownerId", session.userId).put("titleId", entry.entityId)
                        .put("missingParent", true).put("rejectionCode", error.postgresCode))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Unknown state retains the immutable original request. */ }
            }
            PushResult.Retry("Couldn't confirm the saved filter. Retry sync with the same request.")
        }
    }
}
