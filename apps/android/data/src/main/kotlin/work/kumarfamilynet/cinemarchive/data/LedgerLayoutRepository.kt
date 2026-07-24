package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.LedgerLayoutRules
import work.kumarfamilynet.cinemarchive.core.model.LedgerWidgetConfig
import work.kumarfamilynet.cinemarchive.core.model.LedgerWidgetSettings
import work.kumarfamilynet.cinemarchive.core.model.RawLedgerWidget
import work.kumarfamilynet.cinemarchive.core.model.RawLedgerWidgetSettings

private val Context.ledgerLayoutDataStore by preferencesDataStore(name = "cinemarchive_ledger_layout")

/**
 * The Ledger board's customizable widget layout — add/remove/move/resize/settings — persisted
 * locally via DataStore (source of truth for reads: [observeLayout] never hits the network).
 * [setLayout] additionally fire-and-forgets a push to `user_prefs.ledger_layout`
 * (docs/android-contracts/ledger.md §4) via [SupabaseLedgerLayoutWriter] when signed in —
 * same "optimistic local write, best-effort remote push" stance as the web app's db.ts. No
 * pull-on-sign-in yet (a deliberate, smaller scope cut — see this repository's plan doc).
 * Every read runs [LedgerLayoutRules.normalize], so a layout written by a future/older app
 * version degrades the same way a server-synced payload would rather than crashing.
 *
 * [reconcile] is the pull half of the pull-on-sign-in/launch contract (ledger.md §4) —
 * see [resolveLayoutReconciliation] for the actual merge rule.
 */
class LedgerLayoutRepository(
    context: Context,
    private val authRepository: AuthRepository,
    private val remoteWriter: SupabaseLedgerLayoutWriter,
) {
    private val dataStore = context.ledgerLayoutDataStore
    private val layoutKey = stringPreferencesKey("ledger_layout")

    fun observeLayout(): Flow<List<LedgerWidgetConfig>> = dataStore.data.map { preferences ->
        val stored = preferences[layoutKey]?.let { runCatching { parseLedgerLayoutJson(it) }.getOrNull() }
        val normalized = stored?.let { LedgerLayoutRules.normalize(it) }
        normalized?.takeIf { it.isNotEmpty() } ?: LedgerLayoutRules.defaultLedgerWidgets()
    }

    suspend fun setLayout(widgets: List<LedgerWidgetConfig>) {
        dataStore.edit { it[layoutKey] = serialize(widgets) }
        // Dispatchers.IO: this callback is a blocking OkHttp call, and callers (e.g.
        // LedgerScreen's viewModelScope) run on Main by default — see
        // LibrarySyncRepository.syncNow()'s kdoc for the same NetworkOnMainThreadException risk.
        withContext(Dispatchers.IO) {
            val session = authRepository.currentSession() ?: return@withContext
            runCatching { remoteWriter.upsertLayout(session, widgets) }
        }
    }

    /** Pull half of ledger.md §4's contract. Call on sign-in and on each app-launch
     *  reconciliation pass — never mid-edit (edit mode is a bounded local interaction that
     *  only starts once the app is already running and past this point). Silently returns
     *  without touching local or remote state on a fetch failure (offline, transient error):
     *  an unknown server state must never be treated as "confirmed empty" and blindly pushed
     *  over, nor as "confirmed present" and used to overwrite local. */
    suspend fun reconcile() {
        val session = authRepository.currentSession() ?: return
        val fetchResult = withContext(Dispatchers.IO) { runCatching { remoteWriter.fetchLayoutJson(session) } }
        if (fetchResult.isFailure) return
        val localWidgets = observeLayout().first()
        when (val decision = resolveLayoutReconciliation(fetchResult.getOrNull(), localWidgets)) {
            is LayoutReconciliation.OverwriteLocal -> dataStore.edit { it[layoutKey] = serialize(decision.widgets) }
            is LayoutReconciliation.PushLocal ->
                withContext(Dispatchers.IO) { runCatching { remoteWriter.upsertLayout(session, decision.widgets) } }
        }
    }

    private fun serialize(widgets: List<LedgerWidgetConfig>): String {
        val array = JSONArray()
        widgets.forEach { widget ->
            val obj = JSONObject()
                .put("id", widget.id)
                .put("panel", widget.panel.raw)
                .put("width", widget.width.raw)
            widget.settings?.let { obj.put("settings", it.toJson()) }
            array.put(obj)
        }
        return array.toString()
    }

    private fun LedgerWidgetSettings.toJson(): JSONObject = JSONObject().apply {
        timeRange?.let { put("timeRange", it) }
        scope?.let { put("scope", it) }
        topN?.let { put("topN", it) }
        title?.let { put("title", it) }
    }
}

private fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

private fun parseLedgerLayoutJson(json: String): List<RawLedgerWidget> {
    val array = JSONArray(json)
    return (0 until array.length()).map { i ->
        val obj = array.getJSONObject(i)
        val settingsObj = obj.optJSONObject("settings")
        RawLedgerWidget(
            id = obj.getString("id"),
            panel = obj.getString("panel"),
            width = obj.stringOrNull("width"),
            settings = settingsObj?.let {
                RawLedgerWidgetSettings(
                    timeRange = it.stringOrNull("timeRange"),
                    scope = it.stringOrNull("scope"),
                    topN = if (it.has("topN") && !it.isNull("topN")) it.getInt("topN") else null,
                    title = it.stringOrNull("title"),
                )
            },
        )
    }
}

/** [LedgerLayoutRepository.reconcile]'s pure merge decision, split out for unit testing
 *  without a [Context]/DataStore. A server layout is only trusted when its JSON is present,
 *  parseable, and normalizes to at least one widget — anything else (no row, null column,
 *  corrupt JSON, or a payload that normalizes to nothing because every panel was unknown)
 *  is treated the same as "no server layout yet," matching [LedgerLayoutRepository.observeLayout]'s
 *  own empty-after-normalize fallback posture for corrupt *local* storage. No merge, no
 *  per-field reconciliation — same blind last-write-wins stance as [SupabaseLedgerLayoutWriter.upsertLayout]. */
internal sealed interface LayoutReconciliation {
    data class OverwriteLocal(val widgets: List<LedgerWidgetConfig>) : LayoutReconciliation
    data class PushLocal(val widgets: List<LedgerWidgetConfig>) : LayoutReconciliation
}

internal fun resolveLayoutReconciliation(
    serverLayoutJson: String?,
    localWidgets: List<LedgerWidgetConfig>,
): LayoutReconciliation {
    val serverWidgets = serverLayoutJson
        ?.let { json -> runCatching { LedgerLayoutRules.normalize(parseLedgerLayoutJson(json)) }.getOrNull() }
        ?.takeIf { it.isNotEmpty() }
    return if (serverWidgets != null) {
        LayoutReconciliation.OverwriteLocal(serverWidgets)
    } else {
        LayoutReconciliation.PushLocal(localWidgets)
    }
}
