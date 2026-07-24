package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.TrendingTitle

private const val TMDB_POSTER_BASE = "https://image.tmdb.org/t/p/w500"

/**
 * This week's trending movies/TV from TMDB, via the `media-proxy` Edge Function's `trending`
 * action (supabase/functions/media-proxy/index.ts) — read-only and not persisted; backs the
 * Discover tab's live catalog. Movie and TV pages are fetched in parallel and interleaved so
 * both kinds stay visible near the top, mirroring `src/lib/media.ts`'s `fetchTrending('all')`.
 */
class DiscoverRepository(
    private val client: SupabaseRestClient,
    private val authRepository: AuthRepository,
) {
    suspend fun fetchTrending(): List<TrendingTitle> = withContext(Dispatchers.IO) {
        val accessToken = authRepository.currentSession()?.accessToken
        coroutineScope {
            val movies = async { fetchByType(MediaType.MOVIE, accessToken) }
            val tv = async { fetchByType(MediaType.TV, accessToken) }
            interleave(movies.await(), tv.await())
        }
    }

    private fun fetchByType(type: MediaType, accessToken: String?): List<TrendingTitle> {
        val typeParam = if (type == MediaType.MOVIE) "movie" else "tv"
        val body = client.invokeFunction("media-proxy", "action=trending&type=$typeParam", accessToken)
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i -> parseItem(results.getJSONObject(i), type) }
    }

    private fun parseItem(item: JSONObject, type: MediaType): TrendingTitle {
        // Movies carry `title`/`release_date`; TV carries `name`/`first_air_date`.
        val title = if (type == MediaType.MOVIE) item.optString("title") else item.optString("name")
        val dateKey = if (type == MediaType.MOVIE) "release_date" else "first_air_date"
        val date = item.optString(dateKey, "").takeIf { it.isNotEmpty() }
        val posterPath = item.optString("poster_path", "").takeIf { it.isNotEmpty() }
        val synopsis = item.optString("overview", "").takeIf { it.isNotEmpty() }
        return TrendingTitle(
            tmdbId = item.getInt("id"),
            title = title,
            year = date?.take(4)?.toIntOrNull(),
            type = type,
            posterUrl = posterPath?.let { "$TMDB_POSTER_BASE$it" },
            synopsis = synopsis,
        )
    }

    /** Alternate-push two lists so the top of each kind stays visible. */
    private fun interleave(a: List<TrendingTitle>, b: List<TrendingTitle>): List<TrendingTitle> {
        val combined = mutableListOf<TrendingTitle>()
        val maxLen = maxOf(a.size, b.size)
        for (i in 0 until maxLen) {
            if (i < a.size) combined += a[i]
            if (i < b.size) combined += b[i]
        }
        return combined
    }
}
