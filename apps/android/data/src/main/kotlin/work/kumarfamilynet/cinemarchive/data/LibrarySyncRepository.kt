package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
import work.kumarfamilynet.cinemarchive.core.database.TitleCastDao
import work.kumarfamilynet.cinemarchive.core.database.TitleCastEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleCrewDao
import work.kumarfamilynet.cinemarchive.core.database.TitleCrewEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleDao
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.core.database.ViewingDao
import work.kumarfamilynet.cinemarchive.core.database.ViewingEntity

private val Context.librarySyncDataStore by preferencesDataStore(name = "cinemarchive_sync")
private const val EPOCH = "1970-01-01T00:00:00Z"
private const val PAGE_SIZE = 500

/**
 * Bump whenever `sync_library_changes` starts returning an entity type/field this client
 * didn't previously understand. The cursor is a single global watermark across every entity
 * type, so anything of the new kind whose `updated_at` predates the watermark is otherwise
 * unreachable forever — the RPC's `updated_at > p_since` filter excludes it on every future
 * incremental page, with no error or gap indication. Bumping this forces exactly one full
 * resync from epoch after upgrading, at which point the watermark no longer matters.
 *
 * 2: cinema_outing rows, and the viewing arm's companions/outingId fields
 * (supabase/migrations/20260722000000_cinema_outings_sync.sql) — anything synced before this
 * client version existed is stuck without them otherwise. Found via a real device stuck with
 * a cursor past an already-synced-to-Supabase ticket's updated_at.
 *
 * 3: the title arm's releaseDate field (supabase/migrations/20260723000000_sync_release_date.sql)
 * — same "already past the watermark" problem as version 2.
 *
 * 4: `companions` parsing fix — a prior version stringified each `{name, friendUserId?}`
 * element wholesale via `JSONArray.getString()` instead of extracting `name` (raw JSON showing
 * up on the Up Next marquee card). Rows already pulled under the old parser have the bad
 * string baked into Room and, per this constant's own kdoc, are otherwise stuck there forever
 * since their `updated_at` never changes.
 *
 * 5: the `title_cast`/`title_crew` arms and the title arm's imdbRating/originalLanguage
 * (supabase/migrations/20260726000000_sync_cast_crew_and_scores.sql) — every one of those rows
 * predates the cursor on an existing install, which is exactly the case this constant exists
 * for. Without the reset the Ledger's Ensemble/Second Opinions/In Translation widgets stay
 * empty forever on any library that was synced down rather than added on the phone (#177).
 *
 * No bump for [DeferredRows] gaining hold-back arms beyond `title_cast`/`title_crew`: unlike
 * the cases above, a row that hit that ordering hazard never landed with missing data — it hit
 * `SQLiteConstraintException` and crashed before the page's cursor was persisted, so a stuck
 * install already retries the same page from scratch once the fix ships, no forced resync
 * needed.
 */
private const val SYNC_SCHEMA_VERSION = 5

