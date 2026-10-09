package work.kumarfamilynet.cinemarchive.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaType

/**
 * Provider adapters for third-party sync — the Kotlin counterparts of
 * `apps/web/src/lib/sync/{simkl,plex,emby}.ts`. Simkl goes through the `simkl-sync` Edge
 * Function (tokens stay server-side); Plex and Emby talk straight to the user's own server.
 * Plex tokens and Emby passwords are held in memory for a sync only and never persisted.
 * The `map*` functions are pure and unit-tested.
 */

// ─── Simkl (via Edge Function) ───────────────────────────────────────────────

data class SimklDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val interval: Int,
    val expiresIn: Int,
)

sealed interface SimklPoll {
    data object Pending : SimklPoll
    data object SlowDown : SimklPoll
    data object Connected : SimklPoll
}

class SimklApi(
    private val client: SupabaseRestClient,
    private val authRepository: SessionSource,
) {
    fun start(): SimklDeviceCode {
        val json = call(JSONObject().put("action", "start").put("write", false))
        return SimklDeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.getString("verification_uri"),
            interval = json.optInt("interval", 5),
            expiresIn = json.optInt("expires_in", 900),
        )
    }

    fun poll(deviceCode: String): SimklPoll {
        val json = call(JSONObject().put("action", "poll").put("device_code", deviceCode).put("write", false))
        return when {
            json.optBoolean("connected") -> SimklPoll.Connected
            json.optBoolean("slowDown") -> SimklPoll.SlowDown
            else -> SimklPoll.Pending
        }
    }

    fun items(): List<SyncItem> = mapSimklItems(call(JSONObject().put("action", "items")).optJSONArray("items") ?: JSONArray())

    fun disconnect() {
        call(JSONObject().put("action", "disconnect"))
    }

    // The function answers 200 with { error } on failure (functions.invoke discards non-2xx bodies).
    private fun call(body: JSONObject): JSONObject {
        val token = authRepository.currentSession()?.accessToken
        val json = JSONObject(client.invokeFunctionPost("simkl-sync", body.toString(), token))
        json.optString("error").takeIf { it.isNotEmpty() }?.let { error(it) }
        return json
    }
}

internal fun mapSimklItems(items: JSONArray): List<SyncItem> = buildList {
    for (i in 0 until items.length()) {
        val o = items.optJSONObject(i) ?: continue
        val type = if (o.optString("type") == "movie") MediaType.MOVIE else MediaType.TV
        val status = when (o.optString("status")) {
            "watched" -> LibraryStatus.WATCHED
            "watching" -> LibraryStatus.WATCHING
            "dropped" -> LibraryStatus.DROPPED
            else -> LibraryStatus.WATCHLIST
        }
        val ids = o.optJSONObject("ids")
        val date = toDateOnly(o.optString("lastWatchedAt").takeIf { it.isNotEmpty() })
        add(
            SyncItem(
                provider = SyncProvider.SIMKL,
                externalId = o.optString("externalId"),
                type = type,
                title = o.optString("title"),
                year = if (o.has("year") && !o.isNull("year")) o.optInt("year") else null,
                ids = ExternalIds(
                    tmdb = ids?.takeIf { it.has("tmdb") && !it.isNull("tmdb") }?.optInt("tmdb"),
                    imdb = ids?.optString("imdb")?.takeIf { it.isNotEmpty() },
                    tvdb = ids?.takeIf { it.has("tvdb") && !it.isNull("tvdb") }?.optInt("tvdb"),
                ),
                status = status,
                rating = ratingFromTen(if (o.has("rating") && !o.isNull("rating")) o.optDouble("rating") else null),
                // Simkl exposes only the latest watch date at list level.
                watchedDates = if (status == LibraryStatus.WATCHED && type == MediaType.MOVIE && date != null) listOf(date) else emptyList(),
            ),
        )
    }
}

// ─── Plex (direct to the user's server) ──────────────────────────────────────

data class PlexPin(val id: Long, val code: String, val authUrl: String)
data class PlexServer(val name: String, val uri: String)

class PlexClient(private val http: OkHttpClient, private val clientId: String) {
    private fun builder(url: String, token: String? = null): Request.Builder =
        Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("X-Plex-Product", "CinemArchive")
            .header("X-Plex-Client-Identifier", clientId)
            .apply { token?.let { header("X-Plex-Token", it) } }

