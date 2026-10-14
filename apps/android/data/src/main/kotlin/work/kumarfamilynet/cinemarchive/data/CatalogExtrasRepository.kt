package work.kumarfamilynet.cinemarchive.data

import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.*

interface CatalogExtrasSource {
    suspend fun videos(key: CatalogExtrasKey): List<CatalogVideo>
    suspend fun providers(key: CatalogExtrasKey): CatalogProviders?
}

/** Reads public catalog metadata using the runtime's generation-fenced session; never persists it. */
class CatalogExtrasRepository(
    private val session: SessionSource,
    private val request: (query: String, token: String) -> String,
) : CatalogExtrasSource {
    constructor(client: SupabaseRestClient, session: SessionSource) :
        this(session, { query, token -> client.invokeFunction("media-proxy", query, token) })

    private suspend fun fetch(action: String, key: CatalogExtrasKey): String = withContext(Dispatchers.IO) {
        require(key.tmdbId > 0)
        val before = session.currentSession() ?: error("This sign-in has ended")
        currentCoroutineContext().ensureActive()
        val response = request("action=$action&id=${key.tmdbId}&type=${if (key.type == MediaType.MOVIE) "movie" else "tv"}", before.accessToken)
        currentCoroutineContext().ensureActive()
        check(session.currentSession()?.userId == before.userId) { "This sign-in has ended" }
        response
    }

    override suspend fun videos(key: CatalogExtrasKey) = parseCatalogVideos(fetch("videos", key))
    override suspend fun providers(key: CatalogExtrasKey) = parseCatalogProviders(fetch("watch_providers", key), key.region)
}

internal fun parseCatalogVideos(json: String): List<CatalogVideo> {
    val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
    return (0 until results.length()).mapNotNull { index ->
        val value = results.optJSONObject(index) ?: return@mapNotNull null
        val type = value.optString("type")
        val key = value.optString("key")
        if (value.optString("site") != "YouTube" || type !in setOf("Trailer", "Teaser") || !Regex("[A-Za-z0-9_-]{11}").matches(key)) return@mapNotNull null
        CatalogVideo(key, value.optString("name").ifBlank { type }, type, value.optBoolean("official"))
    }.sortedByDescending { (if (it.official) 2 else 0) + (if (it.type == "Trailer") 1 else 0) }.take(4)
}

internal fun parseCatalogProviders(json: String, region: String): CatalogProviders? {
    val results = JSONObject(json).optJSONObject("results") ?: return null
    val selected = results.optJSONObject(region) ?: results.optJSONObject("US") ?: return null
    fun list(name: String): List<CatalogProvider> {
        val array = selected.optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optInt("provider_id", -1)
            val label = item.optString("provider_name")
            if (id < 0 || label.isBlank()) return@mapNotNull null
            val path = item.optString("logo_path")
            CatalogProvider(id, label, path.takeIf { Regex("/[A-Za-z0-9._-]+").matches(it) }?.let { "https://image.tmdb.org/t/p/w92$it" })
        }
    }
    val link = selected.optString("link").takeIf { value ->
        runCatching { URI(value).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
    }
    return CatalogProviders(link, list("flatrate") + list("free") + list("ads"), list("rent"), list("buy"))
}
