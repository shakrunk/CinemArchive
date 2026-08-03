package work.kumarfamilynet.cinemarchive.core.model

/**
 * Domain types for the TMDB-backed *catalog* — titles that exist in the world but not
 * necessarily in the library. They deliberately don't reuse [LibraryTitle]/[TitleDetail]:
 * those describe a row the user owns (status, rating, viewing history), and none of that
 * exists yet for a search hit.
 *
 * The catalog is read through `DiscoverRepository` (the `media-proxy` Edge Function's
 * `search`/`details`/`season`/`ratings` actions) and becomes owned data only when
 * `LibraryRepository.addTitle` writes it — see [AddTitleRequest].
 */

/** One hit from `media-proxy?action=search` — the poster/title/year a result list can show
 *  before the (much larger) details call. Mirrors `mapSearchItem()` in the web app's
 *  `src/lib/media.ts`. */
data class MediaSearchResult(
    val tmdbId: Int,
    val title: String,
    val year: Int?,
    val type: MediaType,
    val posterUrl: String?,
    val synopsis: String?,
    /** TMDB's own popularity score — the merge order for the combined movie+TV result list. */
    val popularity: Double = 0.0,
)

/** A trending grid tap and a search hit both feed the same add flow, and the two carry the
 *  same fields — trending simply has no popularity score to order a result list by. */
fun TrendingTitle.asSearchResult() = MediaSearchResult(
    tmdbId = tmdbId,
    title = title,
    year = year,
    type = type,
    posterUrl = posterUrl,
    synopsis = synopsis,
)

/**
 * Everything `media-proxy?action=details` (plus the OMDb `ratings` action) can tell us about a
 * title, in the shape `LibraryRepository.addTitle` needs to write it.
 *
 * A few fields here have no column in the local Room mirror ([contentRating], [imdbId],
 * [studios], [collectionId]/[collectionName], [rtScore], [metacriticScore]) — they're carried
 * anyway because the *server* has columns for all of them (schema.sql's `titles`), and the web
 * app renders them. Dropping them on the Android add path would mean a title added on a phone
 * looks permanently poorer on the web than the same title added there. They ride in the outbox
 * payload only; see `SupabaseRemoteMutationWriter.insertTitle`.
 */
data class MediaDetails(
    val tmdbId: Int,
    val type: MediaType,
    val title: String,
    /** Null when TMDB has no release/first-air date at all. The server's `titles.year` is
     *  `not null`, so the write path substitutes 0 there — matching the web app's own
     *  `year: date ? ... : 0` fallback — while the local mirror keeps the honest null. */
    val year: Int?,
    val releaseDate: String?,
    val director: String?,
    val genres: List<String>,
    val posterUrl: String?,
    val backdropUrl: String?,
    val synopsis: String?,
    /** Minutes. Movies only — TMDB reports TV runtime per episode, and the web app leaves
     *  `titles.runtime` null for series too. */
    val runtime: Int?,
    val network: String?,
    val originalLanguage: String?,
    val contentRating: String?,
    val imdbId: String?,
    val studios: List<String> = emptyList(),
    val collectionId: Int? = null,
    val collectionName: String? = null,
    val imdbRating: Double? = null,
    val rtScore: Int? = null,
    val metacriticScore: Int? = null,
    val cast: List<MediaCredit> = emptyList(),
    val crew: List<MediaCrewCredit> = emptyList(),
    /** Empty for movies. Season 0 (TMDB "Specials") is filtered out, matching the web app. */
    val seasons: List<MediaSeason> = emptyList(),
)

data class MediaSeason(
    val seasonNumber: Int,
    val episodeCount: Int,
    val airYear: Int?,
    /** Populated by a per-season `action=season` call. Left empty when that call fails — the
     *  season still counts toward progress via [episodeCount], it just can't be ticked off
     *  episode by episode until a later sync fills the rows in. */
    val episodes: List<MediaEpisode> = emptyList(),
)

data class MediaEpisode(
    val episodeNumber: Int,
    val name: String?,
    val airDate: String?,
    val runtime: Int?,
    val synopsis: String? = null,
    val stillUrl: String? = null,
)

data class MediaCredit(
    val tmdbPersonId: Int,
    val name: String,
    val characterName: String?,
    val order: Int,
)

data class MediaCrewCredit(
    val tmdbPersonId: Int,
    val name: String,
    val job: String,
    val department: String?,
)

/** What the Add overlay's second step collects, on top of the TMDB metadata itself. */
data class AddTitleRequest(
    val details: MediaDetails,
    val status: LibraryStatus,
    /** 0.5–5.0 in half-star steps, or null when the user left it unrated. */
    val rating: Double?,
    val notes: String?,
    /** `YYYY-MM-DD`. Only honored for [LibraryStatus.WATCHED], where it seeds the title's
     *  first viewing so the Ledger's date-bucketed widgets have something to fold in. */
    val watchedOn: String? = null,
)
