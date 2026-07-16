package work.kumarfamilynet.cinemarchive.data

import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.LedgerWidgetConfig

/**
 * Real upsert of the Ledger layout to `user_prefs.ledger_layout`
 * (docs/android-contracts/ledger.md §4) — a blind last-write-wins overwrite, matching the web
 * app's own behavior exactly (no per-field merge, no version check; ledger.md §4 flags this
 * as a known, accepted concurrency gap, not something Android should "fix"). Session-obtaining
 * is the same seam [SupabaseRemoteMutationWriter] uses — see its kdoc.
 */
class SupabaseLedgerLayoutWriter(private val client: SupabaseRestClient) {
    fun upsertLayout(session: SupabaseSession, widgets: List<LedgerWidgetConfig>) {
        val body = JSONObject()
            .put("user_id", session.userId)
            .put("ledger_layout", JSONArray(widgets.map { it.toJson() }))
        client.upsert("user_prefs", session.accessToken, body.toString(), onConflict = "user_id")
    }

    private fun LedgerWidgetConfig.toJson(): JSONObject {
        val obj = JSONObject().put("id", id).put("panel", panel.raw).put("width", width.raw)
        settings?.let { s ->
            val settingsJson = JSONObject()
            s.timeRange?.let { settingsJson.put("timeRange", it) }
            s.scope?.let { settingsJson.put("scope", it) }
            s.topN?.let { settingsJson.put("topN", it) }
            s.title?.let { settingsJson.put("title", it) }
            obj.put("settings", settingsJson)
        }
        return obj
    }
}