    fun startPin(): PlexPin {
        val request = builder("https://plex.tv/api/v2/pins?strong=true").post("".toRequestBody()).build()
        val json = JSONObject(execute(request, "Could not start Plex sign-in."))
        val code = json.getString("code")
        val authUrl = "https://app.plex.tv/auth#?clientID=$clientId&code=$code&context%5Bdevice%5D%5Bproduct%5D=CinemArchive"
        return PlexPin(json.getLong("id"), code, authUrl)
    }

    /** The auth token once the user approves, otherwise null. */
    fun pollPin(pin: PlexPin): String? {
        val json = JSONObject(execute(builder("https://plex.tv/api/v2/pins/${pin.id}?code=${pin.code}").get().build(), "Plex sign-in expired. Start again."))
        return json.optString("authToken").takeIf { it.isNotEmpty() && it != "null" }
    }

    fun servers(token: String): List<PlexServer> {
        val resources = JSONArray(execute(builder("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=0", token).get().build(), "Could not list your Plex servers."))
        return buildList {
            for (i in 0 until resources.length()) {
                val r = resources.getJSONObject(i)
                if (!r.optString("provides").contains("server") || !r.optBoolean("owned")) continue
                val conns = r.optJSONArray("connections") ?: continue
                val https = (0 until conns.length()).map { conns.getJSONObject(it) }.filter { it.optString("protocol") == "https" }
                val best = https.firstOrNull { !it.optBoolean("local") } ?: https.firstOrNull() ?: continue
                add(PlexServer(r.optString("name"), best.getString("uri")))
            }
        }
    }

    fun items(serverUri: String, token: String): List<SyncItem> {
        val base = serverUri.trimEnd('/')
        val sections = JSONObject(execute(builder("$base/library/sections", token).get().build(), "Could not reach your Plex server."))
            .optJSONObject("MediaContainer")?.optJSONArray("Directory") ?: JSONArray()
        val all = JSONArray()
        for (i in 0 until sections.length()) {
            val s = sections.getJSONObject(i)
            if (s.optString("type") != "movie" && s.optString("type") != "show") continue
            val body = runCatching { execute(builder("$base/library/sections/${s.getString("key")}/all?includeGuids=1", token).get().build(), "") }.getOrNull() ?: continue
            val meta = JSONObject(body).optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: continue
            for (j in 0 until meta.length()) all.put(meta.get(j))
        }
        return mapPlexItems(all)
    }

    private fun execute(request: Request, failure: String): String =
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { failure.ifEmpty { "Plex returned ${response.code}." } }
            response.body?.string().orEmpty()
        }
}

/** Only watched or rated items are imported: a Plex library is what the server owns, not
 *  what the user chose to track. */
internal fun mapPlexItems(items: JSONArray): List<SyncItem> = buildList {
    for (i in 0 until items.length()) {
        val m = items.optJSONObject(i) ?: continue
        val kind = m.optString("type")
        if (kind != "movie" && kind != "show") continue
        val type = if (kind == "movie") MediaType.MOVIE else MediaType.TV
        val seen = m.optInt("viewedLeafCount", 0)
        val total = m.optInt("leafCount", 0)
        // A show counts as watched only when every episode is; partly watched is "watching".
        val watched = if (type == MediaType.MOVIE) m.optInt("viewCount", 0) > 0 else total > 0 && seen >= total
        val watching = type == MediaType.TV && !watched && seen > 0
        val rating = ratingFromTen(if (m.has("userRating")) m.optDouble("userRating") else null)
        if (!watched && !watching && rating == null) continue
        val guids = m.optJSONArray("Guid")?.let { g -> (0 until g.length()).map { g.getJSONObject(it).optString("id") } } ?: emptyList()
        val date = if (m.has("lastViewedAt")) toDateOnly(m.optLong("lastViewedAt")) else null
        add(
            SyncItem(
                provider = SyncProvider.PLEX,
                externalId = m.optString("ratingKey"),
                type = type,
                title = m.optString("title"),
                year = if (m.has("year")) m.optInt("year") else null,
                ids = parseGuids(guids),
                status = if (watched) LibraryStatus.WATCHED else if (watching) LibraryStatus.WATCHING else LibraryStatus.WATCHLIST,
                rating = rating,
                watchedDates = if (watched && type == MediaType.MOVIE && date != null) listOf(date) else emptyList(),
            ),
        )
    }
}

