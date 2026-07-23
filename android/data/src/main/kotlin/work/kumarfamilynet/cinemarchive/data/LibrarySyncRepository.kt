package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingDao
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeRatingDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeRatingEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeReviewDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeReviewEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeWatchEventDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeWatchEventEntity
import work.kumarfamilynet.cinemarchive.core.database.SeasonDao
import work.kumarfamilynet.cinemarchive.core.database.SeasonEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleDao
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.core.database.ViewingDao
import work.kumarfamilynet.cinemarchive.core.database.ViewingEntity

private val Context.librarySyncDataStore by preferencesDataStore(name = "cinemarchive_sync")
private const val EPOCH = "1970-01-01T00:00:00Z"
private const val PAGE_SIZE = 500

/**
 * Pulls the authenticated user's real library down via `sync_library_changes`
 * (docs/android-sync-contract.md §2,
 * supabase/migrations/20260713000000_android_sync_layer.sql) — the read half of sync;
 * [SupabaseRemoteMutationWriter] (wired in CinemArchiveApplication) is the write half. One
 * RPC serves both bootstrap (`p_since` = epoch) and incremental sync — no separate bootstrap
 * endpoint was ever built server-side, only this RPC.
 *
 * Cast/crew are still deliberately not handled here: the migration gave them `updated_at`/
 * tombstone triggers but the RPC itself has no `union all` arm for either — a server-side
 * gap, not an omission here. `cinema_outings` got its arm in
 * supabase/migrations/20260722000000_cinema_outings_sync.sql once passkey auth and the real
 * outbox writer landed, closing the read half to match [SupabaseRemoteMutationWriter]'s
 * `cinema_outing` push case.
 */
