package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.TrendingTitle

internal fun fetchCatalogRecommendations(id: Int, type: MediaType, request: (String) -> String): List<TrendingTitle> {
    require(id > 0)
    val body = request("action=recommendations&id=$id&type=${if (type == MediaType.MOVIE) "movie" else "tv"}&page=1")
    val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
    return (0 until results.length()).mapNotNull { index ->
        results.optJSONObject(index)?.let { row ->
            // A movie seed may recommend TV. Web treats an absent media_type as movie too.
            parseSearchItem(row, if (row.optString("media_type") == "tv") MediaType.TV else MediaType.MOVIE)
                .takeIf { it.title.isNotBlank() && it.tmdbId > 0 }?.asTrendingTitle()
        }
    }.distinctBy { it.tmdbId to it.type }
}
