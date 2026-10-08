package work.kumarfamilynet.cinemarchive.data

import java.text.Normalizer
import java.time.Instant
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult
import work.kumarfamilynet.cinemarchive.core.model.MediaType

/**
 * Provider-agnostic core for third-party sync (Simkl, Plex, Emby) — the Kotlin counterpart of
 * `apps/web/src/lib/sync/core.ts`. Each provider adapter maps its payload to [SyncItem]s;
 * [SyncRepository] resolves them to TMDB and merges them into the library using [planMerge],
 * which never overwrites local data. Everything here is pure so it's unit-tested without a
 * network client. TV is title-level only (status/rating); episode history is a follow-up on
 * both clients.
 */
enum class SyncProvider(val wire: String) {
    SIMKL("simkl"),
    PLEX("plex"),
    EMBY("emby"),
    LETTERBOXD("letterboxd"),
    ;

    companion object {
        fun fromWire(value: String): SyncProvider? = entries.firstOrNull { it.wire == value }
    }
}

data class ExternalIds(val tmdb: Int? = null, val imdb: String? = null, val tvdb: Int? = null)

data class SyncItem(
    val provider: SyncProvider,
    /** Stable id within the provider — drives `external_title_links` dedupe. */
    val externalId: String,
    val type: MediaType,
    val title: String,
    val year: Int?,
    val ids: ExternalIds,
    val status: LibraryStatus,
    /** 0.5–5 half-star scale (this app's scale). */
    val rating: Double?,
    /** `YYYY-MM-DD`, any order. */
    val watchedDates: List<String>,
)

/** What [planMerge] needs to know about a title already in the library. */
data class TitleSyncSnapshot(
    val status: LibraryStatus,
    val rating: Double?,
    val viewingDates: Set<String>,
)

data class MergePatch(
    val status: LibraryStatus? = null,
    val rating: Double? = null,
    val newViewingDates: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = status == null && rating == null && newViewingDates.isEmpty()
}

/** Provider 1–10 rating → app 0.5–5 half stars; zero/absent → null. */
fun ratingFromTen(value: Double?): Double? {
    if (value == null || value.isNaN() || value <= 0.0) return null
    return maxOf(0.5, Math.round(minOf(value, 10.0)) / 2.0)
}

/** App 0.5–5 rating → provider 1–10. */
fun ratingToTen(value: Double?): Int? {
    if (value == null || value.isNaN() || value <= 0.0) return null
    return Math.round(value * 2).toInt().coerceIn(1, 10)
}

private val GUID_REGEX = Regex("""(?:^|\.)(tmdb|themoviedb|imdb|tvdb|thetvdb)://([^?/\s]+)""", RegexOption.IGNORE_CASE)

/** Parses Plex/Emby-style guid strings: `tmdb://603`, `imdb://tt0133093`, `tvdb://78901`, or
 *  Plex's legacy `com.plexapp.agents.imdb://tt0133093?lang=en`. */
fun parseGuids(guids: List<String?>): ExternalIds {
    var tmdb: Int? = null
    var imdb: String? = null
    var tvdb: Int? = null
    for (raw in guids) {
        val match = raw?.let { GUID_REGEX.find(it) } ?: continue
        val value = match.groupValues[2]
        when (match.groupValues[1].lowercase()) {
            "tmdb", "themoviedb" -> value.toIntOrNull()?.let { tmdb = it }
            "imdb" -> if (Regex("""tt\d+""").matches(value)) imdb = value
            else -> value.toIntOrNull()?.let { tvdb = it }
        }
    }
    return ExternalIds(tmdb, imdb, tvdb)
}

private val DATE_ONLY = Regex("""\d{4}-\d{2}-\d{2}""")

/** Accepts `YYYY-MM-DD` or an ISO timestamp; returns the UTC calendar date, or null. */
fun toDateOnly(value: String?): String? {
    if (value.isNullOrBlank()) return null
    if (DATE_ONLY.matches(value)) return value
    return runCatching { Instant.parse(value).toString().substring(0, 10) }.getOrNull()
}

fun toDateOnly(epochSeconds: Long): String = Instant.ofEpochSecond(epochSeconds).toString().substring(0, 10)

/**
 * Decides what an incoming item adds to an existing title. Never overwrites: only fills an
 * empty rating, adds viewings on dates not yet logged, and promotes watchlist → watched.
 * Returns null when nothing would change.
 */
fun planMerge(existing: TitleSyncSnapshot, type: MediaType, item: SyncItem): MergePatch? {
    var status: LibraryStatus? = null
    if (item.status == LibraryStatus.WATCHED && existing.status == LibraryStatus.WATCHLIST) status = LibraryStatus.WATCHED
    val rating = if (existing.rating == null) item.rating else null

    var fresh = emptyList<String>()
    if (type == MediaType.MOVIE && item.watchedDates.isNotEmpty()) {
        fresh = item.watchedDates.distinct().filter { it !in existing.viewingDates }.sorted()
        if (fresh.isNotEmpty() && existing.status != LibraryStatus.WATCHED && status == null) status = LibraryStatus.WATCHED
    }
    val patch = MergePatch(status, rating, fresh)
    return patch.takeUnless { it.isEmpty }
}

/** Case/diacritic/punctuation-insensitive title comparison key. */
internal fun normTitle(value: String): String =
    Normalizer.normalize(value.lowercase(), Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

/**
 * Chooses the TMDB candidate for a name-only item: exact title and exact year dominate, ±1 year
 * tolerates festival-vs-wide release edges, and TMDB's own relevance order breaks ties. With a
 * year present but nothing matching title or year it returns null — surfacing an unmatched row
 * beats silently importing a lookalike. Ports `pickBestMatch` from `letterboxd-import.ts`,
 * generalized over [type].
 */
fun pickBestMatch(candidates: List<MediaSearchResult>, name: String, year: Int?, type: MediaType): MediaSearchResult? {
    val pool = candidates.filter { it.type == type }
    if (pool.isEmpty()) return null
    val target = normTitle(name)
    fun score(c: MediaSearchResult): Int {
        var s = 0
        if (normTitle(c.title) == target) s += 4
        val cy = c.year
        if (year != null && cy != null) {
            if (cy == year) s += 3 else if (kotlin.math.abs(cy - year) == 1) s += 1
        }
        return s
    }
    var best = pool.first()
    var bestScore = score(best)
    for (c in pool.drop(1)) {
        val sc = score(c)
        if (sc > bestScore) { best = c; bestScore = sc }
    }
    if (year != null && bestScore == 0) return null
    // A name-only TV hit must at least share the title — TMDB happily returns near-misses.
    if (type == MediaType.TV && normTitle(best.title) != target) return null
    return best
}
