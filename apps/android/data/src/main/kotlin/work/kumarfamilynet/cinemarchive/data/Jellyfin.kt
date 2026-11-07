package work.kumarfamilynet.cinemarchive.data

import java.io.IOException
import java.time.Instant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaType

/**
 * Jellyfin adapter — the Kotlin counterpart of `apps/web/src/lib/sync/jellyfin.ts`. Runs on the
 * phone against the user's own server.
 *
 * Auth is a per-user username/password sign-in (`AuthenticateByName`), not a server API key, so
 * every request is scoped to that one Jellyfin account by construction: the access token can only
 * see that user's play state. The password is sent only to the user's own server and never stored;
 * the resulting access token stays on this device (revocable from the Jellyfin dashboard).
 *
 * Unlike the one-shot Emby/Plex imports, Jellyfin is also polled for plays that happen after
 * connecting. Those are never written to the library on their own: they are queued as
 * [JellyfinPlay]s for the user to confirm (see [JellyfinWatch]). The `map*` functions are pure
 * and unit-tested.
 */

/** Adds `https://` when missing and trims trailing slashes; keeps a base path (`/jellyfin`). */
fun normalizeJellyfinUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    require(trimmed.isNotEmpty()) { "Enter your Jellyfin server address." }
    return if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) trimmed else "https://$trimmed"
}

data class JellyfinSession(val baseUrl: String, val token: String, val userId: String, val username: String)

/** The server rejected the stored token: the user has to sign in again. */
class JellyfinAuthException : IOException("Your Jellyfin session expired. Sign in again to keep checking for new watches.")

