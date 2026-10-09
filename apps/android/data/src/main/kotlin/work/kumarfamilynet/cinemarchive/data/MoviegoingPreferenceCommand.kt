package work.kumarfamilynet.cinemarchive.data

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.*

internal const val MOVIEGOING_COMMAND = "preference_v1"
internal val moviegoingTypes = setOf("venue_note", "theater_interest")

internal fun checkedPreferenceText(value: String, max: Int): String {
    require(value.codePointCount(0, value.length) <= max && '\u0000' !in value) { "The note contains unsupported text or is too long." }
    var i = 0
    while (i < value.length) {
        val c = value[i++]
        if (Character.isHighSurrogate(c)) require(i < value.length && Character.isLowSurrogate(value[i++]))
        else require(!Character.isLowSurrogate(c))
    }
    return value
}

internal fun normalizedVenue(value: String): String = value.trim(' ').also {
    checkedPreferenceText(it, 512); require(it.isNotEmpty()) { "Enter a venue first." }
}

internal fun preferenceOperation(entry: OutboxEntity, ownerId: String): JSONObject {
    require(entry.entityType in moviegoingTypes && entry.operation in setOf(MOVIEGOING_COMMAND, "review"))
    val p = JSONObject(entry.payloadJson)
    require(p.getInt("version") == 1 && p.getString("ownerId") == ownerId && p.getString("key") == entry.entityId)
    val op = p.getJSONObject("operation")
    val expected = if (entry.entityType == "venue_note") {
        require(normalizedVenue(entry.entityId) == entry.entityId)
        val desired = if (p.isNull("notes")) null else checkedPreferenceText(p.getString("notes"), 20_000)
        val action = op.getString("action")
        require(action in setOf("insert", "update", "delete") && (action == "delete") == (desired == null))
        JSONObject().put("table", "venue_notes").put("action", action).put("key", JSONObject().put("venue", entry.entityId)).apply {
            if (desired != null) put("values", JSONObject().put("notes", desired))
            if (action != "insert") {
                require(op.has("expectedUpdatedAt") != op.has("expectedOperationId"))
                if (op.has("expectedUpdatedAt")) put("expectedUpdatedAt", op.getString("expectedUpdatedAt").also(Instant::parse))
                else put("expectedOperationId", op.getString("expectedOperationId").also { UUID.fromString(it); require(it != entry.id) })
            }
        }
    } else {
        UUID.fromString(entry.entityId)
        val present = p.getBoolean("present")
        JSONObject().put("table", "theater_interest").put("action", if (present) "insert" else "delete")
            .put("key", JSONObject().put("id", entry.entityId)).apply {
                if (present) put("values", JSONObject().put("title_id", entry.entityId).put("created_at", p.getString("createdAt").also(Instant::parse)))
            }
    }
    require(sameCommandJson(op, expected)) { "Saved moviegoing command changed." }
    return op
}

internal fun checkedPreferenceRow(type: String, key: String, ownerId: String, row: JSONObject): JSONObject {
    UUID.fromString(row.getString("id")); require(row.getString("user_id") == ownerId)
    Instant.parse(row.getString("created_at")); Instant.parse(row.getString("updated_at"))
    if (type == "venue_note") {
        require(row.getString("venue") == key && normalizedVenue(key) == key)
        checkedPreferenceText(row.getString("notes"), 20_000)
    } else require(type == "theater_interest" && row.getString("id") == key && row.getString("title_id") == key)
    return row
}

internal fun JSONObject.toVenueNote() = VenueNoteEntity(getString("venue"), getString("notes"), getString("updated_at"),
    getString("id"), getString("updated_at"), getString("created_at"))
internal fun JSONObject.toTheaterInterest() = TheaterInterestEntity(getString("title_id"), getString("created_at"), getString("updated_at"))

