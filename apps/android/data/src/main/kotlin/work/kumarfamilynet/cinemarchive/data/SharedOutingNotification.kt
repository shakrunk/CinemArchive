package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.AddTitleRequest
import work.kumarfamilynet.cinemarchive.core.model.CinemaFormat
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaDetails
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.OutingSchedulePrefill

data class SharedOutingNotification(
    val tmdbId: Int,
    val type: MediaType,
    val year: Int?,
    val posterUrl: String?,
    val plan: PublicOutingPlan,
) {
    val prefill get() = OutingSchedulePrefill(plan.showtime, plan.venue, plan.format)
    fun addRequest() = AddTitleRequest(MediaDetails(
        tmdbId, type, plan.title, year, releaseDate = null, director = null, genres = emptyList(),
        posterUrl = posterUrl, backdropUrl = null, synopsis = null, runtime = null, network = null,
        originalLanguage = null, contentRating = null, imdbId = null,
    ), LibraryStatus.WATCHLIST, rating = null, notes = null)
}

/** Snapshot-only parsing: the recipient must never fetch the sender's private outing. */
fun parseSharedOutingNotification(payload: JSONObject): SharedOutingNotification? = runCatching {
    val id = payload.opt("tmdb_id") as? Number ?: return null
    require(id.toDouble() == id.toInt().toDouble() && id.toInt() > 0)
    val type = when (payload.opt("type")) {
        "movie" -> MediaType.MOVIE
        "tv" -> MediaType.TV
        else -> return null
    }
    val title = payload.opt("title") as? String ?: return null
    val showtime = payload.opt("showtime") as? String ?: return null
    val endsAt = payload.opt("ends_at") as? String ?: return null
    require(Instant.parse(endsAt).isAfter(Instant.parse(showtime)))
    fun text(key: String) = payload.opt(key) as? String
    val companions = payload.optJSONArray("companions")?.let { names ->
        (0 until names.length()).mapNotNull { names.opt(it) as? String }
    }.orEmpty()
    SharedOutingNotification(id.toInt(), type, (payload.opt("year") as? Number)?.toInt(), text("poster_url"),
        PublicOutingPlan("", title, showtime, endsAt, text("venue"),
            text("format")?.let(CinemaFormat::fromWire), text("seat"), companions))
}.getOrNull()

suspend fun resolveSharedOutingNotification(
    shared: SharedOutingNotification,
    isActive: () -> Boolean,
    findOwned: suspend (Int, MediaType) -> String?,
    addTitle: suspend (AddTitleRequest) -> String,
): String? {
    if (!isActive()) return null
    val existing = findOwned(shared.tmdbId, shared.type)
    if (!isActive()) return null
    val id = existing ?: addTitle(shared.addRequest())
    return id.takeIf { isActive() }
}
