package work.kumarfamilynet.cinemarchive.data

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.KeepsakeRules
import work.kumarfamilynet.cinemarchive.core.model.OutingKeepsake

/**
 * Keepsake photos for cinema outings (#208, docs/android-contracts/outing-keepsakes.md). Unlike
 * tickets this is online-only: there is no offline queue, so a photo is uploaded and its row
 * inserted while connected, and every call fails with a clear error when it cannot reach the
 * account. Each call is fenced to the signed-in owner this repository was built for.
 */
class KeepsakeRepository(
    private val client: SupabaseRestClient,
    private val session: () -> SupabaseSession?,
    private val ownerId: String,
    private val isCurrent: () -> Boolean,
) {
    suspend fun list(outingId: String): List<OutingKeepsake> = withContext(Dispatchers.IO) {
        val token = token()
        parseKeepsakes(client.get("outing_keepsakes", "outing_id=eq.${checkedUuid(outingId)}&select=*&order=created_at.asc", token))
            .also { token() }
    }

    /** Uploads [bytes] (already downscaled), then records them against [outingId]. */
    suspend fun add(outingId: String, bytes: ByteArray, mimeType: String): OutingKeepsake = withContext(Dispatchers.IO) {
        require(KeepsakeRules.isValidPhoto(mimeType, bytes.size)) { "That photo can't be kept: use a JPEG, PNG or WebP under 10 MB." }
        val token = token()
        val id = UUID.randomUUID().toString()
        val key = KeepsakeRules.objectKey(ownerId, id)
        client.storageUpload(BUCKET, key, bytes, mimeType, token)
        token()
        val row = JSONObject()
            .put("id", id).put("user_id", ownerId).put("outing_id", checkedUuid(outingId))
            .put("object_key", key).put("mime_type", mimeType).put("byte_length", bytes.size)
        // A failed insert leaves an unreferenced object; the daily cleanup removes it after a day.
        parseKeepsakes(client.insert("outing_keepsakes", token, JSONArray().put(row).toString())).single().also { token() }
    }

    /** Deletes the row, which makes the bytes unreadable at once; cleanup removes them later. */
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val token = token()
        client.delete("outing_keepsakes", "id=eq.${checkedUuid(id)}&user_id=eq.$ownerId", token)
        token()
        Unit
    }

    suspend fun download(keepsake: OutingKeepsake): ByteArray = withContext(Dispatchers.IO) {
        val token = token()
        val bytes = client.storageDownload(BUCKET, KeepsakeRules.objectKey(ownerId, checkedUuid(keepsake.id)), token)
        token()
        require(bytes.size.toLong() == keepsake.byteLength) { "The downloaded photo has an unexpected size." }
        bytes
    }

    /** The signed-in owner's token, re-checked before and after every request. */
    private fun token(): String {
        check(isCurrent()) { "This sign-in has ended." }
        val current = session()
        check(current != null && current.userId == ownerId) { "Sign in to keep photos with your outings." }
        return current.accessToken
    }

    private companion object {
        const val BUCKET = "outing-keepsakes"
    }
}

private val UUID_SHAPE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

private fun checkedUuid(value: String): String {
    require(UUID_SHAPE.matches(value)) { "Invalid id" }
    return value
}

internal fun parseKeepsakes(body: String): List<OutingKeepsake> {
    val rows = JSONArray(body)
    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val mime = row.optString("mime_type")
        val length = row.optLong("byte_length", -1)
        if (mime !in KeepsakeRules.MIME_TYPES || length !in 1..KeepsakeRules.MAX_BYTES.toLong()) return@mapNotNull null
        OutingKeepsake(
            id = row.optString("id").takeIf(UUID_SHAPE::matches) ?: return@mapNotNull null,
            outingId = row.optString("outing_id").takeIf(UUID_SHAPE::matches) ?: return@mapNotNull null,
            mimeType = mime,
            byteLength = length,
            createdAt = row.optString("created_at"),
        )
    }
}
