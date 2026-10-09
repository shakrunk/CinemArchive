package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity

/** Recovery consumes the complete owner-only table row, rather than the public sharing payload. */
internal fun JSONObject.toRecoveryOuting(): CinemaOutingEntity {
    require(setOf("id", "user_id", "title_id", "showtime", "previews_minutes", "runtime_minutes", "ends_at",
        "venue", "companions", "format", "ticket_price", "seat", "auditorium", "seat_row", "seats", "booking_ref",
        "ticket_image_path", "ticket_barcode_payload", "ticket_barcode_format", "notes", "status",
        "previous_status", "completed_viewing_id", "follow_up_dismissed_at", "created_at", "updated_at").all(::has)) {
        "Incomplete owner outing response."
    }
    fun text(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
    fun instant(key: String) = getString(key).also { Instant.parse(it) }
    fun names(array: JSONArray) = (0 until array.length()).map { index ->
        when (val value = array.get(index)) {
            is JSONObject -> value.getString("name")
            is String -> value
            else -> error("Invalid companion.")
        }
    }
    return CinemaOutingEntity(
        id = getString("id"), titleId = getString("title_id"),
        showtime = instant("showtime"), previewsMinutes = getInt("previews_minutes"),
        runtimeMinutes = getInt("runtime_minutes"), endsAt = instant("ends_at"),
        venue = text("venue"), companions = names(getJSONArray("companions")), format = text("format"),
        companionsJson = getJSONArray("companions").toString(),
        ticketPrice = if (isNull("ticket_price")) null else getDouble("ticket_price"),
        seat = text("seat"), auditorium = text("auditorium"), seatRow = text("seat_row"),
        seats = getJSONArray("seats").let { values -> (0 until values.length()).map(values::getString) },
        bookingRef = text("booking_ref"), ticketImagePath = text("ticket_image_path"),
        ticketBarcodePayload = text("ticket_barcode_payload"), ticketBarcodeFormat = text("ticket_barcode_format"),
        notes = text("notes"), status = getString("status").uppercase(),
        previousStatus = text("previous_status")?.uppercase(), completedViewingId = text("completed_viewing_id"),
        followUpDismissedAt = text("follow_up_dismissed_at"), createdAt = instant("created_at"), updatedAt = instant("updated_at"),
    )
}
