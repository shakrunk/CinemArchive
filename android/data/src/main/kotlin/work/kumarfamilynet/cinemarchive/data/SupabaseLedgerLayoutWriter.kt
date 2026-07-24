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

    /** Reads `user_prefs.ledger_layout` back — the pull half of ledger.md §4's contract.
     *  Returns null when no row exists yet or the column itself is null (server has never
     *  synced a layout for this user), which [resolveLayoutReconciliation] treats the same
     *  as "push the client's current layout up." */
    fun fetchLayoutJson(session: SupabaseSession): String? {
        val rows = JSONArray(
            client.get("user_prefs", "user_id=eq.${session.userId}&select=ledger_layout", session.accessToken),
        )
        if (rows.length() == 0) return null
        val row = rows.getJSONObject(0)
        return if (row.isNull("ledger_layout")) null else row.getJSONArray("ledger_layout").toString()
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
