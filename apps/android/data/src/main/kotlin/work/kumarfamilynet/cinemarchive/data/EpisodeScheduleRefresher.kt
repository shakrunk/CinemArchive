package work.kumarfamilynet.cinemarchive.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.model.MediaSeason

/** TMDB reads for the background schedule check (see [DiscoverRepository.fetchTvSchedule]). */
interface EpisodeScheduleSource {
    suspend fun fetchTvSchedule(tmdbId: Int): TvSchedule?
    /** Empty episodes when the season could not be fetched. */
    suspend fun fetchSeason(tmdbId: Int, seasonNumber: Int): MediaSeason
}

/** Per-series check stamps (epoch millis by title id) for one owner. */
interface ScheduleCheckStore {
    suspend fun read(): Map<String, Long>
    suspend fun write(checkedAt: Map<String, Long>)
}

/** [ScheduleCheckStore] in the owner's own DataStore file. A corrupt value only means a re-check. */
class DataStoreScheduleCheckStore(private val dataStore: DataStore<Preferences>) : ScheduleCheckStore {
    private val key = stringPreferencesKey("episode_schedule_checked_at")
    override suspend fun read(): Map<String, Long> = runCatching {
        val json = JSONObject(dataStore.data.first()[key] ?: return emptyMap())
        json.keys().asSequence().mapNotNull { id -> (json.opt(id) as? Number)?.let { id to it.toLong() } }.toMap()
    }.getOrDefault(emptyMap())
    override suspend fun write(checkedAt: Map<String, Long>) {
        dataStore.edit { it[key] = JSONObject(checkedAt).toString() }
    }
}

/**
 * Background refresh of upcoming episode air dates for tracked series — the web app's
 * apps/web/src/lib/episodeSchedule.ts. Episode rows are otherwise written only when a series is
 * added or refreshed by hand, so a returning show's newly announced season never reached Up Next.
 *
 * Each non-dropped series is checked at most once a day: one TMDB details call (sharing the
 * media-proxy's 24h server cache) decides which seasons can have changed, and only those are
 * fetched. Changes are queued as one narrow catalog command per series: new season/episode rows
 * under their catalog IDs, a guarded count for a season that grew, and air date/name updates.
 * Watch history, progress and stored credits are never rewritten.
 *
 * Call [refresh] only right after a sync completed (so cached revisions are fresh); it does
 * nothing while any change is still queued.
 */
class EpisodeScheduleRefresher(
    private val database: LibraryDatabase,
    private val outbox: MutationOutbox,
    private val source: EpisodeScheduleSource,
    private val store: ScheduleCheckStore,
    private val ownerId: String,
    private val isCurrentOwner: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val mutex = Mutex()

    /** One pass. Returns how many series got schedule changes queued. */
    suspend fun refresh(): Int = mutex.withLock {
        if (!isCurrentOwner() || outbox.pendingEntries().isNotEmpty()) return@withLock 0
        val now = clock()
        val today = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate().toString()
        val titles = database.titleDao().observeAllTitles().first()
        // Forget titles no longer in the library so the stamp map can't grow forever.
        val live = titles.map { it.id }.toSet()
        val checkedAt = store.read().filterKeys { it in live }.toMutableMap()
        val due = seriesDueForScheduleCheck(titles.map { ScheduleCandidate(it.id, it.tmdbId, it.type, it.status) }, checkedAt, now)
        if (due.isEmpty()) return@withLock 0
        // Stamp before fetching, so a failing series is not retried on every open.
        due.forEach { checkedAt[it.id] = now }
        store.write(checkedAt)

        var updated = 0
        for (title in due) {
            if (!isCurrentOwner()) break
            try {
                val schedule = source.fetchTvSchedule(title.tmdbId) ?: continue
                val numbers = seasonsToCheck(storedCatalog(database, title.id), schedule, today)
                if (numbers.isEmpty()) continue
                val years = schedule.seasons.associate { it.seasonNumber to it.airYear }
                val fetched = numbers.map { number -> source.fetchSeason(title.tmdbId, number).copy(seasonNumber = number, airYear = years[number]) }
                if (!isCurrentOwner()) break
                val queued = outbox.atomically {
                    check(isCurrentOwner()) { "This sign-in has ended" }
                    // Built against the latest projection, and never over a change queued meanwhile.
                    val latest = database.titleDao().getById(title.id) ?: return@atomically false
                    if (latest.status == "DROPPED" || latest.tmdbId != title.tmdbId || latest.type != title.type) return@atomically false
                    if (outbox.pendingEntries().any { it.entityId == title.id }) return@atomically false
                    val stored = storedCatalog(database, title.id)
                    enqueueEpisodeCatalog(outbox, title.id, ownerId, catalogAdditions(title.id, stored, fetched, fillCoarse = false),
                        episodeScheduleChanges(stored, fetched), castForStored = false)
                }
                if (queued) updated++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // One unavailable series must not stop the others; it is retried tomorrow.
                if (!isCurrentOwner()) break
            }
        }
        updated
    }
}
