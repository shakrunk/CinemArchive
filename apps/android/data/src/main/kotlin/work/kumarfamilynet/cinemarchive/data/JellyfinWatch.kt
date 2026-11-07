package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device-local state for the Jellyfin watch prompt — the Kotlin counterpart of
 * `jellyfinPending.ts` + `jellyfinWatch.ts`: the signed-in session, the "checked up to" cursor, and
 * the plays awaiting the user's yes/no. Namespaced by CinemArchive user id so switching accounts
 * never shows or uses another account's Jellyfin session.
 */
data class JellyfinState(
    val session: JellyfinSession?,
    /** ISO timestamp; only plays after this are prompted. Set to "now" on connect. */
    val cursor: String,
    val pending: List<JellyfinPlay>,
    /** The server rejected the stored token on the last check. */
    val authExpired: Boolean = false,
)

/** Merge newly seen plays into the queue, skipping ones already queued. The cursor moves to the newest seen play. */
internal fun queuePlays(state: JellyfinState, plays: List<JellyfinPlay>): JellyfinState {
    if (plays.isEmpty()) return state
    val known = state.pending.mapTo(HashSet()) { it.key }
    val fresh = plays.filter { known.add(it.key) }
    var newest = Instant.parse(state.cursor)
    for (p in plays) Instant.parse(p.playedAt).let { if (it.isAfter(newest)) newest = it }
    return state.copy(cursor = newest.toString(), pending = state.pending + fresh)
}

internal fun resolvePlay(state: JellyfinState, playKey: String): JellyfinState =
    state.copy(pending = state.pending.filter { it.key != playKey })

// ─── Persistence ─────────────────────────────────────────────────────────────

internal fun JellyfinState.toJson(): String = JSONObject().apply {
    put("cursor", cursor)
    put("authExpired", authExpired)
    session?.let {
        put("session", JSONObject().put("baseUrl", it.baseUrl).put("token", it.token).put("userId", it.userId).put("username", it.username))
    }
    put("pending", JSONArray().also { arr -> pending.forEach { arr.put(it.toJson()) } })
}.toString()

internal fun jellyfinStateFromJson(raw: String?): JellyfinState? = runCatching {
    val o = JSONObject(raw ?: return null)
    val cursor = o.getString("cursor").also { Instant.parse(it) }
    val pending = o.getJSONArray("pending").let { arr -> (0 until arr.length()).map { playFromJson(arr.getJSONObject(it)) } }
    val session = o.optJSONObject("session")?.let {
        JellyfinSession(it.getString("baseUrl"), it.getString("token"), it.getString("userId"), it.getString("username"))
    }
    JellyfinState(session, cursor, pending, o.optBoolean("authExpired"))
}.getOrNull()

private fun JellyfinPlay.toJson() = JSONObject().apply {
    put("key", key); put("kind", kind.name); put("title", title)
    year?.let { put("year", it) }
    ids.tmdb?.let { put("tmdb", it) }; ids.imdb?.let { put("imdb", it) }; ids.tvdb?.let { put("tvdb", it) }
    seasonNumber?.let { put("seasonNumber", it) }; episodeNumber?.let { put("episodeNumber", it) }
    episodeName?.let { put("episodeName", it) }
    put("date", date); put("playedAt", playedAt)
}

private fun playFromJson(o: JSONObject) = JellyfinPlay(
    key = o.getString("key"),
    kind = JellyfinPlay.Kind.valueOf(o.getString("kind")),
    title = o.getString("title"),
    year = if (o.has("year")) o.getInt("year") else null,
    ids = ExternalIds(
        tmdb = if (o.has("tmdb")) o.getInt("tmdb") else null,
        imdb = o.optString("imdb").takeIf { it.isNotEmpty() },
        tvdb = if (o.has("tvdb")) o.getInt("tvdb") else null,
    ),
    seasonNumber = if (o.has("seasonNumber")) o.getInt("seasonNumber") else null,
    episodeNumber = if (o.has("episodeNumber")) o.getInt("episodeNumber") else null,
    episodeName = o.optString("episodeName").takeIf { it.isNotEmpty() },
    date = o.getString("date"),
    playedAt = o.getString("playedAt"),
)

/** Persistence seam, split out so [JellyfinWatch] is unit-testable without an Android keystore. */
interface JellyfinStore {
    fun read(): JellyfinState?
    fun write(state: JellyfinState)
    fun clear()
}

class InMemoryJellyfinStore : JellyfinStore {
    private var state: JellyfinState? = null
    override fun read() = state
    override fun write(state: JellyfinState) { this.state = state }
    override fun clear() { state = null }
}

/** The access token is a credential, so the whole state lives in [EncryptedSharedPreferences],
 *  one file per CinemArchive account. */
class EncryptedJellyfinStore(context: Context, userId: String) : JellyfinStore {
    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            "cinemarchive_jellyfin_${OwnerNamespace.key(userId)}",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun read(): JellyfinState? = jellyfinStateFromJson(prefs.getString(KEY, null))
    override fun write(state: JellyfinState) { prefs.edit().putString(KEY, state.toJson()).apply() }
    override fun clear() { prefs.edit().clear().apply() }

