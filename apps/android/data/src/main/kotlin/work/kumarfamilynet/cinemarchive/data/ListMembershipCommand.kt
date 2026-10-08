package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.ListItemEntity
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

internal const val MEMBERSHIP_COMMAND = "membership_v2"

/** Natural membership identity survives concurrent inserts with different surrogate UUIDs. */
internal fun membershipPayload(localId: String, listId: String, titleId: String, present: Boolean, addedAt: String?): JSONObject {
    listOf(localId, listId, titleId).forEach(UUID::fromString)
    require(present == (addedAt != null))
    addedAt?.let(Instant::parse)
    return JSONObject().put("version", 2).put("id", localId).put("listId", listId).put("titleId", titleId)
        .put("present", present).put("addedAt", addedAt ?: JSONObject.NULL)
}

internal fun membershipOperations(entry: OutboxEntity): JSONArray {
    require(entry.entityType == "list_item" && entry.operation == MEMBERSHIP_COMMAND)
    val payload = JSONObject(entry.payloadJson)
    require(payload.keys().asSequence().toSet() == setOf("version", "id", "listId", "titleId", "present", "addedAt"))
    require(payload.getInt("version") == 2 && payload.getString("id") == entry.entityId)
    val present = payload.get("present") as Boolean
    val addedAt = if (payload.isNull("addedAt")) null else payload.getString("addedAt")
    membershipPayload(entry.entityId, payload.getString("listId"), payload.getString("titleId"), present, addedAt)
    val operation = JSONObject().put("table", "list_items").put("action", if (present) "insert" else "delete")
        .put("key", JSONObject().put("list_id", payload.getString("listId")).put("title_id", payload.getString("titleId")))
    if (present) operation.put("values", JSONObject().put("added_at", addedAt))
    return JSONArray().put(operation)
}

internal fun membershipProjectionKey(listId: String, titleId: String) = "list_membership:$listId:$titleId"

/** Preserve the natural key even when the server row has a different UUID. */
internal fun membershipProtectionKeys(entries: List<OutboxEntity>): Set<String> = entries
    .filter { it.entityType == "list_item" && it.operation == MEMBERSHIP_COMMAND }
    .map { entry ->
        val key = membershipOperations(entry).getJSONObject(0).getJSONObject("key")
        membershipProjectionKey(key.getString("list_id"), key.getString("title_id"))
    }.toSet()

internal class ListMembershipTransport(
    private val client: SupabaseRestClient,
    private val sessionProvider: () -> SupabaseSession?,
) {
    suspend fun push(entry: OutboxEntity): PushResult = withContext(Dispatchers.IO) {
        try {
            val session = sessionProvider() ?: error("Sign in to sync saved list changes.")
            fun checkOwner() { check(sessionProvider()?.userId == session.userId) { "This sign-in has ended." } }
            val operations = membershipOperations(entry)
            val key = operations.getJSONObject(0).getJSONObject("key")
            val receipt = JSONObject(client.rpc("apply_library_command", JSONObject().put("p_operation_id", entry.id)
                .put("p_operations", operations).toString(), session.accessToken))
            currentCoroutineContext().ensureActive(); checkOwner()
            checkedLibraryCommandReceipt(entry.id, operations, receipt, session.userId)
            val rows = JSONArray(client.get("list_items", "list_id=eq.${key.getString("list_id")}&title_id=eq.${key.getString("title_id")}&user_id=eq.${session.userId}&select=*", session.accessToken))
            require(rows.length() <= 1)
            currentCoroutineContext().ensureActive(); checkOwner()
            val envelope = JSONObject().put("receipt", receipt).put("current", if (rows.length() == 0) JSONObject.NULL else rows.getJSONObject(0))
            currentMembership(entry, envelope, session.userId)
            PushResult.Applied(envelope)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { PushResult.Retry(error.message ?: "Could not confirm the saved list change. Retry the same command.") }
    }
}

private fun currentMembership(entry: OutboxEntity, envelope: JSONObject, ownerId: String): ListItemEntity? {
    val operations = membershipOperations(entry)
    checkedLibraryCommandReceipt(entry.id, operations, envelope.getJSONObject("receipt"), ownerId)
    require(envelope.has("current"))
    if (envelope.isNull("current")) return null
    val row = envelope.getJSONObject("current")
    val key = operations.getJSONObject(0).getJSONObject("key")
    require(row.getString("user_id") == ownerId && row.getString("list_id") == key.getString("list_id") &&
        row.getString("title_id") == key.getString("title_id"))
    val id = row.getString("id").also(UUID::fromString)
    val position = if (row.isNull("position")) null else row.getInt("position")
    return ListItemEntity(id, row.getString("list_id"), row.getString("title_id"), position,
        row.getString("added_at").also(Instant::parse), row.getString("updated_at").also(Instant::parse))
}

/** Canonical ACK and queue removal share the outbox Room transaction. */
class ListMembershipApplier(private val database: LibraryDatabase, private val ownerId: String) {
    suspend fun apply(entry: OutboxEntity, envelope: JSONObject) {
        check(database.inTransaction())
        val current = currentMembership(entry, envelope, ownerId)
        val payload = JSONObject(entry.payloadJson)
        val listId = payload.getString("listId")
        val titleId = payload.getString("titleId")
        val queue = database.outboxDao().getPending()
        require(queue.firstOrNull() == entry)
        // A later local remove/re-add is the visible intent. Its natural-key request
        // never needs retargeting, so even potentially dispatched payloads stay immutable.
        val later = queue.drop(1).any { pending ->
            pending.entityType == "list_item" && (pending.entityId == entry.entityId ||
                JSONObject(pending.payloadJson).let { it.optString("listId") == listId && it.optString("titleId") == titleId })
        }
        if (later) return
        database.listItemDao().deleteByListAndTitle(listId, titleId)
        if (current != null && database.listDao().getById(listId) != null && database.titleDao().getById(titleId) != null) {
            database.listItemDao().upsert(current)
        }
    }
}