/**
 * Pulls the authenticated user's real library down via `sync_library_changes`
 * (docs/android-sync-contract.md §2,
 * supabase/migrations/20260713000000_android_sync_layer.sql) — the read half of sync;
 * [SupabaseRemoteMutationWriter] (wired in CinemArchiveApplication) is the write half. One
 * RPC serves both bootstrap (`p_since` = epoch) and incremental sync — no separate bootstrap
 * endpoint was ever built server-side, only this RPC.
 *
 * `cinema_outings` got its arm in supabase/migrations/20260722000000_cinema_outings_sync.sql
 * once passkey auth and the real outbox writer landed, closing the read half to match
 * [SupabaseRemoteMutationWriter]'s `cinema_outing` push case; `title_cast`/`title_crew`
 * followed in 20260726000000_sync_cast_crew_and_scores.sql. Until then those two tables were
 * only ever written by this phone's own add-title flow, so a library synced down from Supabase
 * had no credits at all and the Ledger's Ensemble widget could never populate (#177).
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
    private val titleCastDao: TitleCastDao,
    private val titleCrewDao: TitleCrewDao,
) {
    private val dataStore = context.librarySyncDataStore
    private val cursorKey = stringPreferencesKey("last_synced_at")
    private val schemaVersionKey = intPreferencesKey("sync_schema_version")

    /** No-ops when signed out. Safe to call repeatedly (launch, sign-in, resume) — same
     *  "call it whenever, entries/cursor just don't move if there's nothing new" contract
     *  [MutationOutbox.flush] already relies on. Self-dispatches to [Dispatchers.IO] — this
     *  does blocking OkHttp network calls, and a caller invoking it from a Compose
     *  `LaunchedEffect`/`viewModelScope` (Main by default) would otherwise hit a
     *  `NetworkOnMainThreadException`. */
    suspend fun syncNow() = withContext(Dispatchers.IO) {
        val session = authRepository.currentSession() ?: return@withContext
        val prefs = dataStore.data.first()
        // An install that predates SYNC_SCHEMA_VERSION has no stored version at all — treat
        // that as version 1, so every pre-existing install also gets the one-time reset.
        val storedSchemaVersion = prefs[schemaVersionKey] ?: 1
        var cursor = if (storedSchemaVersion < SYNC_SCHEMA_VERSION) EPOCH else (prefs[cursorKey] ?: EPOCH)
        val deferred = DeferredRows()
        while (true) {
            val params = JSONObject().put("p_since", cursor).put("p_limit", PAGE_SIZE).toString()
            val rows = JSONArray(client.rpc("sync_library_changes", params, session.accessToken))
            if (rows.length() == 0) break
            applyPage(rows, deferred)
            cursor = rows.getJSONObject(rows.length() - 1).getString("updated_at")
            dataStore.edit { it[cursorKey] = cursor }
            // A page may overshoot PAGE_SIZE — the RPC widens it to avoid splitting a group of
            // rows sharing one `updated_at` — but it can only ever *under*shoot when there was
            // nothing left to send, so a short page is still a reliable "that was the last one".
            if (rows.length() < PAGE_SIZE) break
        }
        deferred.flush()
        // Only recorded once the resync above actually ran to completion — if the app is
        // killed mid-resync, the next syncNow() sees the still-stale stored version and (safely,
        // idempotently) does the full resync again rather than settling for a partial one.
        if (storedSchemaVersion < SYNC_SCHEMA_VERSION) dataStore.edit { it[schemaVersionKey] = SYNC_SCHEMA_VERSION }
    }

    /**
     * Rows whose parent hasn't been applied yet, held back until the whole run is in.
     *
     * Rows are ordered by `updated_at` across the entire run, not grouped by parent, so a
     * title edited after one of its children was written sorts *behind* that child — on a
     * from-epoch resync that means (say) a `season` row can land pages before the `titles`
     * row it points at, and [SeasonEntity]'s foreign key would reject the insert outright —
     * originally only guarded for `title_cast`/`title_crew` (see f73e750), widened here after
     * the same hazard crashed on a `season` row on a real device instead. [flush] re-checks
     * each one once every page has been applied and drops the ones whose parent never arrived
     * (deleted server-side, so a tombstone would have removed them anyway).
     *
     * Two levels deep: `episode` depends on `season` (resolved by titleId+seasonNumber, not a
     * direct id), and `episode_watch_event`/`episode_rating`/`episode_review` depend on
     * `episode`. [flush] applies season/viewing/cinema_outing/cast/crew first — everything
     * that depends only on `titles`, which is fully applied by the time [flush] runs — then
     * re-resolves episodes against those newly-applied seasons, then re-checks the
     * episode-children against those newly-applied episodes.
     */
    private inner class DeferredRows {
        private val seasons = mutableListOf<SeasonEntity>()
        private val episodes = mutableListOf<JSONObject>()
        private val viewings = mutableListOf<ViewingEntity>()
        private val cinemaOutings = mutableListOf<CinemaOutingEntity>()
        private val cast = mutableListOf<TitleCastEntity>()
        private val crew = mutableListOf<TitleCrewEntity>()
        private val watchEvents = mutableListOf<EpisodeWatchEventEntity>()
        private val ratings = mutableListOf<EpisodeRatingEntity>()
        private val reviews = mutableListOf<EpisodeReviewEntity>()

        suspend fun addSeason(row: SeasonEntity) {
            if (titleDao.getById(row.titleId) != null) seasonDao.upsertAll(listOf(row)) else seasons += row
        }

        suspend fun addEpisode(payload: JSONObject) {
            val seasonId = seasonDao.findSeasonId(payload.getString("titleId"), payload.getInt("seasonNumber"))
            if (seasonId != null) episodeDao.upsertAll(listOf(payload.toEpisodeEntity(seasonId))) else episodes += payload
        }

        suspend fun addViewing(row: ViewingEntity) {
            if (titleDao.getById(row.titleId) != null) viewingDao.upsertAll(listOf(row)) else viewings += row
        }

        suspend fun addCinemaOuting(row: CinemaOutingEntity) {
            if (titleDao.getById(row.titleId) != null) cinemaOutingDao.upsert(row) else cinemaOutings += row
        }

        suspend fun addCast(row: TitleCastEntity) {
            if (titleDao.getById(row.titleId) != null) titleCastDao.upsertAll(listOf(row)) else cast += row
        }

        suspend fun addCrew(row: TitleCrewEntity) {
            if (titleDao.getById(row.titleId) != null) titleCrewDao.upsertAll(listOf(row)) else crew += row
        }

        suspend fun addWatchEvent(row: EpisodeWatchEventEntity) {
            if (episodeDao.getById(row.episodeId) != null) watchEventDao.upsertAll(listOf(row)) else watchEvents += row
        }

        suspend fun addRating(row: EpisodeRatingEntity) {
            if (episodeDao.getById(row.episodeId) != null) ratingDao.upsertAll(listOf(row)) else ratings += row
        }

        suspend fun addReview(row: EpisodeReviewEntity) {
            if (episodeDao.getById(row.episodeId) != null) reviewDao.upsertAll(listOf(row)) else reviews += row
        }

        /** Drops a held-back row that a later page went on to tombstone, so [flush] can't
         *  resurrect it after the delete already ran. */
        fun forget(entityType: String, entityId: String) {
            when (entityType) {
                "season" -> seasons.removeAll { it.id == entityId }
                "episode" -> episodes.removeAll { it.getString("id") == entityId }
                "viewing" -> viewings.removeAll { it.id == entityId }
                "cinema_outing" -> cinemaOutings.removeAll { it.id == entityId }
                "title_cast" -> cast.removeAll { it.id == entityId }
                "title_crew" -> crew.removeAll { it.id == entityId }
                "episode_watch_event" -> watchEvents.removeAll { it.id == entityId }
                "episode_rating" -> ratings.removeAll { it.id == entityId }
                "episode_review" -> reviews.removeAll { it.id == entityId }
            }
        }

        suspend fun flush() {
            seasonDao.upsertAll(seasons.filter { titleDao.getById(it.titleId) != null })
            viewingDao.upsertAll(viewings.filter { titleDao.getById(it.titleId) != null })
            cinemaOutings.filter { titleDao.getById(it.titleId) != null }.forEach { cinemaOutingDao.upsert(it) }
            titleCastDao.upsertAll(cast.filter { titleDao.getById(it.titleId) != null })
            titleCrewDao.upsertAll(crew.filter { titleDao.getById(it.titleId) != null })

            val resolvedEpisodes = episodes.mapNotNull { payload ->
                seasonDao.findSeasonId(payload.getString("titleId"), payload.getInt("seasonNumber"))
                    ?.let { payload.toEpisodeEntity(it) }
            }
            episodeDao.upsertAll(resolvedEpisodes)

            watchEventDao.upsertAll(watchEvents.filter { episodeDao.getById(it.episodeId) != null })
            ratingDao.upsertAll(ratings.filter { episodeDao.getById(it.episodeId) != null })
            reviewDao.upsertAll(reviews.filter { episodeDao.getById(it.episodeId) != null })
        }
    }

    /** Applies one page grouped by entity_type in a fixed order — title before everything
     *  else (every other type either FKs to it directly or, for episode, indirectly through
     *  season) — and tombstones strictly last so a same-page delete always wins over an
     *  upsert of the same id, regardless of the RPC's own (updated_at, entity_id) row order.
     *  Anything whose parent isn't in the local DB yet goes through [deferred] instead of a
     *  direct DAO call — see [DeferredRows]'s kdoc for why that's necessary even though title
     *  is always applied first *within* a page. */
    private suspend fun applyPage(rows: JSONArray, deferred: DeferredRows) {
        val byType = (0 until rows.length()).map { rows.getJSONObject(it) }.groupBy { it.getString("entity_type") }

        byType["title"]?.forEach { row ->
            val payload = row.payload()
            // Carry forward whatever the RPC doesn't send — see toTitleEntity's kdoc.
            titleDao.upsertAll(listOf(payload.toTitleEntity(titleDao.getById(payload.getString("id")))))
        }
        byType["season"]?.forEach { deferred.addSeason(it.payload().toSeasonEntity()) }
        byType["episode"]?.forEach { deferred.addEpisode(it.payload()) }
        byType["title_cast"]?.forEach { deferred.addCast(it.payload().toTitleCastEntity()) }
        byType["title_crew"]?.forEach { deferred.addCrew(it.payload().toTitleCrewEntity()) }
        byType["viewing"]?.forEach { deferred.addViewing(it.payload().toViewingEntity()) }
        byType["episode_watch_event"]?.forEach { deferred.addWatchEvent(it.payload().toWatchEventEntity()) }
        byType["episode_rating"]?.forEach { deferred.addRating(it.payload().toRatingEntity()) }
        byType["episode_review"]?.forEach { deferred.addReview(it.payload().toReviewEntity()) }
        byType["cinema_outing"]?.forEach { deferred.addCinemaOuting(it.payload().toCinemaOutingEntity()) }

        byType["tombstone"]?.forEach { row ->
            val entityId = row.getString("entity_id")
            val entityType = row.getJSONObject("payload").getString("entityType")
            when (entityType) {
                "title" -> titleDao.deleteById(entityId)
                "season" -> seasonDao.deleteById(entityId)
                "episode" -> episodeDao.deleteById(entityId)
                "viewing" -> viewingDao.deleteById(entityId)
                "episode_watch_event" -> watchEventDao.deleteById(entityId)
                "episode_rating" -> ratingDao.deleteById(entityId)
                "episode_review" -> reviewDao.deleteById(entityId)
                "cinema_outing" -> cinemaOutingDao.deleteById(entityId)
                "title_cast" -> titleCastDao.deleteById(entityId)
                "title_crew" -> titleCrewDao.deleteById(entityId)
            }
            deferred.forget(entityType, entityId)
        }
    }

    private fun JSONObject.payload(): JSONObject = getJSONObject("payload")
    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null

    // Remote `companions` is `[{name, friendUserId?}]` (schema.sql) to match the web app's
    // Companion[] type; Android keeps display names only and drops friendUserId (no friend
    // graph locally — see CinemaOutingEntity's kdoc). A plain JSONArray.getString() here would
    // stringify each companion object wholesale instead of extracting its name, which is what
    // showed raw JSON blobs on the Up Next marquee card. Falls back to the element itself for
    // any legacy row that already got written as a bare string.
    /** A plain jsonb string array (`cinema_outings.seats`). Blank entries are dropped so a
     *  stray "" from the server can't render as an empty seat in the in-theater view. */
    private fun JSONArray?.toStringList(): List<String> =
        this?.let { arr -> (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf(String::isNotBlank) } }
            ?: emptyList()

    private fun JSONArray?.toCompanionNames(): List<String> =
        this?.let { arr -> (0 until arr.length()).map { i -> (arr.opt(i) as? JSONObject)?.optString("name") ?: arr.getString(i) } }
            ?: emptyList()

    /**
     * [existing] is the local row this one is replacing, when there is one.
     *
     * `imdb_rating`/`original_language` joined the title arm in
     * supabase/migrations/20260726000000_sync_cast_crew_and_scores.sql, so the payload now
     * carries every column this mirror holds. The carry-forward stays: mapping a missing key
     * straight to null would silently erase a locally-added title's critic score and language
     * — exactly what the Ledger's Second Opinions and In Translation widgets read — against
     * any Supabase project still on an older migration. The payload wins whenever it has the
     * key, so a genuine server-side clear still propagates once that project catches up.
     */
    private fun JSONObject.toTitleEntity(existing: TitleEntity? = null) = TitleEntity(
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
        releaseDate = optStringOrNull("releaseDate") ?: existing?.releaseDate,
        imdbRating = optDoubleOrNull("imdbRating") ?: existing?.imdbRating,
        originalLanguage = optStringOrNull("originalLanguage") ?: existing?.originalLanguage,
    )

    // `castOrder`/`department` are the only fields either Ledger credits widget reads
    // (The Ensemble filters on castOrder < 5, The Auteurs no longer touches crew at all —
    // see LedgerRepository.buildBoard). The RPC deliberately omits profile_url/episode_count,
    // which this mirror has no column for.
    private fun JSONObject.toTitleCastEntity() = TitleCastEntity(
        id = getString("id"),
        titleId = getString("titleId"),
        tmdbPersonId = getInt("tmdbPersonId"),
        name = getString("name"),
        characterName = optStringOrNull("characterName"),
        castOrder = optIntOrNull("castOrder") ?: 0,
    )

    private fun JSONObject.toTitleCrewEntity() = TitleCrewEntity(
        id = getString("id"),
        titleId = getString("titleId"),
        tmdbPersonId = getInt("tmdbPersonId"),
        name = getString("name"),
        job = getString("job"),
        department = optStringOrNull("department"),
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
        synopsis = optStringOrNull("synopsis"),
        stillUrl = optStringOrNull("stillUrl"),
    )

    private fun JSONObject.toViewingEntity() = ViewingEntity(
        id = getString("id"),
        titleId = getString("titleId"),
        date = optStringOrNull("date"),
        rating = optDoubleOrNull("rating"),
        notes = optStringOrNull("notes"),
        venue = optStringOrNull("venue"),
        companions = optJSONArray("companions").toCompanionNames(),
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
        companions = optJSONArray("companions").toCompanionNames(),
        format = optStringOrNull("format"),
        ticketPrice = optDoubleOrNull("ticketPrice"),
        seat = optStringOrNull("seat"),
        auditorium = optStringOrNull("auditorium"),
        seatRow = optStringOrNull("seatRow"),
        seats = optJSONArray("seats").toStringList(),
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