    private companion object { const val KEY = "state" }
}

// ─── Runtime ─────────────────────────────────────────────────────────────────

/** What the UI renders: the plays awaiting an answer and whether the user must sign in again. */
data class JellyfinView(
    val connected: Boolean = false,
    val pending: List<JellyfinPlay> = emptyList(),
    val authExpired: Boolean = false,
)

/**
 * Polls the user's server for new plays, queues them in device storage, and exposes the queue.
 * A play only leaves the queue when the user answers it. One instance per account runtime; the
 * background worker, the settings screen and the notification actions all go through it, and a
 * mutex serializes their reads and writes of the stored state.
 */
class JellyfinWatch(
    private val store: JellyfinStore,
    private val client: JellyfinClient,
    private val isCurrent: () -> Boolean,
    /** Called with true once a session exists and false after it is removed (schedules the poll). */
    private val onConnectionChanged: (Boolean) -> Unit = {},
    private val now: () -> Instant = Instant::now,
) {
    private val lock = Mutex()
    private var state: JellyfinState? = null
    private var loaded = false
    private val _view = MutableStateFlow(JellyfinView())
    val view: StateFlow<JellyfinView> = _view.asStateFlow()

    private fun fence() { if (!isCurrent()) throw CancellationException("Account changed.") }

    private fun publish() {
        val s = state
        _view.value = JellyfinView(connected = s?.session != null, pending = s?.pending.orEmpty(), authExpired = s?.authExpired == true)
    }

    private fun ensureLoaded() {
        if (loaded) return
        state = store.read()
        loaded = true
        publish()
    }

    private fun persist(next: JellyfinState) {
        state = next
        store.write(next)
        publish()
    }

    /** Loads the stored state into [view]; safe to call repeatedly. */
    suspend fun load() = withContext(Dispatchers.IO) { lock.withLock { ensureLoaded() } }

    suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) { lock.withLock { ensureLoaded(); state?.session != null } }

    /** The plays currently awaiting an answer, oldest first. */
    suspend fun pending(): List<JellyfinPlay> = withContext(Dispatchers.IO) { lock.withLock { ensureLoaded(); state?.pending.orEmpty() } }

    suspend fun signIn(serverUrl: String, username: String, password: String): JellyfinSession =
        withContext(Dispatchers.IO) { client.signIn(serverUrl, username, password) }

    /**
     * Stores a fresh [session]. A first connection starts watching from "now" (history before
     * connecting is never prompted for); [alreadyConnected] replaces an expired session without
     * moving the cursor, so missed plays still prompt.
     */
    suspend fun connect(session: JellyfinSession, alreadyConnected: Boolean) = withContext(Dispatchers.IO) {
        lock.withLock {
            fence()
            ensureLoaded()
            val current = state
            state = if (alreadyConnected && current != null) {
                current.copy(session = session, authExpired = false)
            } else {
                JellyfinState(session, now().toString(), current?.pending.orEmpty())
            }
            persist(checkNotNull(state))
        }
        onConnectionChanged(true)
    }

    /** Signs out of Jellyfin (best effort) and forgets the session, cursor and queue. */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        val session = lock.withLock {
            ensureLoaded()
            val s = state?.session
            store.clear()
            state = null
            publish()
            s
        }
        onConnectionChanged(false)
        session?.let { client.signOut(it) }
    }

    /**
     * Asks the server for plays since the cursor and queues them. Returns only the plays that were
     * newly queued, so callers can announce each exactly once. Failures other than an expired
     * token (server asleep, away from home) just try again at the next check.
     */
    suspend fun check(): List<JellyfinPlay> = withContext(Dispatchers.IO) {
        val (session, cursor) = lock.withLock {
            fence()
            ensureLoaded()
            val current = state
            val s = current?.session ?: return@withContext emptyList()
            s to current.cursor
        }
        // The request runs outside the lock so answering a play never waits on the network.
        try {
            val plays = client.fetchPlays(session, cursor)
            lock.withLock {
                fence()
                // The account may have been disconnected or replaced while the request was in flight.
                val latest = state?.takeIf { it.session == session } ?: return@withContext emptyList()
                val known = latest.pending.mapTo(HashSet()) { it.key }
                val fresh = plays.filter { it.key !in known }
                persist(queuePlays(latest, plays).copy(authExpired = false))
                fresh
            }
        } catch (e: JellyfinAuthException) {
            lock.withLock {
                state?.takeIf { it.session == session }?.let { persist(it.copy(authExpired = true)) }
            }
            emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Removes a play from the queue once the user has answered it. */
    suspend fun resolve(playKey: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            fence()
            ensureLoaded()
            state?.let { persist(resolvePlay(it, playKey)) }
        }
    }

    /** One-time full-history read for the optional import. */
    suspend fun fetchLibrary(session: JellyfinSession): List<SyncItem> =
        withContext(Dispatchers.IO) { client.fetchLibrary(session) }
}
