package work.kumarfamilynet.cinemarchive.data

import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.MediaCredit
import work.kumarfamilynet.cinemarchive.core.model.MediaCrewCredit
import work.kumarfamilynet.cinemarchive.core.model.MediaDetails
import work.kumarfamilynet.cinemarchive.core.model.MediaEpisode
import work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult
import work.kumarfamilynet.cinemarchive.core.model.MediaSeason
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.TrendingTitle

/**
 * TMDB JSON → domain mapping for every `media-proxy` action this client calls.
 *
 * Deliberately separate from [DiscoverRepository]: the mapping is where the real complexity
 * lives (movie and TV carry the same information under different keys, at different nesting
 * depths, with several fields TMDB simply omits rather than nulls), and keeping it as pure
 * functions makes it testable against recorded payloads without a network client or an Android
 * runtime. The web app's equivalents live in `apps/web/src/lib/media.ts` — `mapSearchItem`,
 * `fetchMediaDetails`, `extractCertification`, `mapTmdbCast` — and this file tracks them field
 * for field, since the two clients write the same `titles` rows.
 */

private const val TMDB_POSTER_BASE = "https://image.tmdb.org/t/p/w500"
private const val TMDB_BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280"
private const val TMDB_STILL_BASE = "https://image.tmdb.org/t/p/w300"

/** Title-level crew jobs worth keeping, matching `TITLE_CREW_JOBS` in the web app's
 *  `fetchMediaDetails`. TMDB's full crew list runs to hundreds of rows for a big production. */
private val TITLE_CREW_JOBS = setOf(
    "Director",
    "Screenplay",
    "Writer",
    "Producer",
    "Director of Photography",
    "Original Music Composer",
)

private fun JSONObject.stringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

private fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.doubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null

private fun JSONArray?.objects(): List<JSONObject> =
    this?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } } ?: emptyList()

private fun String?.yearOrNull(): Int? = this?.take(4)?.toIntOrNull()

// ─── search / trending ───────────────────────────────────────────────────────

/** Movies carry `title`/`release_date`; TV carries `name`/`first_air_date`. Every other
 *  difference between the two shapes is handled at the details level, not here. */
internal fun parseSearchItem(item: JSONObject, type: MediaType): MediaSearchResult {
    val date = item.stringOrNull(if (type == MediaType.MOVIE) "release_date" else "first_air_date")
    return MediaSearchResult(
        tmdbId = item.getInt("id"),
        title = item.stringOrNull(if (type == MediaType.MOVIE) "title" else "name").orEmpty(),
        year = date.yearOrNull(),
        type = type,
        posterUrl = item.stringOrNull("poster_path")?.let { "$TMDB_POSTER_BASE$it" },
        synopsis = item.stringOrNull("overview"),
        popularity = item.doubleOrNull("popularity") ?: 0.0,
    )
}

internal fun parseSearchPage(body: String, type: MediaType): List<MediaSearchResult> =
    JSONObject(body).optJSONArray("results").objects()
        // A hit with no title at all is unusable in a result list and unaddable — TMDB
        // occasionally returns these for foreign-only entries with no English name.
        .map { parseSearchItem(it, type) }
        .filter { it.title.isNotBlank() }

internal fun MediaSearchResult.asTrendingTitle() =
    TrendingTitle(tmdbId = tmdbId, title = title, year = year, type = type, posterUrl = posterUrl, synopsis = synopsis)

/**
 * Merges the separate movie and TV result lists into one. Search interleaves *by popularity*
 * rather than alternating positionally the way trending does: a query is a specific intent
 * ("severance"), so the single best match must be first, whereas the trending grid is a browse
 * surface where keeping both kinds visible near the top matters more than strict ranking.
 */
internal fun mergeSearchResults(movies: List<MediaSearchResult>, tv: List<MediaSearchResult>, limit: Int = 20) =
    (movies + tv).sortedByDescending { it.popularity }.take(limit)

// ─── details ─────────────────────────────────────────────────────────────────

/**
 * Maps one `action=details` payload. [fallback] supplies poster/synopsis/year for the rare
 * detail response that omits what the search hit already had — the same "fall back to `base`"
 * behavior `fetchMediaDetails` has in the web app.
 */
