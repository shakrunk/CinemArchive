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
import work.kumarfamilynet.cinemarchive.core.database.ViewingDao
import work.kumarfamilynet.cinemarchive.core.database.ViewingEntity
import work.kumarfamilynet.cinemarchive.core.model.CinemaFormat
import work.kumarfamilynet.cinemarchive.core.model.CinemaOuting
import work.kumarfamilynet.cinemarchive.core.model.CinemaOutingRules
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.OutingStatus
import work.kumarfamilynet.cinemarchive.core.model.OutingTransition

/**
 * Owns [CinemaOuting] CRUD and the local completion engine — the Android analogue of the web
 * app's `complete_due_outings()` Postgres RPC (docs/superpowers/plans/2026-07-11-cinema-outings.md
 * §6.4), except it runs as a plain Kotlin function over Room rather than a server call: there
 * is no live Supabase session to call an RPC through yet (see
 * docs/superpowers/plans/2026-07-21-android-cinema-outings.md §2). Writes land in Room
 * immediately and are queued in [outbox] for a remote push once network sync is wired up,
 * same pattern as [LibraryRepository].
 */
class OutingsRepository(
    private val cinemaOutingDao: CinemaOutingDao,
    private val viewingDao: ViewingDao,
    private val titleDao: TitleDao,
    private val outbox: MutationOutbox,
    private val alarmScheduler: OutingAlarmScheduler = NoOpOutingAlarmScheduler,
) {
    fun observeOutingsForTitle(titleId: String): Flow<List<CinemaOuting>> =
        cinemaOutingDao.observeOutingsForTitle(titleId).map { rows -> rows.map { it.toDomain() } }

    fun observeAllOutings(): Flow<List<CinemaOuting>> =
        cinemaOutingDao.observeAllOutings().map { rows -> rows.map { it.toDomain() } }

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
        seat: String?,
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
            format = format?.name,
            ticketPrice = ticketPrice,
            seat = seat,
            bookingRef = bookingRef,
            notes = notes,
            status = OutingStatus.SCHEDULED.name,
            createdAt = nowIso,
            updatedAt = nowIso,
        )
        cinemaOutingDao.upsert(entity)
        enqueueOutingMutation(entity)
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
        seat: String?,
        bookingRef: String?,
        notes: String?,
    ) {
        val existing = cinemaOutingDao.getById(outingId) ?: return
        val updated = existing.copy(
            showtime = showtime.toString(),
            previewsMinutes = previewsMinutes,
            runtimeMinutes = runtimeMinutes,
            endsAt = endsAt(showtime, previewsMinutes, runtimeMinutes).toString(),
            venue = venue,
            companions = companions,
            format = format?.name,
            ticketPrice = ticketPrice,
            seat = seat,
            bookingRef = bookingRef,
            notes = notes,
            updatedAt = Instant.now().toString(),
        )
        cinemaOutingDao.upsert(updated)
        enqueueOutingMutation(updated)
        rearmAlarm()
    }

    /** Cancels a still-scheduled outing (before the show ends) — kept as a row for history,
     *  never transitions to completed. No-op for an outing that's already completed/missed/
     *  cancelled. */
    suspend fun cancelOuting(outingId: String) {
        val existing = cinemaOutingDao.getById(outingId) ?: return
        if (existing.status != OutingStatus.SCHEDULED.name) return
        val updated = existing.copy(status = OutingStatus.CANCELLED.name, updatedAt = Instant.now().toString())
        cinemaOutingDao.upsert(updated)
        enqueueOutingMutation(updated)
        rearmAlarm()
    }

    /** Stamps the 14-day follow-up window closed without rating — the post-show card/inbox
     *  item's ✕ dismissal (web plan §4.4). */
    suspend fun dismissFollowUp(outingId: String) {
        val existing = cinemaOutingDao.getById(outingId) ?: return
        val updated = existing.copy(followUpDismissedAt = Instant.now().toString(), updatedAt = Instant.now().toString())
        cinemaOutingDao.upsert(updated)
        enqueueOutingMutation(updated)
    }

    /** "Didn't make it" — reverts a completion: deletes the auto-logged viewing, restores the
     *  title's [CinemaOutingEntity.previousStatus] iff it's still `WATCHED` (a manual status
     *  change in between is left alone, per the web plan's rule 6), and moves the outing to
     *  `MISSED`. Hidden by the UI once the viewing has a rating — enforced by the caller, not
     *  here, so this stays a pure revert regardless of who calls it. */
    suspend fun revertCompletion(outingId: String) {
        val existing = cinemaOutingDao.getById(outingId) ?: return
        if (existing.status != OutingStatus.COMPLETED.name) return

        existing.completedViewingId?.let { viewingId ->
            viewingDao.deleteById(viewingId)
            // Not yet meaningfully flushable: SupabaseRemoteMutationWriter's "viewing" case
            // always upserts regardless of `operation` (docs/android-sync-contract.md's delete
            // contract isn't implemented for viewings yet). Harmless today since the writer
            // is Unconfigured; worth fixing before this write path goes live.
            outbox.enqueue(
                entityType = "viewing",
                entityId = viewingId,
                operation = "delete",
                payload = JSONObject().put("id", viewingId),
            )
        }

        val title = titleDao.observeTitle(existing.titleId).first()
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
        enqueueOutingMutation(reverted)
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

            val viewingId = viewingDao.getByOutingId(entity.id)?.id ?: run {
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

            val previousStatus = title.status
            val newStatus = if (previousStatus == LibraryStatus.WATCHED.name) previousStatus else LibraryStatus.WATCHED.name
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
                completedViewingId = viewingId,
                updatedAt = nowIso,
            )
            cinemaOutingDao.upsert(completed)
            enqueueOutingMutation(completed)

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

    private suspend fun enqueueOutingMutation(entity: CinemaOutingEntity) {
        outbox.enqueue(
            entityType = "cinema_outing",
            entityId = entity.id,
            operation = "upsert",
            payload = JSONObject().apply {
                put("id", entity.id)
                put("titleId", entity.titleId)
                put("showtime", entity.showtime)
                put("previewsMinutes", entity.previewsMinutes)
                put("runtimeMinutes", entity.runtimeMinutes)
                put("endsAt", entity.endsAt)
                put("venue", entity.venue ?: JSONObject.NULL)
                put("companions", JSONArray(entity.companions))
                put("format", entity.format ?: JSONObject.NULL)
                put("ticketPrice", entity.ticketPrice ?: JSONObject.NULL)
                put("seat", entity.seat ?: JSONObject.NULL)
                put("bookingRef", entity.bookingRef ?: JSONObject.NULL)
                put("notes", entity.notes ?: JSONObject.NULL)
                put("status", entity.status)
                put("previousStatus", entity.previousStatus ?: JSONObject.NULL)
                put("completedViewingId", entity.completedViewingId ?: JSONObject.NULL)
                put("followUpDismissedAt", entity.followUpDismissedAt ?: JSONObject.NULL)
                put("createdAt", entity.createdAt)
                put("updatedAt", entity.updatedAt)
            },
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
    format = format?.let { runCatching { CinemaFormat.valueOf(it) }.getOrNull() },
    ticketPrice = ticketPrice,
    seat = seat,
    bookingRef = bookingRef,
    notes = notes,
    status = runCatching { OutingStatus.valueOf(status) }.getOrDefault(OutingStatus.SCHEDULED),
    previousStatus = previousStatus?.let { runCatching { LibraryStatus.valueOf(it) }.getOrNull() },
    completedViewingId = completedViewingId,
    followUpDismissedAt = followUpDismissedAt,
    createdAt = createdAt,
)
