package work.kumarfamilynet.cinemarchive.data

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import work.kumarfamilynet.cinemarchive.core.model.EpisodeCast
import work.kumarfamilynet.cinemarchive.core.model.MediaDetails
import work.kumarfamilynet.cinemarchive.core.model.MediaEpisode
import work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.TrendingTitle
import work.kumarfamilynet.cinemarchive.core.model.isSpecials

/**
 * The app's read-only window onto TMDB/OMDb, through the `media-proxy` Edge Function
 * (supabase/functions/media-proxy/index.ts): trending for the Discover grid, plus search and
 * details for the Add-title flow. Mirrors `apps/web/src/lib/media.ts`; the JSON→domain mapping
 * itself lives in `MediaProxyParsing.kt` so it can be tested without a network client.
 *
 * Nothing here touches Room — a catalog result becomes owned data only when
 * [LibraryRepository.addTitle] writes it.
 */
class DiscoverRepository(
    private val client: SupabaseRestClient,
    private val authRepository: AuthRepository,
) : EpisodeMetadataFetcher {
    suspend fun searchPeople(query: String): List<CatalogLookup> = searchLookups(query, people = true)
    suspend fun searchStudios(query: String): List<CatalogLookup> = searchLookups(query, people = false)

    private suspend fun searchLookups(query: String, people: Boolean): List<CatalogLookup> = withContext(Dispatchers.IO) {
        if (query.isBlank()) emptyList() else parseCatalogLookups(
            client.invokeFunction("media-proxy", catalogLookupQuery(query, people), accessToken()), people)
    }

    suspend fun fetchPersonTitles(id: Int): List<TrendingTitle> = withContext(Dispatchers.IO) {
        require(id > 0)
        parseCatalogPersonTitles(client.invokeFunction("media-proxy", "action=person_credits&id=$id", accessToken()))
    }

    suspend fun fetchRecommendations(id: Int, type: MediaType): List<TrendingTitle> = withContext(Dispatchers.IO) {
        val token = accessToken()
        fetchCatalogRecommendations(id, type) { client.invokeFunction("media-proxy", it, token) }
    }

    suspend fun fetchStudioTitles(id: Int, type: MediaType?): List<TrendingTitle> = withContext(Dispatchers.IO) {
        val token = accessToken()
        fetchCatalogStudioTitles(id, type) { client.invokeFunction("media-proxy", it, token) }
    }

    /** A complete catalog page for the selected type and optional genre. Search/add APIs stay separate. */
    suspend fun fetchBrowse(type: MediaType?, genreId: Int?, page: Int): List<TrendingTitle> = withContext(Dispatchers.IO) {
        val token = accessToken()
        fetchDiscoverBrowse(type, genreId, page) { query -> client.invokeFunction("media-proxy", query, token) }
    }

    /** This week's trending movies and TV, interleaved so both kinds stay visible near the
     *  top — matching `fetchTrending('all')`'s alternating merge in the web app. */
    suspend fun fetchTrending(): List<TrendingTitle> = withContext(Dispatchers.IO) {
        val accessToken = accessToken()
        coroutineScope {
            val movies = async { trendingPage(MediaType.MOVIE, accessToken) }
            val tv = async { trendingPage(MediaType.TV, accessToken) }
            interleave(movies.await(), tv.await()).map { it.asTrendingTitle() }
        }
    }

    /**
     * Searches movies and TV in parallel and merges by TMDB popularity (see
     * [mergeSearchResults] for why search ranks where trending interleaves). A blank query
     * short-circuits before any network call, so an empty search box costs nothing.
     */
    suspend fun searchMedia(query: String): List<MediaSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val accessToken = accessToken()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        coroutineScope {
            val movies = async { searchPage(encoded, MediaType.MOVIE, accessToken) }
            val tv = async { searchPage(encoded, MediaType.TV, accessToken) }
            mergeSearchResults(movies.await(), tv.await())
        }
    }

    /**
     * Hydrates a search hit into everything the add path needs to write, in two round trips:
     * the `details` call, then — in parallel — OMDb critic scores (only when TMDB gave us an
     * IMDb id to look them up by) and one `season` call per season for a series' episode rows.
     *
     * Both of those are enrichment rather than the substance of the title, so each degrades on
     * its own instead of failing the add: missing scores just leave the Ledger's Second
     * Opinions widget without a data point, and a season whose episode call fails keeps its
     * episode *count* (so progress still works) and simply can't be ticked off episode by
     * episode until a later sync fills the rows in.
     */
    suspend fun fetchDetails(result: MediaSearchResult): MediaDetails = withContext(Dispatchers.IO) {
        val accessToken = accessToken()
        val base = parseDetails(
            client.invokeFunction(
                "media-proxy",
                "action=details&id=${result.tmdbId}&type=${result.type.param()}",
                accessToken,
            ),
            result.type,
            result,
        )
        coroutineScope {
            val scores = async {
                base.imdbId?.let { imdbId ->
                    runCatching {
                        parseCriticScores(client.invokeFunction("media-proxy", "action=ratings&imdb=$imdbId", accessToken))
                    }.getOrNull()
                }
            }
            val seasons = base.seasons.map { season ->
                async {
                    val (episodes, cast) = runCatching {
                        val body = client.invokeFunction(
                            "media-proxy",
                            "action=season&id=${base.tmdbId}&season=${season.seasonNumber}",
                            accessToken,
                        )
                        parseSeasonEpisodes(body) to parseSeasonCast(body)
                    }.getOrDefault(emptyList<MediaEpisode>() to emptyList())
                    if (season.isSpecials) {
                        // Specials keep TMDB's own (possibly non-contiguous) numbering and
                        // count only the episodes TMDB actually returned — see buildSeasons.
                        season.copy(episodes = episodes, episodeCount = episodes.size, cast = cast)
                    } else {
                        season.copy(episodes = episodes, cast = cast)
                    }
                }
            }
            val critics = scores.await()
            base.copy(
                imdbRating = critics?.imdbRating,
                rtScore = critics?.rtScore,
                metacriticScore = critics?.metacriticScore,
                // A Specials season whose episode fetch came back empty is dropped rather
                // than stored as an empty shell.
                seasons = seasons.map { it.await() }.filterNot { it.isSpecials && it.episodes.isEmpty() },
            )
        }
    }

    /**
     * On-demand backfill of one season's episode rows — [LibraryRepository]'s equivalent of
     * the web app's `TitleDetailDrawer` backfill effect (`fetchSeasonDetails`), so a title
     * *added on Android* still gets synopsis/still images without ever having been opened on
     * web. Same call [fetchDetails] already makes at add-time; exposed separately because the
     * backfill runs later, against seasons that already exist locally.
     */
    override suspend fun fetchSeasonEpisodes(tmdbId: Int, seasonNumber: Int): List<MediaEpisode> =
        withContext(Dispatchers.IO) {
            runCatching {
                parseSeasonEpisodes(
                    client.invokeFunction("media-proxy", "action=season&id=$tmdbId&season=$seasonNumber", accessToken()),
                )
            }.getOrDefault(emptyList())
        }

    /** Per-episode cast for the title detail screen — the web app's `fetchEpisodeCast`. Fetched
     *  on demand when an episode's cast is opened rather than at add-time, since TMDB bills it
     *  per episode and nothing else in the app reads it. */
    override suspend fun fetchEpisodeCast(tmdbId: Int, seasonNumber: Int, episodeNumber: Int): EpisodeCast =
        withContext(Dispatchers.IO) {
            runCatching {
                parseEpisodeCredits(
                    client.invokeFunction(
                        "media-proxy",
                        "action=episode_credits&id=$tmdbId&season=$seasonNumber&episode=$episodeNumber",
                        accessToken(),
                    ),
                )
            }.getOrDefault(EpisodeCast.EMPTY)
        }

    /**
     * Resolves an IMDb or TVDB id to a TMDB hit through `media-proxy`'s `find` action — exact,
     * no fuzzy matching. Used by sync to map Simkl/Plex/Emby items. Returns null when TMDB has
     * no match; [preferType] orders movie vs TV when an id exists as both.
     */
    suspend fun findByExternalId(source: String, externalId: String, preferType: MediaType): MediaSearchResult? =
        withContext(Dispatchers.IO) {
            val body = client.invokeFunction(
                "media-proxy",
                "action=find&id=${URLEncoder.encode(externalId, "UTF-8")}&source=$source",
                accessToken(),
            )
            val json = org.json.JSONObject(body)
            val movies = parseSearchPage("""{"results":${json.optJSONArray("movie_results") ?: "[]"}}""", MediaType.MOVIE)
            val tv = parseSearchPage("""{"results":${json.optJSONArray("tv_results") ?: "[]"}}""", MediaType.TV)
            (if (preferType == MediaType.TV) tv + movies else movies + tv).firstOrNull()
        }

    /** Discover browsing deliberately works signed-out, so the anon key backstops the bearer
     *  token — the same fallback `supabase-js`'s `functions.invoke` applies. */
    private fun accessToken(): String? = authRepository.currentSession()?.accessToken

    private fun MediaType.param() = if (this == MediaType.MOVIE) "movie" else "tv"

    private fun trendingPage(type: MediaType, accessToken: String?): List<MediaSearchResult> =
        parseSearchPage(client.invokeFunction("media-proxy", "action=trending&type=${type.param()}", accessToken), type)

    private fun searchPage(query: String, type: MediaType, accessToken: String?): List<MediaSearchResult> =
        parseSearchPage(client.invokeFunction("media-proxy", "action=search&q=$query&type=${type.param()}", accessToken), type)

    /** Alternate-push two lists so the top of each kind stays visible. */
    private fun interleave(a: List<MediaSearchResult>, b: List<MediaSearchResult>): List<MediaSearchResult> {
        val combined = mutableListOf<MediaSearchResult>()
        for (i in 0 until maxOf(a.size, b.size)) {
            if (i < a.size) combined += a[i]
            if (i < b.size) combined += b[i]
        }
        return combined
    }
}

