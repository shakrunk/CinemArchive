package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingDao
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleDao
import work.kumarfamilynet.cinemarchive.core.database.VenueNoteDao
import work.kumarfamilynet.cinemarchive.core.database.VenueNoteEntity
import work.kumarfamilynet.cinemarchive.core.database.ViewingDao
import work.kumarfamilynet.cinemarchive.core.database.ViewingEntity
import work.kumarfamilynet.cinemarchive.core.model.CinemaFormat
import work.kumarfamilynet.cinemarchive.core.model.CinemaOuting
import work.kumarfamilynet.cinemarchive.core.model.CinemaOutingRules
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.OutingStatus
import work.kumarfamilynet.cinemarchive.core.model.OutingTransition
import work.kumarfamilynet.cinemarchive.core.model.SeatAssignment
import work.kumarfamilynet.cinemarchive.core.model.TicketBarcodeFormat

/**
 * Owns [CinemaOuting] CRUD and the offline completion projection. Writes land in Room and
 * [outbox] atomically. New plans carry explicit insert intent; edits carry only changed
 * fields, so a stale local snapshot cannot replay unrelated remote fields.
 */
class OutingsRepository(
    private val cinemaOutingDao: CinemaOutingDao,
    private val viewingDao: ViewingDao,
    private val titleDao: TitleDao,
    private val outbox: MutationOutbox,
    private val venueNoteDao: VenueNoteDao,
    private val alarmScheduler: OutingAlarmScheduler = NoOpOutingAlarmScheduler,
) {
    fun observeOutingsForTitle(titleId: String): Flow<List<CinemaOuting>> =
        cinemaOutingDao.observeOutingsForTitle(titleId).map { rows -> rows.map { it.toDomain() } }

    fun observeAllOutings(): Flow<List<CinemaOuting>> =
        cinemaOutingDao.observeAllOutings().map { rows -> rows.map { it.toDomain() } }

    /** Distinct past venues, most-recently-used first — sourced from the user's own outing
     *  history rather than a dedicated `venues` table (issue #197). */
    fun observeVenueSuggestions(): Flow<List<String>> =
        cinemaOutingDao.observeAllOutings().map { rows ->
            rows.sortedByDescending { it.createdAt }
                .mapNotNull { it.venue?.trim()?.takeIf(String::isNotBlank) }
                .distinct()
        }

    /** Distinct past companion names, most-recently-used first, flattened out of every
     *  outing's [CinemaOutingEntity.companions] list (issue #198). */
    fun observeCompanionSuggestions(): Flow<List<String>> =
        cinemaOutingDao.observeAllOutings().map { rows ->
            rows.sortedByDescending { it.createdAt }
                .flatMap { it.companions }
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
        }

    /** Per-venue parking/transit notes, keyed by venue name (issue #214). */
    fun observeVenueNotes(): Flow<Map<String, String>> =
        venueNoteDao.observeAll().map { rows -> rows.associate { it.venue to it.notes } }

    suspend fun saveVenueNotes(venue: String, notes: String) {
        val trimmedVenue = venue.trim()
        if (trimmedVenue.isEmpty()) return
        venueNoteDao.upsert(VenueNoteEntity(venue = trimmedVenue, notes = notes, updatedAt = Instant.now().toString()))
    }

    /** "I've got tickets" — creates a new scheduled outing. [endsAt] is computed here
     *  (`showtime + previewsMinutes + runtimeMinutes`) rather than left to the caller, per
     *  the web plan's rule that it's a snapshot recomputed on every edit, not just at
     *  creation. */
    suspend fun scheduleOuting(
        titleId: String,
        showtime: Instant,
        previewsMinutes: Int,
        runtimeMinutes: Int,
        venue: String?,
        companions: List<String>,
        format: CinemaFormat?,
        ticketPrice: Double?,
        seating: SeatAssignment,
        bookingRef: String?,
        notes: String?,
    ): String {
        val id = UUID.randomUUID().toString()
        val nowIso = Instant.now().toString()
        val entity = CinemaOutingEntity(
            id = id,
            titleId = titleId,
            showtime = showtime.toString(),
            previewsMinutes = previewsMinutes,
            runtimeMinutes = runtimeMinutes,
            endsAt = endsAt(showtime, previewsMinutes, runtimeMinutes).toString(),
            venue = venue,
            companions = companions,
            format = format?.wireValue,
            ticketPrice = ticketPrice,
            // A new outing never gets a legacy free-text `seat` — that column only ever
            // holds what pre-#221 rows already had (see SeatAssignment's kdoc).
            seat = null,
            auditorium = seating.auditorium,
            seatRow = seating.seatRow,
            seats = seating.seats,
            bookingRef = bookingRef,
            notes = notes,
            status = OutingStatus.SCHEDULED.name,
            createdAt = nowIso,
            updatedAt = nowIso,
        )
        outbox.atomically {
            cinemaOutingDao.upsert(entity)
            enqueueOutingMutation(entity)
        }
        rearmAlarm()
        return id
    }

    /** Edits/reschedules an existing outing — recomputes [CinemaOutingEntity.endsAt] the same
     *  way [scheduleOuting] does, so a showtime edit can't leave it stale. */
    suspend fun updateOuting(
        outingId: String,
        showtime: Instant,
        previewsMinutes: Int,
        runtimeMinutes: Int,
        venue: String?,
        companions: List<String>,
        format: CinemaFormat?,
        ticketPrice: Double?,
        seating: SeatAssignment,
        bookingRef: String?,
        notes: String?,
    ) {
        val applied = outbox.atomically {
            val existing = cinemaOutingDao.getById(outingId) ?: return@atomically false
            val updated = existing.copy(
                showtime = showtime.toString(),
                previewsMinutes = previewsMinutes,
                runtimeMinutes = runtimeMinutes,
                endsAt = endsAt(showtime, previewsMinutes, runtimeMinutes).toString(),
                venue = venue,
                companions = companions,
                format = if (CinemaFormat.fromWire(existing.format) == format) existing.format else format?.wireValue,
                ticketPrice = ticketPrice,
                // `seat` is deliberately carried forward untouched: the edit form has no input
                // for it, so taking it from `seating` would erase a pre-#221 row's only seat
                // record the first time it's edited for any other reason.
                auditorium = seating.auditorium,
                seatRow = seating.seatRow,
                seats = seating.seats,
                bookingRef = bookingRef,
                notes = notes,
                updatedAt = Instant.now().toString(),
            )
            cinemaOutingDao.upsert(updated)
            enqueueOutingMutation(updated, existing)
            true
        }
        if (applied) rearmAlarm()
    }

    /** Attaches a captured ticket photo (GitHub #219) to an outing, plus whatever barcode
     *  [work.kumarfamilynet.cinemarchive.core.designsystem.decodeTicketBarcode] managed to read
     *  off it. [barcodePayload]/[barcodeFormat] are null when nothing decoded — the photo is
     *  still saved as the visual proof-of-ticket, TicketScreen just has no code to render for
     *  it. Overwrites any previous capture on this outing (one ticket photo at a time). */
    suspend fun saveTicketCapture(
        outingId: String,
        imagePath: String,
        barcodePayload: String?,
        barcodeFormat: TicketBarcodeFormat?,
    ) {
        outbox.atomically {
            val existing = cinemaOutingDao.getById(outingId) ?: return@atomically
            val updated = existing.copy(
                ticketImagePath = imagePath,
                ticketBarcodePayload = barcodePayload,
                ticketBarcodeFormat = barcodeFormat?.name,
                updatedAt = Instant.now().toString(),
            )
            cinemaOutingDao.upsert(updated)
            enqueueOutingMutation(updated, existing)
        }
    }

    /** Removes a captured ticket photo/barcode, e.g. before re-capturing or if it was added by
     *  mistake. Does not touch [CinemaOutingEntity.bookingRef] — that's a separately-entered
     *  field, not part of the capture. */
    suspend fun clearTicketCapture(outingId: String) {
        outbox.atomically {
            val existing = cinemaOutingDao.getById(outingId) ?: return@atomically
            val updated = existing.copy(
                ticketImagePath = null,
                ticketBarcodePayload = null,
                ticketBarcodeFormat = null,
                updatedAt = Instant.now().toString(),
            )
            cinemaOutingDao.upsert(updated)
            enqueueOutingMutation(updated, existing)
        }
    }

    /** Cancels a still-scheduled outing (before the show ends) — kept as a row for history,
     *  never transitions to completed. No-op for an outing that's already completed/missed/
     *  cancelled. */
    suspend fun cancelOuting(outingId: String) {
        val applied = outbox.atomically {
            val existing = cinemaOutingDao.getById(outingId) ?: return@atomically false
            if (existing.status != OutingStatus.SCHEDULED.name) return@atomically false
            val updated = existing.copy(status = OutingStatus.CANCELLED.name, updatedAt = Instant.now().toString())
            cinemaOutingDao.upsert(updated)
            enqueueOutingMutation(updated, existing)
            true
        }
        if (applied) rearmAlarm()
    }

    /** Stamps the 14-day follow-up window closed without rating — the post-show card/inbox
     *  item's ✕ dismissal (web plan §4.4). */
    suspend fun dismissFollowUp(outingId: String) {
        outbox.atomically {
            val existing = cinemaOutingDao.getById(outingId) ?: return@atomically
            val updated = existing.copy(followUpDismissedAt = Instant.now().toString(), updatedAt = Instant.now().toString())
            cinemaOutingDao.upsert(updated)
            enqueueOutingMutation(updated, existing)
        }
    }

    /** "Didn't make it" — reverts a completion: deletes the auto-logged viewing, restores the
     *  title's [CinemaOutingEntity.previousStatus] iff it's still `WATCHED` (a manual status
     *  change in between is left alone, per the web plan's rule 6), and moves the outing to
     *  `MISSED`. Hidden by the UI once the viewing has a rating — enforced by the caller, not
     *  here, so this stays a pure revert regardless of who calls it. */
    suspend fun revertCompletion(outingId: String) {
        outbox.atomically {
            val existing = cinemaOutingDao.getById(outingId) ?: return@atomically
            if (existing.status != OutingStatus.COMPLETED.name) return@atomically

            existing.completedViewingId?.let { viewingId ->
                viewingDao.deleteById(viewingId)
                outbox.enqueue(
                    entityType = "viewing",
                    entityId = viewingId,
                    operation = "delete",
                    payload = JSONObject().put("id", viewingId),
                )
            }

            // One-shot read (not observeTitle().first()): don't collect a Room Flow inside a transaction.
            val title = titleDao.getById(existing.titleId)
            val previousStatus = existing.previousStatus
            if (title != null && previousStatus != null && title.status == LibraryStatus.WATCHED.name) {
                val nowIso = Instant.now().toString()
                titleDao.updateStatus(existing.titleId, previousStatus, nowIso)
                outbox.enqueue(
                    entityType = "title",
                    entityId = existing.titleId,
                    operation = "update",
                    payload = JSONObject().put("id", existing.titleId).put("status", previousStatus).put("updatedAt", nowIso),
                )
            }

            val reverted = existing.copy(
                status = OutingStatus.MISSED.name,
                completedViewingId = null,
                updatedAt = Instant.now().toString(),
            )
            cinemaOutingDao.upsert(reverted)
            enqueueOutingMutation(reverted, existing)
        }
    }

    /**
     * The local completion choke point (see this class's kdoc). Safe to call redundantly —
     * from app launch, resume, and the exact-alarm receiver alike — because:
     * 1. it only ever reads outings still `SCHEDULED` (a completed one drops out immediately);
     * 2. the viewing insert is deduped by [ViewingDao.getByOutingId], so a re-run after a
     *    process death between the viewing insert and the outing's status flip can't double-log.
     *
     * For each due outing: inserts a `viewings` row (date = the showtime's calendar date in
     * the device's own zone — Android has no per-outing IANA zone to pass through, unlike the
     * web RPC's `p_tz` argument, since this never crosses devices in v1), flips the title to
     * `WATCHED` iff it isn't already, marks the outing `COMPLETED`, and returns a transition
     * per outing so the UI can show a toast / "Fresh from the lobby" card without a re-query.
     */
    suspend fun completeDueOutings(now: Instant = Instant.now()): List<OutingTransition> {
        val due = cinemaOutingDao.getScheduledOutings().filter { Instant.parse(it.endsAt) <= now }
        if (due.isEmpty()) {
            rearmAlarm()
            return emptyList()
        }

        val transitions = mutableListOf<OutingTransition>()
        for (entity in due) {
            val title = titleDao.observeTitle(entity.titleId).first() ?: continue
            val nowIso = now.toString()

            val previousStatus = title.status
            val newStatus = if (previousStatus == LibraryStatus.WATCHED.name) previousStatus else LibraryStatus.WATCHED.name

            // One transaction per outing: the viewing insert, title status flip, outing status
            // flip and their outbox entries commit together (or not at all).
            val viewingId = outbox.atomically {
                val resolvedViewingId = viewingDao.getByOutingId(entity.id)?.id ?: run {
                    val id = UUID.randomUUID().toString()
                    val viewedDate = Instant.parse(entity.showtime).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                    viewingDao.upsert(
                        ViewingEntity(
                            id = id,
                            titleId = entity.titleId,
                            date = viewedDate,
                            rating = null,
                            notes = null,
                            venue = entity.venue,
                            companions = entity.companions,
                            outingId = entity.id,
                        ),
                    )
                    outbox.enqueue(
                        entityType = "viewing",
                        entityId = id,
                        operation = "upsert",
                        payload = JSONObject().apply {
                            put("id", id)
                            put("titleId", entity.titleId)
                            put("date", viewedDate)
                            put("venue", entity.venue ?: JSONObject.NULL)
                            put("companions", JSONArray(entity.companions))
                            put("outingId", entity.id)
                        },
                    )
                    id
                }

                if (previousStatus != newStatus) {
                    titleDao.updateStatus(entity.titleId, newStatus, nowIso)
                    outbox.enqueue(
                        entityType = "title",
                        entityId = entity.titleId,
                        operation = "update",
                        payload = JSONObject().put("id", entity.titleId).put("status", newStatus).put("updatedAt", nowIso),
                    )
                }

                val completed = entity.copy(
                    status = OutingStatus.COMPLETED.name,
                    previousStatus = previousStatus,
                    completedViewingId = resolvedViewingId,
                    updatedAt = nowIso,
                )
                cinemaOutingDao.upsert(completed)
                enqueueOutingMutation(completed, entity)
                resolvedViewingId
            }

            transitions += OutingTransition(
                outingId = entity.id,
                titleId = entity.titleId,
                titleName = title.title,
                posterUrl = title.posterUrl,
                viewingId = viewingId,
                newTitleStatus = LibraryStatus.valueOf(newStatus),
                previousStatus = LibraryStatus.valueOf(previousStatus),
            )
        }
        rearmAlarm()
        return transitions
    }

    private suspend fun rearmAlarm() {
        val scheduled = cinemaOutingDao.getScheduledOutings().map { it.toDomain() }
        alarmScheduler.scheduleNext(CinemaOutingRules.nextTransitionAt(scheduled))
    }

    private fun endsAt(showtime: Instant, previewsMinutes: Int, runtimeMinutes: Int): Instant =
        showtime.plusSeconds((previewsMinutes + runtimeMinutes) * 60L)

    private suspend fun enqueueOutingMutation(entity: CinemaOutingEntity, previous: CinemaOutingEntity? = null) {
        val payload = entity.mutationPayload(previous)
        if (previous != null && payload.length() == 2) return
        outbox.enqueue(
            entityType = "cinema_outing",
            entityId = entity.id,
            operation = if (previous == null) "insert" else "update",
            payload = payload,
        )
    }
}

internal fun CinemaOutingEntity.toDomain(): CinemaOuting = CinemaOuting(
    id = id,
    titleId = titleId,
    showtime = showtime,
    previewsMinutes = previewsMinutes,
    runtimeMinutes = runtimeMinutes,
    endsAt = endsAt,
    venue = venue,
    companions = companions,
    format = CinemaFormat.fromWire(format),
    ticketPrice = ticketPrice,
    seat = seat,
    auditorium = auditorium,
    seatRow = seatRow,
    seats = seats,
    bookingRef = bookingRef,
    ticketImagePath = ticketImagePath,
    ticketBarcodePayload = ticketBarcodePayload,
    ticketBarcodeFormat = ticketBarcodeFormat?.let { runCatching { TicketBarcodeFormat.valueOf(it) }.getOrNull() },
    notes = notes,
    status = runCatching { OutingStatus.valueOf(status) }.getOrDefault(OutingStatus.SCHEDULED),
    previousStatus = previousStatus?.let { runCatching { LibraryStatus.valueOf(it) }.getOrNull() },
    completedViewingId = completedViewingId,
    followUpDismissedAt = followUpDismissedAt,
    createdAt = createdAt,
)