internal fun parseDetails(body: String, type: MediaType, fallback: MediaSearchResult): MediaDetails {
    val data = JSONObject(body)
    val date = data.stringOrNull("release_date") ?: data.stringOrNull("first_air_date")
    val crew = parseCrew(data, type)
    return MediaDetails(
        tmdbId = data.intOrNull("id") ?: fallback.tmdbId,
        type = type,
        title = data.stringOrNull("title") ?: data.stringOrNull("name") ?: fallback.title,
        year = date.yearOrNull() ?: fallback.year,
        releaseDate = date,
        // Movies get a director; TV creators are recorded as "Creator" crew instead, so a
        // series legitimately ends up with a null director and shows its network instead.
        director = crew.firstOrNull { it.job == "Director" }?.name,
        genres = data.optJSONArray("genres").objects().mapNotNull { it.stringOrNull("name") },
        posterUrl = data.stringOrNull("poster_path")?.let { "$TMDB_POSTER_BASE$it" } ?: fallback.posterUrl,
        backdropUrl = data.stringOrNull("backdrop_path")?.let { "$TMDB_BACKDROP_BASE$it" },
        synopsis = data.stringOrNull("overview") ?: fallback.synopsis,
        runtime = if (type == MediaType.MOVIE) data.intOrNull("runtime") else null,
        network = data.optJSONArray("networks").objects().firstOrNull()?.stringOrNull("name"),
        originalLanguage = data.stringOrNull("original_language"),
        contentRating = parseCertification(data, type),
        // Movie details carry imdb_id at the top level; TV exposes it via external_ids.
        imdbId = data.stringOrNull("imdb_id") ?: data.optJSONObject("external_ids")?.stringOrNull("imdb_id"),
        studios = data.optJSONArray("production_companies").objects().mapNotNull { it.stringOrNull("name") },
        collectionId = data.optJSONObject("belongs_to_collection")?.intOrNull("id"),
        collectionName = data.optJSONObject("belongs_to_collection")?.stringOrNull("name"),
        cast = parseCast(data, type),
        crew = crew,
        seasons = parseSeasons(data, type),
    )
}

/** US certification only, same as the web app: movies nest it under `release_dates`, TV under
 *  `content_ratings`, and both are arrays keyed by country. */
internal fun parseCertification(data: JSONObject, type: MediaType): String? =
    if (type == MediaType.MOVIE) {
        data.optJSONObject("release_dates")
            ?.optJSONArray("results").objects()
            .firstOrNull { it.optString("iso_3166_1") == "US" }
            ?.optJSONArray("release_dates").objects()
            .firstNotNullOfOrNull { it.stringOrNull("certification") }
    } else {
        data.optJSONObject("content_ratings")
            ?.optJSONArray("results").objects()
            .firstOrNull { it.optString("iso_3166_1") == "US" }
            ?.stringOrNull("rating")
    }

/** TV uses `aggregate_credits` (character sits under `roles[0]`, not on the row itself);
 *  movies have no aggregate endpoint and use plain `credits`.
 *
 *  De-duplicated by person: TMDB bills an actor once per role, so anyone playing two parts
 *  appears twice — and `title_cast`'s `unique (title_id, tmdb_person_id)` (schema.sql) would
 *  reject the second row, failing the whole add. Keeps the highest-billed entry. */
internal fun parseCast(data: JSONObject, type: MediaType): List<MediaCredit> {
    val aggregate = type == MediaType.TV && data.optJSONObject("aggregate_credits") != null
    val raw = if (aggregate) {
        data.optJSONObject("aggregate_credits")?.optJSONArray("cast")
    } else {
        data.optJSONObject("credits")?.optJSONArray("cast")
    }
    return raw.objects().mapNotNull { entry ->
        val name = entry.stringOrNull("name") ?: return@mapNotNull null
        MediaCredit(
            tmdbPersonId = entry.optInt("id"),
            name = name,
            characterName = if (aggregate) {
                entry.optJSONArray("roles").objects().firstOrNull()?.stringOrNull("character")
            } else {
                entry.stringOrNull("character")
            },
            order = entry.optInt("order", Int.MAX_VALUE),
        )
    }.sortedBy { it.order }.distinctBy { it.tmdbPersonId }
}

