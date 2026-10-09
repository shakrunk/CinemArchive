package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.flow.Flow

data class OutingRecoveryCard(val id: String, val title: String, val resolved: Boolean, val error: String? = null)
data class OutingRecoveryField(val key: String, val label: String, val saved: String, val current: String, val selectable: Boolean)
data class OutingRecoveryReview(
    val id: String,
    val title: String,
    val fields: List<OutingRecoveryField>,
    val remoteVersion: String?,
    val remoteExists: Boolean,
    val pendingAttempt: Boolean,
    val resolved: Boolean,
    val message: String? = null,
)
enum class OutingRecoveryOutcome { APPLIED, CONFIRMED, CHANGED, MISSING }

interface OutingRecoverySource {
    val changes: Flow<Unit>
    fun isActive(): Boolean
    suspend fun items(): List<OutingRecoveryCard>
    suspend fun review(id: String): OutingRecoveryReview
    suspend fun pendingAttempt(id: String): Boolean
    suspend fun apply(id: String, expectedVersion: String?, selected: Set<String>): OutingRecoveryOutcome
    suspend fun discard(id: String)
    suspend fun exportOriginal(id: String): String
}

internal val outingReviewFields = linkedMapOf(
    "showtime" to "Showtime", "previews_minutes" to "Previews (minutes)", "runtime_minutes" to "Runtime (minutes)",
    "venue" to "Theater", "companions" to "Companions", "format" to "Format", "ticket_price" to "Ticket price",
    "seat" to "Legacy seat", "auditorium" to "Auditorium", "seat_row" to "Row", "seats" to "Seats",
    "booking_ref" to "Booking reference", "notes" to "Notes",
)

internal val outingReadOnlyReviewFields = linkedMapOf(
    "ends_at" to "End time (calculated)", "status" to "Outing status", "previous_status" to "Previous title status",
    "completed_viewing_id" to "Linked viewing", "follow_up_dismissed_at" to "Follow-up dismissed",
    "ticket_image_path" to "Ticket attachment", "ticket_barcode_payload" to "Ticket barcode",
    "ticket_barcode_format" to "Barcode format",
)
