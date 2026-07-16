package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
 * **locally only** via DataStore, same pattern as [PreferencesRepository]. Syncing this to
 * `user_prefs.ledger_layout` (docs/android-contracts/ledger.md §4) stays blocked on a real
 * `RemoteMutationWriter`, itself blocked on a physical device for Credential Manager auth —
 * see docs/android-implementation-status.md. Every read runs [LedgerLayoutRules.normalize],
 * so a layout written by a future/older app version degrades the same way a server-synced
 * payload would rather than crashing.
 */
class LedgerLayoutRepository(context: Context) {
    private val dataStore = context.ledgerLayoutDataStore
    private val layoutKey = stringPreferencesKey("ledger_layout")

    fun observeLayout(): Flow<List<LedgerWidgetConfig>> = dataStore.data.map { preferences ->
        val stored = preferences[layoutKey]?.let { runCatching { parse(it) }.getOrNull() }
        val normalized = stored?.let { LedgerLayoutRules.normalize(it) }
        normalized?.takeIf { it.isNotEmpty() } ?: LedgerLayoutRules.defaultLedgerWidgets()
    }

    suspend fun setLayout(widgets: List<LedgerWidgetConfig>) {
        dataStore.edit { it[layoutKey] = serialize(widgets) }
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