/** One finished watch reported by Jellyfin, waiting for the user to confirm it. */
data class JellyfinPlay(
    /** `<itemId>@<lastPlayedDate>` — stable per play, so a re-watch (new date) prompts again. */
    val key: String,
    val kind: Kind,
    /** Movie title, or the series name for an episode. */
    val title: String,
    val year: Int? = null,
    /** Ids of the movie, or of the series for an episode. */
    val ids: ExternalIds = ExternalIds(),
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeName: String? = null,
    /** `YYYY-MM-DD` (UTC), the day Jellyfin recorded the play. */
    val date: String,
    /** Full ISO timestamp; drives the "newer than the cursor" check. */
    val playedAt: String,
) {
    enum class Kind { MOVIE, EPISODE }

    /** Jellyfin item id, the part of [key] before the `@`. */
    val itemId: String get() = key.substringBefore('@')

    /** Human line for prompts and notifications: "Heat (1995)" / "Severance S1E1 · Hello". */
    fun describe(): String = when (kind) {
        Kind.MOVIE -> year?.let { "$title ($it)" } ?: title
        Kind.EPISODE -> "$title S$seasonNumber" + "E$episodeNumber" + (episodeName?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
    }
}

// ─── Mapping (pure) ──────────────────────────────────────────────────────────

private fun idsFrom(providerIds: JSONObject?): ExternalIds {
    // Jellyfin keys are case-inconsistent across versions/plugins ("Tmdb", "tmdb").
    fun get(k: String): String? =
        providerIds?.optString(k)?.takeIf { it.isNotEmpty() } ?: providerIds?.optString(k.lowercase())?.takeIf { it.isNotEmpty() }
    return parseGuids(
        listOf(
            get("Tmdb")?.let { "tmdb://$it" },
            get("Imdb")?.let { "imdb://$it" },
            get("Tvdb")?.let { "tvdb://$it" },
        ),
    )
}

private fun JSONObject.intOrNull(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null

/** Plays newer than [sinceIso], oldest first. [seriesIds] maps a Jellyfin series id to its provider ids. */
internal fun mapJellyfinPlays(items: JSONArray, seriesIds: Map<String, ExternalIds>, sinceIso: String): List<JellyfinPlay> {
    val since = runCatching { Instant.parse(sinceIso) }.getOrNull() ?: Instant.EPOCH
    val out = mutableListOf<JellyfinPlay>()
    for (n in 0 until items.length()) {
        val i = items.optJSONObject(n) ?: continue
        val type = i.optString("Type")
        if (type != "Movie" && type != "Episode") continue
        val ud = i.optJSONObject("UserData") ?: continue
        val rawDate = ud.optString("LastPlayedDate").takeIf { it.isNotEmpty() } ?: continue
        if (!ud.optBoolean("Played")) continue
        val playedAt = runCatching { Instant.parse(rawDate) }.getOrNull() ?: continue
        if (!playedAt.isAfter(since)) continue
        val date = toDateOnly(rawDate) ?: continue
        val key = "${i.optString("Id")}@$rawDate"
        if (type == "Movie") {
            out += JellyfinPlay(
                key = key, kind = JellyfinPlay.Kind.MOVIE, title = i.optString("Name"),
                year = i.intOrNull("ProductionYear"), ids = idsFrom(i.optJSONObject("ProviderIds")),
                date = date, playedAt = playedAt.toString(),
            )
        } else {
            val season = i.intOrNull("ParentIndexNumber") ?: continue
            val episode = i.intOrNull("IndexNumber") ?: continue
            val seriesId = i.optString("SeriesId").takeIf { it.isNotEmpty() }
            out += JellyfinPlay(
                key = key, kind = JellyfinPlay.Kind.EPISODE,
                title = i.optString("SeriesName").takeIf { it.isNotEmpty() } ?: i.optString("Name"),
                ids = seriesId?.let { seriesIds[it] } ?: ExternalIds(),
                seasonNumber = season, episodeNumber = episode, episodeName = i.optString("Name"),
                date = date, playedAt = playedAt.toString(),
            )
        }
    }
    return out.sortedBy { Instant.parse(it.playedAt) }
}

/** Full-history import (movies and series), same shape the Emby/Plex imports use. */
internal fun mapJellyfinItems(items: JSONArray): List<SyncItem> = buildList {
    for (n in 0 until items.length()) {
        val i = items.optJSONObject(n) ?: continue
        val kind = i.optString("Type")
        if (kind != "Movie" && kind != "Series") continue
        val ud = i.optJSONObject("UserData")
        val isMovie = kind == "Movie"
        val watched = ud?.optBoolean("Played") == true || (isMovie && (ud?.optInt("PlayCount", 0) ?: 0) > 0)
        val watching = !isMovie && !watched && ud != null && ud.has("UnplayedItemCount") && i.has("RecursiveItemCount") &&
            ud.optInt("UnplayedItemCount") < i.optInt("RecursiveItemCount")
        if (!watched && !watching) continue
        val date = toDateOnly(ud?.optString("LastPlayedDate")?.takeIf { it.isNotEmpty() })
        add(
            SyncItem(
                provider = SyncProvider.JELLYFIN,
                externalId = i.optString("Id"),
                type = if (isMovie) MediaType.MOVIE else MediaType.TV,
                title = i.optString("Name"),
                year = i.intOrNull("ProductionYear"),
                ids = idsFrom(i.optJSONObject("ProviderIds")),
                status = if (watched) LibraryStatus.WATCHED else LibraryStatus.WATCHING,
                rating = null,
                watchedDates = if (watched && isMovie && date != null) listOf(date) else emptyList(),
            ),
        )
    }
}

// ─── HTTP client ─────────────────────────────────────────────────────────────

class JellyfinClient(private val http: OkHttpClient, private val deviceId: String) {
    private fun authHeader(token: String? = null): String {
        val base = "MediaBrowser Client=\"CinemArchive\", Device=\"Android\", DeviceId=\"$deviceId\", Version=\"1.0\""
        return if (token != null) "$base, Token=\"$token\"" else base
    }

    fun signIn(serverUrl: String, username: String, password: String): JellyfinSession {
        val baseUrl = normalizeJellyfinUrl(serverUrl)
        val body = JSONObject().put("Username", username).put("Pw", password).toString()
        val request = Request.Builder()
            .url("$baseUrl/Users/AuthenticateByName")
            .header("Authorization", authHeader())
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            error("Could not reach that Jellyfin server. It must be reachable over https.")
        }
        response.use {
            check(it.code != 401 && it.code != 403) { "Jellyfin rejected that username or password." }
            check(it.isSuccessful) { "Jellyfin returned ${it.code}." }
            val json = JSONObject(it.body?.string().orEmpty())
            val token = json.optString("AccessToken")
            val user = json.optJSONObject("User")
            check(token.isNotEmpty() && user != null && user.optString("Id").isNotEmpty()) {
                "Jellyfin returned an unexpected sign-in response."
            }
            return JellyfinSession(baseUrl, token, user.getString("Id"), user.optString("Name").ifEmpty { username })
        }
    }

    /** Best-effort token revocation on disconnect; the local copy is dropped regardless. */
    fun signOut(session: JellyfinSession) {
        runCatching {
            val request = Request.Builder()
                .url("${session.baseUrl}/Sessions/Logout")
                .header("Authorization", authHeader(session.token))
                .post(ByteArray(0).toRequestBody())
                .build()
            http.newCall(request).execute().close()
        }
    }

    private fun get(session: JellyfinSession, path: String, params: Map<String, String>): JSONObject {
        val url = "${session.baseUrl}$path".toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(url).header("Authorization", authHeader(session.token)).get().build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            error("Could not reach your Jellyfin server.")
        }
        response.use {
            if (it.code == 401) throw JellyfinAuthException()
            check(it.isSuccessful) { "Jellyfin returned ${it.code}." }
            return JSONObject(it.body?.string().orEmpty())
        }
    }

    /** Movies and episodes this account finished after [sinceIso]. */
    fun fetchPlays(session: JellyfinSession, sinceIso: String): List<JellyfinPlay> {
        val body = get(
            session, "/Users/${session.userId}/Items",
            mapOf(
                "Recursive" to "true",
                "IncludeItemTypes" to "Movie,Episode",
                "Filters" to "IsPlayed",
                "SortBy" to "DatePlayed",
                "SortOrder" to "Descending",
                "Limit" to RECENT_LIMIT.toString(),
                "Fields" to "ProviderIds,ProductionYear",
                "EnableUserData" to "true",
            ),
        )
        val items = body.optJSONArray("Items") ?: JSONArray()
        val wanted = LinkedHashSet<String>()
        for (n in 0 until items.length()) {
            val i = items.optJSONObject(n) ?: continue
            if (i.optString("Type") == "Episode") i.optString("SeriesId").takeIf { it.isNotEmpty() }?.let { wanted += it }
        }
        val seriesIds = mutableMapOf<String, ExternalIds>()
        if (wanted.isNotEmpty()) {
            val series = get(session, "/Users/${session.userId}/Items", mapOf("Ids" to wanted.joinToString(","), "Fields" to "ProviderIds"))
            val rows = series.optJSONArray("Items") ?: JSONArray()
            for (n in 0 until rows.length()) {
                val s = rows.optJSONObject(n) ?: continue
                seriesIds[s.optString("Id")] = idsFrom(s.optJSONObject("ProviderIds"))
            }
        }
        return mapJellyfinPlays(items, seriesIds, sinceIso)
    }

    /** Full-history import (movies and series). */
    fun fetchLibrary(session: JellyfinSession): List<SyncItem> {
        val body = get(
            session, "/Users/${session.userId}/Items",
            mapOf(
                "Recursive" to "true",
                "IncludeItemTypes" to "Movie,Series",
                "Fields" to "ProviderIds,ProductionYear,RecursiveItemCount",
                "EnableUserData" to "true",
            ),
        )
        return mapJellyfinItems(body.optJSONArray("Items") ?: JSONArray())
    }

    private companion object {
        const val RECENT_LIMIT = 100
    }
}