/** Filtered to [TITLE_CREW_JOBS] and de-duplicated by (person, job) — TMDB lists a person once
 *  per department they're credited in, so a writer-director appears twice under one job. */
internal fun parseCrew(data: JSONObject, type: MediaType): List<MediaCrewCredit> {
    val seen = mutableSetOf<String>()
    val crew = mutableListOf<MediaCrewCredit>()
    for (entry in data.optJSONObject("credits")?.optJSONArray("crew").objects()) {
        val job = entry.stringOrNull("job") ?: continue
        if (job !in TITLE_CREW_JOBS) continue
        val name = entry.stringOrNull("name") ?: continue
        val id = entry.optInt("id")
        if (!seen.add("$id:$job")) continue
        crew += MediaCrewCredit(id, name, job, entry.stringOrNull("department"))
    }
    if (type == MediaType.TV) {
        for (creator in data.optJSONArray("created_by").objects()) {
            val name = creator.stringOrNull("name") ?: continue
            val id = creator.optInt("id")
            if (!seen.add("$id:Creator")) continue
            crew += MediaCrewCredit(id, name, "Creator", "Writing")
        }
    }
    return crew
}

/** Season 0 is TMDB's "Specials" bucket — excluded here exactly as `buildSeasons` excludes it
 *  in the web app, so episode totals match across clients. */
internal fun parseSeasons(data: JSONObject, type: MediaType): List<MediaSeason> {
    if (type != MediaType.TV) return emptyList()
    return data.optJSONArray("seasons").objects()
        .mapNotNull { season ->
            val number = season.intOrNull("season_number") ?: return@mapNotNull null
            if (number <= 0) return@mapNotNull null
            MediaSeason(
                seasonNumber = number,
                episodeCount = season.optInt("episode_count", 0),
                airYear = season.stringOrNull("air_date").yearOrNull(),
            )
        }
        .sortedBy { it.seasonNumber }
}

/** Maps one `action=season` payload into the episode rows for that season. Still images are
 *  resolved to a full URL here (not a bare TMDB path), matching how the web app's backfill
 *  effect stores `stillUrl` — see `apps/web/src/components/TitleDetailDrawer.tsx`. */
internal fun parseSeasonEpisodes(body: String): List<MediaEpisode> =
    JSONObject(body).optJSONArray("episodes").objects()
        .mapNotNull { episode ->
            val number = episode.intOrNull("episode_number") ?: return@mapNotNull null
            MediaEpisode(
                episodeNumber = number,
                name = episode.stringOrNull("name"),
                airDate = episode.stringOrNull("air_date"),
                runtime = episode.intOrNull("runtime"),
                synopsis = episode.stringOrNull("overview"),
                stillUrl = episode.stringOrNull("still_path")?.let { "$TMDB_STILL_BASE$it" },
            )
        }
        .sortedBy { it.episodeNumber }

// ─── OMDb ratings ────────────────────────────────────────────────────────────

/** The three critic scores from `action=ratings` (OMDb). OMDb encodes "we don't have this" as
 *  the literal string `"N/A"` rather than omitting the key, and returns Rotten Tomatoes only
 *  inside a `Ratings` array keyed by source name. */
internal data class CriticScores(val imdbRating: Double?, val rtScore: Int?, val metacriticScore: Int?)

internal fun parseCriticScores(body: String): CriticScores {
    val data = JSONObject(body)
    val imdb = data.stringOrNull("imdbRating")?.takeIf { it != "N/A" }?.toDoubleOrNull()
    val metacritic = data.stringOrNull("Metascore")?.takeIf { it != "N/A" }?.toIntOrNull()
    val rt = data.optJSONArray("Ratings").objects()
        .firstOrNull { it.optString("Source") == "Rotten Tomatoes" }
        ?.stringOrNull("Value")
        ?.removeSuffix("%")
        ?.toIntOrNull()
    return CriticScores(imdb, rt, metacritic)
}
