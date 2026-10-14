package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.util.UUID
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.*

/** Captures the original row/guard once; a restored form cannot silently rebase a stale edit. */
class OutingScheduleCommands(
    private val db: LibraryDatabase,
    private val outbox: MutationOutbox,
    private val scope: TicketOwnerScope,
    private val isCurrentOwner: () -> Boolean,
) {
    private fun active() {
        check(isCurrentOwner() && outbox.outingOwnerScope == scope) { "The account changed. Reopen this outing." }
    }

    suspend fun prepare(titleId: String, outingId: String?): String = outbox.atomically {
        active()
        val title = checkNotNull(db.titleDao().getById(titleId)) { "This title is no longer in your library." }
        require(title.type == "MOVIE") { "Cinema outings require a movie." }
        val original = outingId?.let { checkNotNull(db.cinemaOutingDao().getById(it)) { "This outing is no longer available." } }
        require(original == null || original.titleId == titleId && original.status == OutingStatus.SCHEDULED.name) {
            "Only a scheduled outing can be edited."
        }
        val guard = original?.let { resolveOutingPrecondition(it, outbox.pendingEntries(), scope) }
        JSONObject().put("version", 1).put("ownerId", scope.ownerId).put("projectId", scope.projectId)
            .put("titleId", titleId).put("outingId", original?.id ?: UUID.randomUUID().toString())
            .put("operationId", UUID.randomUUID().toString()).put("capturedAt", Instant.now().toString())
            .put("original", original?.let { outingWireBody(it.mutationPayload(), scope.ownerId, true) } ?: JSONObject.NULL)
            .put("expectedUpdatedAt", (guard as? OutingPrecondition.Literal)?.updatedAt ?: JSONObject.NULL)
            .put("expectedOperationId", (guard as? OutingPrecondition.Operation)?.operationId ?: JSONObject.NULL)
            .put("review", guard is OutingPrecondition.Review)
            .put("companionsKnown", original == null || original.companionsJson != null)
            .also { active() }.toString()
    }

    suspend fun save(
        opening: String, showtime: Instant, previewsMinutes: Int, runtimeMinutes: Int,
        venue: String?, companions: List<String>, format: CinemaFormat?, ticketPrice: Double?,
        seating: SeatAssignment, bookingRef: String?, notes: String?,
    ): String {
        active()
        val captured = checkedScheduleOpening(opening)
        require(captured.getString("ownerId") == scope.ownerId && captured.getString("projectId") == scope.projectId)
        require(previewsMinutes in 0..120 && runtimeMinutes > 0) { "Use 0–120 preview minutes and a positive runtime." }
        require(ticketPrice == null || ticketPrice.isFinite() && ticketPrice in 0.0..9999.99 &&
            java.math.BigDecimal.valueOf(ticketPrice).stripTrailingZeros().scale() <= 2) {
            "Ticket price must be between 0 and 9999.99 with at most two decimal places."
        }
        val original = scheduleOriginal(captured)
        val id = captured.getString("outingId")
        val operationId = captured.getString("operationId")
        val instant = captured.getString("capturedAt")
        val base = original ?: CinemaOutingEntity(
            id, captured.getString("titleId"), showtime.toString(), previewsMinutes, runtimeMinutes,
            showtime.plusSeconds((previewsMinutes.toLong() + runtimeMinutes) * 60L).toString(),
            venue = null, format = null, ticketPrice = null,
            status = OutingStatus.SCHEDULED.name, createdAt = instant, updatedAt = instant)
        val desired = base.copy(showtime = showtime.toString(), previewsMinutes = previewsMinutes,
            runtimeMinutes = runtimeMinutes, endsAt = showtime.plusSeconds((previewsMinutes.toLong() + runtimeMinutes) * 60L).toString(),
            venue = venue, companions = companions,
            companionsJson = if (original == null) companionObjects(null, companions).toString()
                else retainCompanionsJson(original.companionsJson, original.companions, companions),
            format = if (original != null && CinemaFormat.fromWire(original.format) == format) original.format else format?.wireValue,
            ticketPrice = ticketPrice, auditorium = seating.auditorium, seatRow = seating.seatRow, seats = seating.seats,
            bookingRef = bookingRef, notes = notes, updatedAt = instant)
        val intent = desired.mutationPayload(original)
        val review = captured.getBoolean("review")
        val payload = if (review) intent else outingCommandPayload(intent, original == null,
            captured.nullableScheduleText("expectedUpdatedAt"), captured.nullableScheduleText("expectedOperationId"))
        val admitted = JSONObject().put("opening", captured).put("payload", payload).toString()
        outbox.atomically {
            active()
            db.outingScheduleAdmissionDao().payload(operationId)?.let { prior ->
                require(sameCommandJson(JSONObject(prior), JSONObject(admitted))) { "This saved operation was reused with different ticket details." }
                return@atomically // Never recreate its row or queue after ACK, deletion, review or discard.
            }
            checkNotNull(db.titleDao().getById(desired.titleId)) { "This title is no longer in your library." }
            val current = db.cinemaOutingDao().getById(id)
            if (original == null) {
                check(current == null) { "This outing ID is already in use; reopen the form." }
                db.cinemaOutingDao().upsert(desired)
            } else if (current != null) {
                require(current.titleId == original.titleId)
                val merged = outingWireBody(current.mutationPayload(), scope.ownerId, true)
                val fields = outingWireBody(intent, scope.ownerId, false)
                fields.keys().forEach { field -> if (field != "updated_at") merged.put(field, fields.get(field)) }
                val projected = merged.toRecoveryOuting(localUpdatedAt = current.updatedAt)
                db.cinemaOutingDao().upsert(projected.copy(
                    companionsJson = if (fields.has("companions")) projected.companionsJson else current.companionsJson))
            }
            if (original == null || intent.length() > 2) {
                outbox.enqueueCaptured(operationId, "cinema_outing", id, if (review) "review" else OUTING_COMMAND, payload.toString())
            }
            db.outingScheduleAdmissionDao().insert(OutingScheduleAdmissionEntity(operationId, admitted))
            active()
        }
        return id
    }
}