class LibrarySyncRepository(
    context: Context,
    private val client: SupabaseRestClient,
    private val authRepository: AuthRepository,
    private val titleDao: TitleDao,
    private val seasonDao: SeasonDao,
    private val episodeDao: EpisodeDao,
    private val watchEventDao: EpisodeWatchEventDao,
    private val ratingDao: EpisodeRatingDao,
    private val reviewDao: EpisodeReviewDao,
    private val viewingDao: ViewingDao,
    private val cinemaOutingDao: CinemaOutingDao,
) {
    private val dataStore = context.librarySyncDataStore
    private val cursorKey = stringPreferencesKey("last_synced_at")

    /** No-ops when signed out. Safe to call repeatedly (launch, sign-in, resume) — same
     *  "call it whenever, entries/cursor just don't move if there's nothing new" contract
     *  [MutationOutbox.flush] already relies on. Self-dispatches to [Dispatchers.IO] — this
     *  does blocking OkHttp network calls, and a caller invoking it from a Compose
     *  `LaunchedEffect`/`viewModelScope` (Main by default) would otherwise hit a
     *  `NetworkOnMainThreadException`. */
    suspend fun syncNow() = withContext(Dispatchers.IO) {
        val session = authRepository.currentSession() ?: return@withContext
        var cursor = dataStore.data.first()[cursorKey] ?: EPOCH
        while (true) {
            val params = JSONObject().put("p_since", cursor).put("p_limit", PAGE_SIZE).toString()
            val rows = JSONArray(client.rpc("sync_library_changes", params, session.accessToken))
            if (rows.length() == 0) break
            applyPage(rows)
            cursor = rows.getJSONObject(rows.length() - 1).getString("updated_at")
            dataStore.edit { it[cursorKey] = cursor }
            if (rows.length() < PAGE_SIZE) break
        }
    }

    /** Applies one page grouped by entity_type in a fixed order — title/season before
     *  episode (episodes resolve their seasonId against already-applied seasons), and
     *  tombstones strictly last so a same-page delete always wins over an upsert of the
     *  same id, regardless of the RPC's own (updated_at, entity_id) row order. */
    private suspend fun applyPage(rows: JSONArray) {
        val byType = (0 until rows.length()).map { rows.getJSONObject(it) }.groupBy { it.getString("entity_type") }

        byType["title"]?.forEach { titleDao.upsertAll(listOf(it.payload().toTitleEntity())) }
        byType["season"]?.forEach { seasonDao.upsertAll(listOf(it.payload().toSeasonEntity())) }
        byType["episode"]?.forEach { row ->
            val payload = row.payload()
            val seasonId = seasonDao.findSeasonId(payload.getString("titleId"), payload.getInt("seasonNumber"))
            if (seasonId != null) episodeDao.upsertAll(listOf(payload.toEpisodeEntity(seasonId)))
        }
        byType["viewing"]?.forEach { viewingDao.upsertAll(listOf(it.payload().toViewingEntity())) }
        byType["episode_watch_event"]?.forEach { watchEventDao.upsertAll(listOf(it.payload().toWatchEventEntity())) }
        byType["episode_rating"]?.forEach { ratingDao.upsertAll(listOf(it.payload().toRatingEntity())) }
        byType["episode_review"]?.forEach { reviewDao.upsertAll(listOf(it.payload().toReviewEntity())) }
        byType["cinema_outing"]?.forEach { cinemaOutingDao.upsert(it.payload().toCinemaOutingEntity()) }

        byType["tombstone"]?.forEach { row ->
            val entityId = row.getString("entity_id")
            when (row.getJSONObject("payload").getString("entityType")) {
                "title" -> titleDao.deleteById(entityId)
                "season" -> seasonDao.deleteById(entityId)
                "episode" -> episodeDao.deleteById(entityId)
                "viewing" -> viewingDao.deleteById(entityId)
                "episode_watch_event" -> watchEventDao.deleteById(entityId)
                "episode_rating" -> ratingDao.deleteById(entityId)
                "episode_review" -> reviewDao.deleteById(entityId)
                "cinema_outing" -> cinemaOutingDao.deleteById(entityId)
            }
        }
    }

    private fun JSONObject.payload(): JSONObject = getJSONObject("payload")
    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null

    private fun JSONObject.toTitleEntity() = TitleEntity(
        id = getString("id"),
        tmdbId = getInt("tmdbId"),
        // Postgres's media_type/watch_status enums are lowercase ('movie', 'watched', ...);
        // Room stores MediaType.name/LibraryStatus.name (uppercase) — every other boundary
        // crossing this same enum (e.g. SupabaseRemoteMutationWriter's cinema_outing upsert)
        // already converts case, this one just hadn't been exercised against real data yet.
        type = getString("type").uppercase(),
        title = getString("title"),
        year = optIntOrNull("year"),
        director = optStringOrNull("director"),
        genres = optJSONArray("genres")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
        posterUrl = optStringOrNull("posterUrl"),
        backdropUrl = optStringOrNull("backdropUrl"),
        synopsis = optStringOrNull("synopsis"),
        runtime = optIntOrNull("runtime"),
        network = optStringOrNull("network"),
        status = getString("status").uppercase(),
        rating = optDoubleOrNull("rating"),
        notes = optStringOrNull("notes"),
        addedAt = getString("addedAt"),
        updatedAt = getString("updatedAt"),
    )

    private fun JSONObject.toSeasonEntity() = SeasonEntity(
        id = getString("id"),
        titleId = getString("titleId"),
        seasonNumber = getInt("seasonNumber"),
        episodeCount = getInt("episodeCount"),
        episodesWatched = getInt("episodesWatched"),
        airYear = optIntOrNull("airYear"),
    )

    private fun JSONObject.toEpisodeEntity(seasonId: String) = EpisodeEntity(
        id = getString("id"),
        titleId = getString("titleId"),
        seasonId = seasonId,
        episodeNumber = getInt("episodeNumber"),
        episodeName = optStringOrNull("episodeName"),
        airDate = optStringOrNull("airDate"),
        runtime = optIntOrNull("runtime"),
    )

    private fun JSONObject.toViewingEntity() = ViewingEntity(
        id = getString("id"),
        titleId = getString("titleId"),
        date = optStringOrNull("date"),
        rating = optDoubleOrNull("rating"),
        notes = optStringOrNull("notes"),
        venue = optStringOrNull("venue"),
        companions = optJSONArray("companions")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
        outingId = optStringOrNull("outingId"),
    )

    // Postgres's status/previous_status enums are lowercase ('scheduled', 'watched', ...);
    // Room stores OutingStatus.name/LibraryStatus.name (uppercase) — same conversion every
    // other title/cinema_outing boundary crossing already applies (see toTitleEntity's kdoc
    // and SupabaseRemoteMutationWriter's upsertOuting).
    private fun JSONObject.toCinemaOutingEntity() = CinemaOutingEntity(
        id = getString("id"),
        titleId = getString("titleId"),
        showtime = getString("showtime"),
        previewsMinutes = getInt("previewsMinutes"),
        runtimeMinutes = getInt("runtimeMinutes"),
        endsAt = getString("endsAt"),
        venue = optStringOrNull("venue"),
        companions = optJSONArray("companions")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
        format = optStringOrNull("format"),
        ticketPrice = optDoubleOrNull("ticketPrice"),
        seat = optStringOrNull("seat"),
        bookingRef = optStringOrNull("bookingRef"),
        notes = optStringOrNull("notes"),
        status = getString("status").uppercase(),
        previousStatus = optStringOrNull("previousStatus")?.uppercase(),
        completedViewingId = optStringOrNull("completedViewingId"),
        followUpDismissedAt = optStringOrNull("followUpDismissedAt"),
        createdAt = getString("createdAt"),
        updatedAt = getString("updatedAt"),
    )

    private fun JSONObject.toWatchEventEntity() = EpisodeWatchEventEntity(
        id = getString("id"),
        episodeId = getString("episodeId"),
        watchedAt = optStringOrNull("watchedAt"),
    )

    private fun JSONObject.toRatingEntity() = EpisodeRatingEntity(
        id = getString("id"),
        episodeId = getString("episodeId"),
        rating = getDouble("rating"),
        ratedAt = getString("ratedAt"),
    )

    private fun JSONObject.toReviewEntity() = EpisodeReviewEntity(
        id = getString("id"),
        episodeId = getString("episodeId"),
        reviewText = getString("reviewText"),
        reviewedAt = getString("reviewedAt"),
    )
}
