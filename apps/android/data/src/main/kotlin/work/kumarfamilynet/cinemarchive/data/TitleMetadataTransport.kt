package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

interface TitleMetadataRemote {
    suspend fun current(titleId: String): JSONObject?
    suspend fun push(entry: OutboxEntity): PushResult
}

class TitleMetadataTransport(private val client: SupabaseRestClient, private val session: SessionSource) : TitleMetadataRemote {
    override suspend fun current(titleId: String): JSONObject? = withContext(Dispatchers.IO) {
        val before = session.currentSession() ?: error("This sign-in has ended")
        val rows = JSONArray(client.get("titles", "id=eq.$titleId&user_id=eq.${before.userId}&select=*", before.accessToken))
        ensureActive(); check(session.currentSession()?.userId == before.userId) { "This sign-in has ended" }
        require(rows.length() <= 1)
        if (rows.length() == 0) null else rows.getJSONObject(0).also { checkedCurrentTitle(it, titleId, before.userId) }
    }

    override suspend fun push(entry: OutboxEntity): PushResult = withContext(Dispatchers.IO) {
        if (entry.operation == "review") return@withContext PushResult.Retry("Open this title or Profile > Saved title changes to review your saved edits.")
        var received = false
        try {
            require(entry.operation == TITLE_METADATA_COMMAND)
            val before = session.currentSession() ?: error("This sign-in has ended")
            val operation = titleMetadataOperation(entry, before.userId)
            val receipt = JSONObject(client.rpc("apply_library_command", JSONObject().put("p_operation_id", entry.id)
                .put("p_operations", JSONArray().put(operation)).toString(), before.accessToken))
            received = true
            ensureActive(); check(session.currentSession()?.userId == before.userId) { "This sign-in has ended" }
            checkedTitleMetadataReceipt(entry, receipt, before.userId)
            val current = current(entry.entityId)
            ensureActive(); check(session.currentSession()?.userId == before.userId) { "This sign-in has ended" }
            PushResult.Applied(JSONObject().put("receipt", receipt).put("current", current ?: JSONObject.NULL))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: SupabaseHttpException) {
            if (!received && error.postgresCode in setOf("40001", "P0002", "23505"))
                PushResult.Review("This title changed or was removed. Compare your saved edits before applying them.")
            else PushResult.Retry("Couldn't confirm the saved title edit. Retry the same change.")
        } catch (_: Exception) { PushResult.Retry("Couldn't confirm the saved title edit. Retry the same change.") }
    }
}

internal fun checkedTitleMetadataReceipt(entry: OutboxEntity, receipt: JSONObject, ownerId: String): JSONObject {
    val operation = titleMetadataOperation(entry, ownerId)
    val row = checkedLibraryCommandReceipt(entry.id, JSONArray().put(operation), receipt, ownerId).single().getJSONObject("row")
    checkedCurrentTitle(row, entry.entityId, ownerId)
    val patch = operation.getJSONObject("values")
    require(patch.keys().asSequence().all { row.has(it) && sameCommandJson(JSONObject().put(it, patch.get(it)), JSONObject().put(it, row.get(it))) }) {
        "Title receipt does not confirm the saved fields."
    }
    return row
}

internal fun checkedCurrentTitle(row: JSONObject, titleId: String, ownerId: String): JSONObject {
    require(row.getString("id") == titleId && row.getString("user_id") == ownerId)
    Instant.parse(row.getString("updated_at"))
    checkedTitlePatch(JSONObject().put("tags", row.getJSONArray("tags")).put("status", row.getString("status"))
        .put("rating", row.get("rating")))
    return row
}
