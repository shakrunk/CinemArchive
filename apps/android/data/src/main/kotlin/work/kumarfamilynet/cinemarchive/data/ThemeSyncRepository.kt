package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.SyncedAppearance
import work.kumarfamilynet.cinemarchive.core.model.applySyncedAppearance
import work.kumarfamilynet.cinemarchive.core.model.archivePaletteForSyncId
import work.kumarfamilynet.cinemarchive.core.model.archiveThemeModeForSyncId
import work.kumarfamilynet.cinemarchive.core.model.syncId
import java.time.Instant

/** A validated `user_prefs.theme_prefs` payload (docs/android-contracts/theme-sync.md). */
data class SyncedThemePrefs(val appearance: SyncedAppearance, val updatedAt: Instant)

/** Rejects the whole payload on any missing field or unknown vocabulary (e.g. from a newer
 *  client) rather than half-applying it. */
fun parseSyncedThemePrefs(json: JSONObject): SyncedThemePrefs? {
    val palette = (json.opt("palette") as? String)?.let(::archivePaletteForSyncId) ?: return null
    val mode = (json.opt("mode") as? String)?.let(::archiveThemeModeForSyncId) ?: return null
    val pureBlack = json.opt("pureBlack") as? Boolean ?: return null
    val updatedAt = (json.opt("updatedAt") as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    return SyncedThemePrefs(SyncedAppearance(palette, mode, pureBlack), updatedAt)
}

fun SyncedAppearance.toThemePrefsJson(updatedAt: String): JSONObject = JSONObject()
    .put("palette", palette.syncId)
    .put("mode", mode.syncId)
    .put("pureBlack", pureBlack)
    .put("updatedAt", updatedAt)

/**
 * Opt-in appearance sync for one signed-in account, modelled on [LedgerLayoutRepository]:
 * [PreferencesRepository] stays the source of truth for reads, and the remote copy is reconciled
 * last-write-wins on its `updatedAt`. A failed fetch, a missing `theme_prefs` column or an
 * invalid payload leaves both sides untouched; a failed push is retried by the next reconcile.
 */
class ThemeSyncRepository(
    private val preferences: PreferencesRepository,
    private val session: SessionSource,
    private val client: SupabaseRestClient,
    private val isCurrent: () -> Boolean,
) {
    // The stamp last pushed or applied here, so applying a remote copy never echoes back.
    @Volatile private var lastSyncedAt: String? = null

    /** Call on sign-in/launch and when the user opts in. */
    suspend fun reconcile() {
        if (!preferences.appearanceSnapshot().syncEnabled) return
        val active = session.currentSession() ?: return
        val raw = withContext(Dispatchers.IO) {
            runCatching {
                val rows = JSONArray(client.get("user_prefs", "user_id=eq.${active.userId}&select=theme_prefs", active.accessToken))
                val row = rows.optJSONObject(0)
                if (row == null || row.isNull("theme_prefs")) null else row.getJSONObject("theme_prefs")
            }
        }
        if (raw.isFailure || !isCurrent()) return
        val json = raw.getOrNull()
        val remote = json?.let(::parseSyncedThemePrefs)
        if (json != null && remote == null) return
        val local = preferences.appearanceSnapshot()
        val localAt = local.updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        when {
            remote != null && (localAt == null || remote.updatedAt.isAfter(localAt)) -> {
                val next = applySyncedAppearance(remote.appearance, local.appearance, local.unlocked)
                val stamp = remote.updatedAt.toString()
                lastSyncedAt = stamp
                preferences.applySyncedAppearance(next, stamp)
            }
            remote == null || (localAt != null && localAt.isAfter(remote.updatedAt)) -> push()
        }
    }

    /** Pushes each user-made appearance change and reconciles when sync is switched on. Runs
     *  for the life of the account runtime's scope. */
    suspend fun watchLocalChanges() {
        coroutineScope {
            launch {
                preferences.observeThemeSyncEnabled().distinctUntilChanged().drop(1).filter { it }.collect { reconcile() }
            }
            launch {
                preferences.observeThemeUpdatedAt().distinctUntilChanged().drop(1).collect { stamp ->
                    if (stamp != null && stamp != lastSyncedAt && preferences.appearanceSnapshot().syncEnabled) push()
                }
            }
        }
    }

    private suspend fun push() {
        val active = session.currentSession() ?: return
        val stamp = preferences.stampAppearanceIfUnset()
        lastSyncedAt = stamp
        val appearance = preferences.appearanceSnapshot().appearance
        val body = JSONObject().put("user_id", active.userId).put("theme_prefs", appearance.toThemePrefsJson(stamp))
        withContext(Dispatchers.IO) {
            runCatching { client.upsert("user_prefs", active.accessToken, body.toString(), onConflict = "user_id") }
        }
    }
}