private fun JSONObject.nullableScheduleText(key: String): String? = if (isNull(key)) null else getString(key)

internal fun checkedScheduleOpening(raw: String): JSONObject = JSONObject(raw).also { opening ->
    require(opening.getInt("version") == 1)
    listOf("outingId", "operationId", "titleId", "ownerId").forEach { UUID.fromString(opening.getString(it)) }
    require(opening.getString("projectId").isNotBlank())
    Instant.parse(opening.getString("capturedAt"))
    val original = opening.optJSONObject("original")
    if (original != null) {
        require(original.getString("id") == opening.getString("outingId") &&
            original.getString("title_id") == opening.getString("titleId") &&
            original.getString("user_id") == opening.getString("ownerId"))
        require(original.getString("status") == "scheduled")
        require(opening.getBoolean("review") ||
            (opening.nullableScheduleText("expectedUpdatedAt") != null) !=
            (opening.nullableScheduleText("expectedOperationId") != null))
    } else require(!opening.getBoolean("review"))
}

private fun scheduleOriginal(opening: JSONObject): CinemaOutingEntity? = opening.optJSONObject("original")?.let { original ->
    original.toRecoveryOuting(localUpdatedAt = original.getString("updated_at"))
}?.let {
    if (opening.optBoolean("companionsKnown", false)) it else it.copy(companionsJson = null)
}

/** Presentation always uses the captured original, including after process restoration. */
fun capturedScheduleInitial(opening: String, ownerId: String?, titleId: String?): CinemaOuting? =
    checkedScheduleOpening(opening).also {
        require(it.getString("ownerId") == ownerId && it.getString("titleId") == titleId)
    }.let(::scheduleOriginal)?.toDomain()
