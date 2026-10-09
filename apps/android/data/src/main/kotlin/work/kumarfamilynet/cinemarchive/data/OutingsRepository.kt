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
    val moviegoingPreferences: MoviegoingPreferencesRepository? = null,
    private val lifecycle: OutingLifecycleRepository? = null,
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
        val repository = checkNotNull(moviegoingPreferences) { "Shared venue notes are unavailable for this session." }
        repository.saveVenue(repository.captureVenue(venue), notes)
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
            companionsJson = companionObjects(null, companions).toString(),
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
                companionsJson = retainCompanionsJson(existing.companionsJson, existing.companions, companions),
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

    /** All new lifecycle writes use the same canonical, receipt-backed command on both clients. */
    suspend fun completeDueOutings(now: Instant = Instant.now()): List<OutingTransition> {
        val transitions = checkNotNull(lifecycle) { "Outing completion requires the current account runtime." }.completeDue(now)
        refreshAlarm()
        return transitions
    }

    suspend fun revertPostShow(opening: work.kumarfamilynet.cinemarchive.core.model.PostShowOpening) {
        checkNotNull(lifecycle) { "Outing reversal requires the current account runtime." }.revert(opening)
        refreshAlarm()
    }

    /** Restore may re-arm notifications without performing any completion side effects. */
    suspend fun refreshAlarm() {
        lifecycle?.active()
        rearmAlarm()
    }

    private suspend fun rearmAlarm() {
        val scheduled = cinemaOutingDao.getScheduledOutings().map { it.toDomain() }
        alarmScheduler.scheduleNext(CinemaOutingRules.nextTransitionAt(scheduled))
    }

    private fun endsAt(showtime: Instant, previewsMinutes: Int, runtimeMinutes: Int): Instant =
        showtime.plusSeconds((previewsMinutes + runtimeMinutes) * 60L)

    private suspend fun enqueueOutingMutation(entity: CinemaOutingEntity, previous: CinemaOutingEntity? = null) {
        outbox.enqueueOutingCommand(entity, previous)
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
    companions = savedCompanionNames(companionsJson, companions),
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