// ─── Emby (direct to the user's server) ──────────────────────────────────────

data class EmbySession(val baseUrl: String, val token: String, val userId: String, val username: String)

fun normalizeEmbyUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    require(trimmed.isNotEmpty()) { "Enter your Emby server address." }
    val withScheme = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) trimmed else "https://$trimmed"
    return withScheme.replace(Regex("/emby$", RegexOption.IGNORE_CASE), "")
}

class EmbyClient(private val http: OkHttpClient) {
    private val authHeader = "MediaBrowser Client=\"CinemArchive\", Device=\"Android\", DeviceId=\"cinemarchive-android\", Version=\"1.0\""

    fun signIn(serverUrl: String, username: String, password: String): EmbySession {
        val baseUrl = normalizeEmbyUrl(serverUrl)
        val body = JSONObject().put("Username", username).put("Pw", password).toString()
        val request = Request.Builder()
            .url("$baseUrl/emby/Users/AuthenticateByName")
            .header("X-Emby-Authorization", authHeader)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: java.io.IOException) {
            error("Could not reach that Emby server. It must be reachable over https.")
        }
        response.use {
            check(it.code != 401 && it.code != 403) { "Emby rejected that username or password." }
            check(it.isSuccessful) { "Emby returned ${it.code}." }
            val json = JSONObject(it.body?.string().orEmpty())
            val user = json.getJSONObject("User")
            return EmbySession(baseUrl, json.getString("AccessToken"), user.getString("Id"), user.optString("Name", username))
        }
    }

    fun items(session: EmbySession): List<SyncItem> {
        val url = "${session.baseUrl}/emby/Users/${session.userId}/Items?Recursive=true&IncludeItemTypes=Movie,Series&Fields=ProviderIds,ProductionYear,RecursiveItemCount&EnableUserData=true"
        val request = Request.Builder().url(url).header("X-Emby-Token", session.token).get().build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Emby returned ${response.code}." }
            return mapEmbyItems(JSONObject(response.body?.string().orEmpty()).optJSONArray("Items") ?: JSONArray())
        }
    }
}

internal fun mapEmbyItems(items: JSONArray): List<SyncItem> = buildList {
    for (i in 0 until items.length()) {
        val it = items.optJSONObject(i) ?: continue
        val kind = it.optString("Type")
        if (kind != "Movie" && kind != "Series") continue
        val ud = it.optJSONObject("UserData")
        val type = if (kind == "Movie") MediaType.MOVIE else MediaType.TV
        val watched = ud?.optBoolean("Played") == true || (type == MediaType.MOVIE && (ud?.optInt("PlayCount", 0) ?: 0) > 0)
        // Emby only flags a series Played once every episode is; an unplayed count below the
        // episode total means partly watched.
        val watching = type == MediaType.TV && !watched && ud != null && ud.has("UnplayedItemCount") &&
            it.has("RecursiveItemCount") && ud.optInt("UnplayedItemCount") < it.optInt("RecursiveItemCount")
        val rating = ratingFromTen(if (ud != null && ud.has("Rating")) ud.optDouble("Rating") else null)
        if (!watched && !watching && rating == null) continue
        val p = it.optJSONObject("ProviderIds")
        val date = toDateOnly(ud?.optString("LastPlayedDate")?.takeIf { d -> d.isNotEmpty() })
        add(
            SyncItem(
                provider = SyncProvider.EMBY,
                externalId = it.optString("Id"),
                type = type,
                title = it.optString("Name"),
                year = if (it.has("ProductionYear")) it.optInt("ProductionYear") else null,
                ids = parseGuids(
                    listOf(
                        p?.optString("Tmdb")?.takeIf { v -> v.isNotEmpty() }?.let { v -> "tmdb://$v" },
                        p?.optString("Imdb")?.takeIf { v -> v.isNotEmpty() }?.let { v -> "imdb://$v" },
                        p?.optString("Tvdb")?.takeIf { v -> v.isNotEmpty() }?.let { v -> "tvdb://$v" },
                    ),
                ),
                status = if (watched) LibraryStatus.WATCHED else if (watching) LibraryStatus.WATCHING else LibraryStatus.WATCHLIST,
                rating = rating,
                watchedDates = if (watched && type == MediaType.MOVIE && date != null) listOf(date) else emptyList(),
            ),
        )
    }
}
