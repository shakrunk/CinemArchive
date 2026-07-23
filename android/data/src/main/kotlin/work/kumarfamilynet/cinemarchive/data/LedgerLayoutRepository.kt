package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
 */
class LedgerLayoutRepository(
    context: Context,
    private val authRepository: AuthRepository,
    private val remoteWriter: SupabaseLedgerLayoutWriter,
) {
    private val dataStore = context.ledgerLayoutDataStore
    private val layoutKey = stringPreferencesKey("ledger_layout")

    fun observeLayout(): Flow<List<LedgerWidgetConfig>> = dataStore.data.map { preferences ->
        val stored = preferences[layoutKey]?.let { runCatching { parse(it) }.getOrNull() }
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

    private fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    private fun parse(json: String): List<RawLedgerWidget> {
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
