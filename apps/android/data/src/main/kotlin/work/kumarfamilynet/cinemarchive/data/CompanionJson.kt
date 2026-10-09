package work.kumarfamilynet.cinemarchive.data

import org.json.JSONArray
import org.json.JSONObject

/** Names remain the presentation model; the full objects retain optional friend identities. */
internal fun companionNames(values: JSONArray): List<String> = (0 until values.length()).map { index ->
    when (val value = values.get(index)) {
        is String -> value
        is JSONObject -> value.get("name").also { require(it is String) } as String
        else -> error("Invalid companion")
    }
}

internal fun companionObjects(rawJson: String?, names: List<String>): JSONArray {
    if (rawJson == null) return JSONArray().apply { names.forEach { put(JSONObject().put("name", it)) } }
    val values = JSONArray(rawJson)
    companionNames(values) // Validate the authoritative array, including names that contain the old Room delimiter.
    return JSONArray().apply { for (index in 0 until values.length()) {
        val value = values.get(index)
        put(if (value is String) JSONObject().put("name", value) else value)
    } }
}

/** Preserve unknown provenance on an unrelated edit. Actual name edits retain matching
 * occurrences in order (including distinct friends with the same name); new names have no link.
 * Never look up current server values when constructing an older captured edit.
 */
internal fun retainCompanionsJson(rawJson: String?, oldNames: List<String>, newNames: List<String>): String? {
    if (oldNames == newNames) return rawJson
    require(rawJson != null || oldNames.none { it in newNames }) {
        "Sync before changing these companions so their friend links can be preserved. Your draft is still here."
    }
    val before = companionObjects(rawJson, oldNames)
    val actualNames = companionNames(before)
    if (actualNames == newNames) return rawJson
    val remaining = (0 until before.length()).toMutableList()
    return JSONArray().apply { newNames.forEach { name ->
            val match = remaining.firstOrNull { actualNames[it] == name }
        put(if (match == null) JSONObject().put("name", name) else before.getJSONObject(match))
        if (match != null) remaining.remove(match)
    } }.toString()
}

internal fun savedCompanionNames(rawJson: String?, fallback: List<String>): List<String> =
    rawJson?.let { companionNames(JSONArray(it)) } ?: fallback
