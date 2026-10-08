package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

/** Receipt values may be historical. Only canonical identities are taken from them. */
class CreditReceiptApplier(private val database: LibraryDatabase, private val ownerId: String) : AppliedMutationHandler {
    /** Earlier receipts can rekey a row while a later immutable refresh still names its old ID. */
    suspend fun protectionKeys(entries: List<OutboxEntity>): Set<String> = buildSet {
        entries.filter { it.entityType == "title_credits" }.map { it.entityId }.distinct().forEach { titleId ->
            readCreditRows(database, titleId).forEach { add("${it.table}:${it.id}") }
        }
    }

    override suspend fun apply(entry: OutboxEntity, receipt: JSONObject) {
        require(entry.entityType == "title_credits" && entry.operation == "refresh")
        val payload = JSONObject(entry.payloadJson)
        require(payload.getString("ownerId") == ownerId && payload.getString("titleId") == entry.entityId)
        val results = checkedLibraryCommandReceipt(entry.id, payload.getJSONArray("operations"), receipt, ownerId)
        val current = readCreditRows(database, entry.entityId)
        results.filter { it.getString("table") != "titles" && !it.optBoolean("deleted") }.forEach { result ->
            val row = current.firstOrNull { it.table == result.getString("table") && sameCommandJson(it.key(), result.getJSONObject("key")) }
                ?: return@forEach // A later local refresh/delete removed it. Never resurrect it.
            val canonicalId = result.getJSONObject("row").getString("id")
            if (row.id != canonicalId) {
                deleteCreditRow(database, row)
                writeCreditRows(database, listOf(row.copy(id = canonicalId)))
            }
        }
    }
}
