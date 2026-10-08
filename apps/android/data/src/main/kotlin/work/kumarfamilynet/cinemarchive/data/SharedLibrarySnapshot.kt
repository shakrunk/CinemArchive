package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.*

/** A value snapshot with no account database, session, outbox, or live collectors attached. */
data class SharedLibrarySnapshot(
    val library: SharedLibrary,
    val layout: List<LedgerWidgetConfig>,
    val stats: LedgerStats,
    val boards: Map<String, LedgerBoard>,
)

/** Project only the scoped RPC graph into a temporary, memory-only database so the read-only
 * board uses precisely the same calculations as the owner's board. Every first() collector
 * completes before close(), and finally also closes on cancellation/mapping failure.
 * The complete graph remains on SharedLibraryTitle for detail fields absent from Room.
 */
suspend fun buildSharedLibrarySnapshot(context: Context, library: SharedLibrary): SharedLibrarySnapshot =
    withContext(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(context.applicationContext, LibraryDatabase::class.java).build()
        try {
            val layout = when (val resolved = resolveLayoutReconciliation(library.ledgerLayoutJson, emptyList())) {
                is LayoutReconciliation.OverwriteLocal -> resolved.widgets
                is LayoutReconciliation.PushLocal -> LedgerLayoutRules.defaultLedgerWidgets()
            }
            for (title in library.titles) {
                val row = title.graph()
                db.titleDao().upsertAll(listOf(TitleEntity(
                    id = title.id, tmdbId = title.tmdbId, type = title.mediaType.uppercase(),
                    title = title.title, year = title.year, director = row.optStringOrNull("director"),
                    genres = title.genres, posterUrl = title.posterUrl, backdropUrl = row.optStringOrNull("backdrop_url"),
                    synopsis = row.optStringOrNull("synopsis"), runtime = row.intOrNull("runtime"), network = row.optStringOrNull("network"),
                    status = title.status.uppercase(), rating = title.rating, notes = row.optStringOrNull("notes"),
                    addedAt = title.addedAt.orEmpty(), updatedAt = row.optStringOrNull("updated_at").orEmpty(),
                    imdbRating = row.doubleOrNull("imdb_rating"), originalLanguage = row.optStringOrNull("original_language"),
                    releaseDate = row.optStringOrNull("release_date"),
                )))
                val episodes = row.rows("episodes")
                val seasons = row.rows("seasons").map { season ->
                    SeasonEntity(season.getString("id"), title.id, season.getInt("season_number"),
                        season.optInt("episode_count"), season.optInt("episodes_watched"), season.intOrNull("air_year"))
                }.toMutableList()
                // The server's episodes reference season_number, not a season FK. Preserve
                // episode logs even if an older archive lacks its season metadata row.
                episodes.groupBy { it.getInt("season_number") }.forEach { (number, rows) ->
                    if (seasons.none { it.seasonNumber == number }) seasons += SeasonEntity(
                        "shared:${title.id}:$number", title.id, number, rows.size,
                        rows.count { it.rows("episode_watch_events").isNotEmpty() }, null)
                }
                db.seasonDao().upsertAll(seasons)
                db.episodeDao().upsertAll(episodes.map { ep ->
                    EpisodeEntity(ep.getString("id"), title.id,
                        seasons.first { it.seasonNumber == ep.getInt("season_number") }.id,
                        ep.getInt("episode_number"), ep.optStringOrNull("episode_name"), ep.optStringOrNull("air_date"),
                        ep.intOrNull("runtime"), ep.optStringOrNull("synopsis"), ep.optStringOrNull("still_url"))
                })
                db.episodeWatchEventDao().upsertAll(episodes.flatMap { ep -> ep.rows("episode_watch_events").map {
                    EpisodeWatchEventEntity(it.getString("id"), ep.getString("id"), it.optStringOrNull("watched_at"), it.optStringOrNull("notes"))
                } })
                db.episodeRatingDao().upsertAll(episodes.flatMap { ep -> ep.rows("episode_ratings").map {
                    EpisodeRatingEntity(it.getString("id"), ep.getString("id"), it.getDouble("rating"), it.getString("rated_at"))
                } })
                db.episodeReviewDao().upsertAll(episodes.flatMap { ep -> ep.rows("episode_reviews").map {
                    EpisodeReviewEntity(it.getString("id"), ep.getString("id"), it.getString("review_text"), it.getString("reviewed_at"))
                } })
                db.viewingDao().upsertAll(row.rows("viewings").map {
                    ViewingEntity(it.getString("id"), title.id, it.optStringOrNull("viewed_at"), it.doubleOrNull("rating"),
                        it.optStringOrNull("notes"), it.optStringOrNull("venue"), it.companionNames(), null)
                })
                db.titleCastDao().upsertAll(row.rows("title_cast").map {
                    TitleCastEntity(it.getString("id"), title.id, it.getInt("tmdb_person_id"), it.getString("name"),
                        it.optStringOrNull("character_name"), it.optInt("cast_order"))
                })
                db.titleCrewDao().upsertAll(row.rows("title_crew").map {
                    TitleCrewEntity(it.getString("id"), title.id, it.getInt("tmdb_person_id"), it.getString("name"),
                        it.getString("job"), it.optStringOrNull("department"))
                })
            }
            val ledger = LedgerRepository(db.titleDao(), db.viewingDao(), db.titleCastDao(), db.titleCrewDao(),
                db.cinemaOutingDao(), db.episodeWatchEventDao(), db.seasonDao(), db.episodeDao())
            SharedLibrarySnapshot(library, layout, ledger.observeLedgerStats().first(),
                ledger.observeLedgerBoards(flowOf(layout)).first())
        } finally {
            db.close()
        }
    }

private fun JSONObject.rows(key: String): List<JSONObject> =
    optJSONArray(key)?.let { a -> (0 until a.length()).map(a::getJSONObject) }.orEmpty()
private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)
private fun JSONObject.doubleOrNull(key: String): Double? = if (isNull(key)) null else optDouble(key)
private fun JSONObject.companionNames(): List<String> = optJSONArray("companions")?.let { a ->
    (0 until a.length()).mapNotNull { index ->
        when (val item = a.get(index)) {
            is JSONObject -> item.optStringOrNull("name")
            is String -> item
            else -> null
        }
    }
}.orEmpty()
