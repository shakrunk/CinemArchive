package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity
import work.kumarfamilynet.cinemarchive.core.model.CinemaFormat

/** Only values changed by this local action belong in a patch. A cached row is not a remote baseline. */
internal fun CinemaOutingEntity.mutationPayload(previous: CinemaOutingEntity? = null): JSONObject {
    val current = fullMutationPayload()
    if (previous == null) return current
    val before = previous.fullMutationPayload()
    return JSONObject().put("id", id).put("updatedAt", updatedAt).apply {
        current.keys().forEach { key ->
            if (key !in setOf("id", "titleId", "createdAt", "updatedAt") &&
                !(if (key in setOf("showtime", "endsAt")) outingInstantEqual(current.get(key), before.get(key))
                    else outingJsonEqual(current.get(key), before.get(key)))) put(key, current.get(key))
        }
    }
}

private fun CinemaOutingEntity.fullMutationPayload() = JSONObject()
    .put("id", id).put("titleId", titleId)
    .put("showtime", showtime).put("previewsMinutes", previewsMinutes)
    .put("runtimeMinutes", runtimeMinutes).put("endsAt", endsAt)
    .put("venue", venue ?: JSONObject.NULL).put("companions", companionObjects(companionsJson, companions))
    .put("format", format?.let { CinemaFormat.fromWire(it)?.wireValue ?: it } ?: JSONObject.NULL)
    .put("ticketPrice", ticketPrice ?: JSONObject.NULL).put("seat", seat ?: JSONObject.NULL)
    .put("auditorium", auditorium ?: JSONObject.NULL).put("seatRow", seatRow ?: JSONObject.NULL)
    .put("seats", JSONArray(seats)).put("bookingRef", bookingRef ?: JSONObject.NULL)
    .put("ticketImagePath", ticketImagePath ?: JSONObject.NULL)
    .put("ticketBarcodePayload", ticketBarcodePayload ?: JSONObject.NULL)
    .put("ticketBarcodeFormat", ticketBarcodeFormat ?: JSONObject.NULL)
    .put("notes", notes ?: JSONObject.NULL).put("status", status)
    .put("previousStatus", previousStatus ?: JSONObject.NULL)
    .put("completedViewingId", completedViewingId ?: JSONObject.NULL)
    .put("followUpDismissedAt", followUpDismissedAt ?: JSONObject.NULL)
    .put("createdAt", createdAt).put("updatedAt", updatedAt)

private val outingColumns = mapOf(
    "showtime" to "showtime", "previewsMinutes" to "previews_minutes",
    "runtimeMinutes" to "runtime_minutes", "endsAt" to "ends_at", "venue" to "venue",
    "companions" to "companions", "format" to "format", "ticketPrice" to "ticket_price",
    "seat" to "seat", "auditorium" to "auditorium", "seatRow" to "seat_row", "seats" to "seats",
    "bookingRef" to "booking_ref", "ticketImagePath" to "ticket_image_path",
    "ticketBarcodePayload" to "ticket_barcode_payload", "ticketBarcodeFormat" to "ticket_barcode_format",
    "notes" to "notes", "status" to "status", "previousStatus" to "previous_status",
    "completedViewingId" to "completed_viewing_id", "followUpDismissedAt" to "follow_up_dismissed_at",
    "updatedAt" to "updated_at",
)

/** Explicit null clears a field; an absent key never touches it. Unknown format spellings survive. */
internal fun outingWireBody(payload: JSONObject, owner: String, insert: Boolean): JSONObject = JSONObject().apply {
    if (insert) {
        // Incomplete legacy entries cannot establish an exact match or prove a safe create.
        require((outingColumns.keys + setOf("id", "titleId", "createdAt")).all(payload::has)) {
            "Incomplete outing intent; saved changes require review."
        }
        put("id", payload.getString("id")).put("title_id", payload.getString("titleId"))
            .put("user_id", owner).put("created_at", payload.getString("createdAt"))
    }
    outingColumns.forEach { (key, column) ->
        if (payload.has(key)) {
            val value = payload.get(key)
            put(column, when {
                value == JSONObject.NULL -> JSONObject.NULL
                key == "format" -> CinemaFormat.fromWire(value as String)?.wireValue ?: value
                key == "status" || key == "previousStatus" -> (value as String).lowercase()
                key == "companions" -> JSONArray().also { result ->
                    val companions = value as JSONArray
                    for (index in 0 until companions.length()) {
                        val companion = companions.get(index)
                        result.put(if (companion is String) JSONObject().put("name", companion) else companion)
                    }
                }
                else -> value
            })
        }
    }
}

/** A read-only retry may ACK an exact already-applied intent, never infer a create from absence. */
internal fun outingMatchesRemote(expected: JSONObject, remote: JSONObject): Boolean =
    expected.keys().asSequence().filter { it != "updated_at" }.all { key ->
        remote.has(key) && when (key) {
            "showtime", "ends_at", "created_at", "follow_up_dismissed_at" ->
                outingInstantEqual(expected.get(key), remote.get(key))
            "format" -> {
                fun canonical(value: Any) = if (value is String) CinemaFormat.fromWire(value)?.wireValue ?: value else value
                outingJsonEqual(canonical(expected.get(key)), canonical(remote.get(key)))
            }
            else -> outingJsonEqual(expected.get(key), remote.get(key))
        }
    }

private fun outingInstantEqual(left: Any, right: Any): Boolean =
    if (left is String && right is String) runCatching { postgresInstant(left) == postgresInstant(right) }.getOrDefault(false)
    else outingJsonEqual(left, right)

/** PostgreSQL timestamptz rounds fractional seconds to microseconds, with ties to even. */
private fun postgresInstant(value: String): Instant = Instant.parse(value).let {
    Instant.ofEpochSecond(it.epochSecond, Math.rint(it.nano / 1000.0).toLong() * 1000L)
}

private fun outingJsonEqual(left: Any, right: Any): Boolean = when {
    left == JSONObject.NULL || right == JSONObject.NULL -> left === JSONObject.NULL && right === JSONObject.NULL
    left is JSONObject && right is JSONObject -> left.length() == right.length() &&
        left.keys().asSequence().all { right.has(it) && outingJsonEqual(left.get(it), right.get(it)) }
    left is JSONArray && right is JSONArray -> left.length() == right.length() &&
        (0 until left.length()).all { outingJsonEqual(left.get(it), right.get(it)) }
    left is Number && right is Number -> left.toString().toBigDecimal().compareTo(right.toString().toBigDecimal()) == 0
    else -> left == right
}

