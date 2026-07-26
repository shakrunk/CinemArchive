package work.kumarfamilynet.cinemarchive.data

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import work.kumarfamilynet.cinemarchive.core.model.MediaDetails
import work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.TrendingTitle

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
) {
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
                    val episodes = runCatching {
                        parseSeasonEpisodes(
                            client.invokeFunction(
                                "media-proxy",
                                "action=season&id=${base.tmdbId}&season=${season.seasonNumber}",
                                accessToken,
                            ),
                        )
                    }.getOrDefault(emptyList())
                    season.copy(episodes = episodes)
                }
            }
            val critics = scores.await()
            base.copy(
                imdbRating = critics?.imdbRating,
                rtScore = critics?.rtScore,
                metacriticScore = critics?.metacriticScore,
                seasons = seasons.map { it.await() },
            )
        }
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
