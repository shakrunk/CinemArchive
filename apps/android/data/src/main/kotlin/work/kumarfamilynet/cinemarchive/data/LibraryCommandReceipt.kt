package work.kumarfamilynet.cinemarchive.data

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Validate immutable command identity and each ordered result before a domain may ACK it. */
internal fun checkedLibraryCommandReceipt(
    operationId: String,
    operations: JSONArray,
    receipt: JSONObject,
    ownerId: String,
): List<JSONObject> {
    require(receipt.getString("operationId") == operationId) { "Command receipt identity does not match" }
    val rows = receipt.getJSONArray("rows")
    require(rows.length() == operations.length()) { "Command receipt is incomplete" }
    val identities = mutableMapOf<Pair<String, String>, JSONObject>()
    return (0 until rows.length()).map { index ->
        val expected = operations.getJSONObject(index)
        val result = rows.getJSONObject(index)
        require(result.getString("table") == expected.getString("table") &&
            sameCommandJson(result.getJSONObject("key"), expected.getJSONObject("key"))) { "Command receipt changed an entity identity" }
        if (expected.getString("action") == "delete") {
            require(result.optBoolean("deleted") && !result.has("row")) { "Command deletion was not confirmed" }
        } else {
            require(!result.optBoolean("deleted")) { "Command receipt unexpectedly deleted a row" }
            val row = result.getJSONObject("row")
            require(row.getString("user_id") == ownerId) { "Command receipt belongs to another account" }
            val key = expected.getJSONObject("key")
            key.keys().forEach { field ->
                require(sameCommandJson(key.get(field), row.opt(field))) { "Command receipt changed $field" }
            }
            val values = expected.optJSONObject("values") ?: JSONObject()
            listOf("title_id", "season_id", "episode_id", "season_number", "episode_number").filter(values::has).forEach { field ->
                require(sameCommandJson(values.get(field), row.opt(field))) { "Command receipt changed parent $field" }
            }
            if (row.has("id")) {
                val id = row.getString("id")
                require(UUID.fromString(id).toString() == id.lowercase()) { "Invalid canonical row ID" }
                val previous = identities.put(result.getString("table") to id, key)
                require(previous == null || sameCommandJson(previous, key)) { "Command receipt reused a row ID" }
            }
        }
        result
    }
}

internal fun sameCommandJson(a: Any?, b: Any?): Boolean = when {
    a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet() == b.keys().asSequence().toSet() &&
        a.keys().asSequence().all { sameCommandJson(a.get(it), b.get(it)) }
    a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { sameCommandJson(a.get(it), b.get(it)) }
    a is Number && b is Number -> a.toString().toBigDecimal().compareTo(b.toString().toBigDecimal()) == 0
    else -> a == b
}
