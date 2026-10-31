package work.kumarfamilynet.cinemarchive.data

import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.KeepsakeRules
import work.kumarfamilynet.cinemarchive.core.model.PendingKeepsake

/**
 * Where [KeepsakeRepository] remembers photos whose bytes uploaded but whose row did not save, so a
 * retry can finish just the row insert (docs/android-contracts/outing-keepsakes.md). The list is one
 * account's: the app builds one journal per signed-in owner.
 */
interface KeepsakeJournal {
    fun load(): List<PendingKeepsake>
    fun store(pending: List<PendingKeepsake>)
}

/** Forgotten with the process; the default when no durable store is supplied. */
class InMemoryKeepsakeJournal : KeepsakeJournal {
    private var list: List<PendingKeepsake> = emptyList()
    override fun load(): List<PendingKeepsake> = list
    override fun store(pending: List<PendingKeepsake>) { list = pending }
}

/** A journal kept as one JSON string in whatever text store [read] and [write] wrap (the app uses
 *  SharedPreferences). A damaged string, or an entry with an unexpected shape, is ignored. */
class TextKeepsakeJournal(
    private val read: () -> String?,
    private val write: (String?) -> Unit,
) : KeepsakeJournal {
    override fun load(): List<PendingKeepsake> = runCatching {
        val rows = JSONArray(read() ?: return@runCatching emptyList())
        (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val pending = PendingKeepsake(
                id = row.optString("id"),
                outingId = row.optString("outingId"),
                mimeType = row.optString("mimeType"),
                byteLength = row.optLong("byteLength", -1),
            )
            pending.takeIf { ID_SHAPE.matches(it.id) && ID_SHAPE.matches(it.outingId) && it.mimeType in KeepsakeRules.MIME_TYPES && it.byteLength in 1..KeepsakeRules.MAX_BYTES.toLong() }
        }
    }.getOrDefault(emptyList())

    override fun store(pending: List<PendingKeepsake>) {
        if (pending.isEmpty()) {
            write(null)
            return
        }
        val rows = JSONArray()
        pending.forEach { rows.put(JSONObject().put("id", it.id).put("outingId", it.outingId).put("mimeType", it.mimeType).put("byteLength", it.byteLength)) }
        write(rows.toString())
    }
}

private val ID_SHAPE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
