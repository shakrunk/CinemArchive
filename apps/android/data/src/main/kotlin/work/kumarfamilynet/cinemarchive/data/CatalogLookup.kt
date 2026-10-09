package work.kumarfamilynet.cinemarchive.data

import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.TrendingTitle

data class CatalogLookup(val id: Int, val name: String, val description: String, val imageUrl: String?)

internal fun catalogLookupQuery(query: String, people: Boolean): String =
    "action=${if (people) "person_search" else "company_search"}&q=${URLEncoder.encode(query.trim(), "UTF-8")}"

internal fun parseCatalogLookups(body: String, people: Boolean): List<CatalogLookup> {
    val rows = JSONObject(body).optJSONArray("results") ?: return emptyList()
    return (0 until minOf(8, rows.length())).mapNotNull { index ->
        val row = rows.getJSONObject(index)
        val id = row.optInt("id")
        val name = row.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (id <= 0) return@mapNotNull null
        val known = row.optJSONArray("known_for") ?: JSONArray()
        val description = if (people) (0 until minOf(3, known.length())).mapNotNull {
            val title = known.getJSONObject(it)
            title.optString("title").ifBlank { title.optString("name") }.takeIf(String::isNotBlank)
        }.joinToString(", ").ifBlank { row.optString("known_for_department") } else row.optString("origin_country")
        val imageKey = if (people) "profile_path" else "logo_path"
        val image = if (row.isNull(imageKey)) null else row.optString(imageKey).takeIf { it.startsWith("/") }
        CatalogLookup(id, name, description, image?.let { "https://image.tmdb.org/t/p/${if (people) "w185" else "w92"}$it" })
    }.distinctBy { it.id }
}

/** Same cast+crew identity, popularity ordering and cap as web fetchPersonCredits. */
internal fun parseCatalogPersonTitles(body: String): List<TrendingTitle> {
    val data = JSONObject(body)
    val rows = listOf("cast", "crew").flatMap { key ->
        val array = data.optJSONArray(key) ?: JSONArray()
        (0 until array.length()).map(array::getJSONObject)
    }.distinctBy { it.optString("media_type") to it.optInt("id") }
        .sortedByDescending { it.optDouble("popularity", 0.0) }
        .filter { it.optString("media_type") in setOf("movie", "tv") }.take(40)
    return rows.flatMap { row ->
        val type = if (row.getString("media_type") == "movie") MediaType.MOVIE else MediaType.TV
        parseSearchPage(JSONObject().put("results", JSONArray().put(row)).toString(), type).map { it.asTrendingTitle() }
    }
}

internal suspend fun fetchCatalogStudioTitles(id: Int, type: MediaType?, invoke: suspend (String) -> String): List<TrendingTitle> = coroutineScope {
    require(id > 0)
    suspend fun fetch(kind: MediaType) = parseSearchPage(invoke(
        "action=discover&type=${if (kind == MediaType.MOVIE) "movie" else "tv"}&company=$id"), kind).map { it.asTrendingTitle() }
    if (type != null) fetch(type) else {
        val movies = async { fetch(MediaType.MOVIE) }
        val tv = async { fetch(MediaType.TV) }
        val a = movies.await(); val b = tv.await()
        buildList { for (i in 0 until maxOf(a.size, b.size)) { a.getOrNull(i)?.let(::add); b.getOrNull(i)?.let(::add) } }.take(20)
    }
}