internal class MoviegoingPreferenceTransport(private val client: SupabaseRestClient, private val session: () -> SupabaseSession?) {
    suspend fun current(type: String, key: String): JSONObject? = withContext(Dispatchers.IO) {
        val captured = checkNotNull(session()) { "This sign-in has ended." }
        val table = if (type == "venue_note") "venue_notes" else "theater_interest"
        val column = if (type == "venue_note") "venue" else "id"
        if (type == "venue_note") normalizedVenue(key) else UUID.fromString(key)
        val encoded = URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20")
        val rows = JSONArray(client.get(table, "$column=eq.$encoded&user_id=eq.${captured.userId}&select=*", captured.accessToken))
        check(session()?.userId == captured.userId) { "This sign-in has ended." }
        require(rows.length() <= 1)
        if (rows.length() == 0) null else checkedPreferenceRow(type, key, captured.userId, rows.getJSONObject(0))
    }
    suspend fun push(entry: OutboxEntity): PushResult = withContext(Dispatchers.IO) {
        var accepted = false
        try {
            val captured = checkNotNull(session()) { "This sign-in has ended." }
            val operation = preferenceOperation(entry, captured.userId)
            val receipt = JSONObject(client.rpc("apply_library_command", JSONObject().put("p_operation_id", entry.id)
                .put("p_operations", JSONArray().put(operation)).toString(), captured.accessToken))
            accepted = true
            check(session()?.userId == captured.userId)
            checkedPreferenceReceipt(entry, receipt, captured.userId)
            val current = current(entry.entityType, entry.entityId)
            check(session()?.userId == captured.userId)
            PushResult.Applied(JSONObject().put("receipt", receipt).put("current", current ?: JSONObject.NULL))
        } catch (e: CancellationException) { throw e }
        catch (e: SupabaseHttpException) {
            if (!accepted && e.postgresCode in setOf("40001", "23505", "P0002")) PushResult.Review("Compare your saved moviegoing preference with the current value in Profile.")
            else PushResult.Retry(e.message ?: "Retry this same saved preference.")
        } catch (e: Exception) { PushResult.Retry(e.message ?: "Retry this same saved preference.") }
    }
}

internal fun checkedPreferenceReceipt(entry: OutboxEntity, receipt: JSONObject, ownerId: String) {
    val operation = preferenceOperation(entry, ownerId)
    val effect = checkedLibraryCommandReceipt(entry.id, JSONArray().put(operation), receipt, ownerId).single()
    if (operation.getString("action") != "delete") {
        val row = checkedPreferenceRow(entry.entityType, entry.entityId, ownerId, effect.getJSONObject("row"))
        if (entry.entityType == "venue_note") require(row.getString("notes") == JSONObject(entry.payloadJson).getString("notes"))
    }
}

internal suspend fun applyPreferenceCurrent(database: LibraryDatabase, type: String, key: String, current: JSONObject?, later: List<OutboxEntity>, ownerId: String) {
    check(database.inTransaction())
    current?.let { checkedPreferenceRow(type, key, ownerId, it) }
    val pending = later.filter { it.entityType == type && it.entityId == key }
    if (type == "venue_note") {
        val projection = runCatching {
            var row = current?.toVenueNote()
            for (entry in pending) {
                val op = preferenceOperation(entry, ownerId)
                val p = JSONObject(entry.payloadJson)
                row = if (p.isNull("notes")) null else {
                    require(row != null || op.getString("action") == "insert")
                    row?.copy(notes = p.getString("notes")) ?: VenueNoteEntity(key, p.getString("notes"), p.getString("recordedAt"))
                }
            }
            row
        }
        if (projection.isSuccess) {
            projection.getOrNull()?.let { database.venueNoteDao().upsert(it) } ?: database.venueNoteDao().delete(key)
        }
    } else {
        var row = current?.toTheaterInterest()
        for (entry in pending) {
            preferenceOperation(entry, ownerId)
            val p = JSONObject(entry.payloadJson)
            row = if (p.getBoolean("present")) row ?: TheaterInterestEntity(key, p.getString("createdAt")) else null
        }
        if (row != null && database.titleDao().getById(key) != null) database.theaterInterestDao().upsert(row)
        else database.theaterInterestDao().deleteByTitleId(key)
    }
}