/** Mirrors web's movie-first mixed page, with each request retaining the same genre and page. */
internal suspend fun fetchDiscoverBrowse(
    type: MediaType?,
    genreId: Int?,
    page: Int,
    invoke: suspend (String) -> String,
): List<TrendingTitle> = coroutineScope {
    require(page in 1..500) { "Catalog page must be between 1 and 500" }
    require(genreId == null || genreId > 0) { "Invalid genre" }
    suspend fun fetch(mediaType: MediaType): List<MediaSearchResult> {
        val action = if (genreId == null) "trending" else "discover"
        val kind = if (mediaType == MediaType.MOVIE) "movie" else "tv"
        val query = "action=$action&type=$kind&page=$page" + (genreId?.let { "&genre=$it" } ?: "")
        return parseSearchPage(invoke(query), mediaType)
    }
    val results = if (type != null) {
        fetch(type).let { if (genreId == null) it.take(20) else it }
    } else {
        val movies = async { fetch(MediaType.MOVIE) }
        val television = async { fetch(MediaType.TV) }
        val moviePage = movies.await()
        val tvPage = television.await()
        buildList {
            for (i in 0 until maxOf(moviePage.size, tvPage.size)) {
                moviePage.getOrNull(i)?.let { add(it) }
                tvPage.getOrNull(i)?.let { add(it) }
            }
        }.take(20)
    }
    results.map { it.asTrendingTitle() }
}
