package work.kumarfamilynet.cinemarchive.data

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.KeepsakeReplacement
import work.kumarfamilynet.cinemarchive.core.model.KeepsakeRules
import work.kumarfamilynet.cinemarchive.core.model.KeepsakeSaveException
import work.kumarfamilynet.cinemarchive.core.model.OutingKeepsake
import work.kumarfamilynet.cinemarchive.core.model.PendingKeepsake

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
    private val journal: KeepsakeJournal = InMemoryKeepsakeJournal(),
) {
    suspend fun list(outingId: String): List<OutingKeepsake> = withContext(Dispatchers.IO) {
        val token = token()
        parseKeepsakes(client.get("outing_keepsakes", "outing_id=eq.${checkedUuid(outingId)}&select=*&order=created_at.asc", token))
            .also { token() }
    }

    /** Uploaded-but-unsaved photos for [outingId], oldest first. */
    fun pending(outingId: String): List<PendingKeepsake> = journal.load().filter { it.outingId == outingId }

    /** Forgets a pending photo (saved, given up on, or already a row). Its object is left to the daily cleanup. */
    fun discardPending(id: String) = journal.store(journal.load().filterNot { it.id == id })

    /** Step one: uploads [bytes] (already downscaled) under the owner's folder without overwriting and
     *  remembers them as pending. A failure here changes nothing. */
    suspend fun upload(outingId: String, bytes: ByteArray, mimeType: String): PendingKeepsake = withContext(Dispatchers.IO) {
        require(KeepsakeRules.isValidPhoto(mimeType, bytes.size)) { "That photo can't be kept: use a JPEG, PNG or WebP under 10 MB." }
        val token = token()
        val pending = PendingKeepsake(UUID.randomUUID().toString(), checkedUuid(outingId), mimeType, bytes.size.toLong())
        client.storageUpload(BUCKET, KeepsakeRules.objectKey(ownerId, pending.id), bytes, mimeType, token)
        token()
        journal.store(journal.load() + pending)
        pending
    }

    /** Step two, safe to repeat: records an uploaded photo against its outing. A row that already exists
     *  (an earlier attempt whose reply was lost) counts as saved. Throws [KeepsakeSaveException]. */
    suspend fun finish(pending: PendingKeepsake): OutingKeepsake = withContext(Dispatchers.IO) {
        val token = token()
        try {
            val saved = parseKeepsakes(client.insert("outing_keepsakes", token, JSONArray().put(rowOf(pending.id, pending.outingId, pending.mimeType, pending.byteLength)).toString()))
                .singleOrNull() ?: throw KeepsakeSaveException("The photo could not be saved.", pending)
            token()
            discardPending(pending.id)
            saved
        } catch (cause: KeepsakeSaveException) {
            throw cause
        } catch (cause: SupabaseHttpException) {
            if (cause.postgresCode == "23505") {
                val existing = parseKeepsakes(client.get("outing_keepsakes", "id=eq.${checkedUuid(pending.id)}&select=*", token)).singleOrNull()
                if (existing != null) {
                    discardPending(pending.id)
                    return@withContext existing
                }
            }
            val message = cause.userMessage("The photo uploaded but couldn't be saved to this trip.")
            if (cause.postgresCode in KeepsakeRules.FINAL_INSERT_CODES) {
                discardPending(pending.id)
                throw KeepsakeSaveException(message, null, cause)
            }
            throw KeepsakeSaveException(message, pending, cause)
        } catch (cause: java.io.IOException) {
            throw KeepsakeSaveException(cause.userMessage("The photo uploaded but couldn't be saved to this trip."), pending, cause)
        }
    }

    /** Uploads [bytes] (already downscaled), then records them against [outingId]. When only the second
     *  step fails, the thrown [KeepsakeSaveException] carries the pending photo to finish later. */
    suspend fun add(outingId: String, bytes: ByteArray, mimeType: String): OutingKeepsake = finish(upload(outingId, bytes, mimeType))

    /**
     * Swaps [old] for a new photo without ever leaving the trip with neither. The new bytes upload first,
     * so a failed upload changes nothing. With room for another row ([count] below the limit) the new row
     * is added, then the old one removed (if that last step fails both stay, reported by
     * [KeepsakeReplacement.oldRemoved]). A full album cannot hold both rows, so the old row is removed first
     * and put back, with the same id and object, if the new row cannot be saved.
     */
    suspend fun replace(old: OutingKeepsake, bytes: ByteArray, mimeType: String, count: Int): KeepsakeReplacement {
        val pending = upload(old.outingId, bytes, mimeType)
        if (KeepsakeRules.canAddAnother(count)) {
            val saved = finish(pending)
            val removed = try { remove(old.id); true } catch (cause: Exception) { cause.rethrowIfCancelled(); false }
            return KeepsakeReplacement(saved, removed)
        }
        try {
            remove(old.id)
        } catch (cause: Exception) {
            discardPending(pending.id)
            throw cause
        }
        try {
            return KeepsakeReplacement(finish(pending), true)
        } catch (cause: Exception) {
            cause.rethrowIfCancelled()
            try {
                restore(old)
                discardPending(pending.id)
            } catch (restoreFailure: Exception) {
                restoreFailure.rethrowIfCancelled()
                // The old row stays removed (its object lasts about a day); the new photo can still be finished.
                (cause as? KeepsakeSaveException)?.originalRemoved = true
            }
            throw cause
        }
    }

    /** Puts a removed row back (same id, same object), used to undo a replace that could not finish. */
    private suspend fun restore(old: OutingKeepsake) = withContext(Dispatchers.IO) {
        val token = token()
        client.insert("outing_keepsakes", token, JSONArray().put(rowOf(old.id, old.outingId, old.mimeType, old.byteLength)).toString())
        token()
        Unit
    }

    private fun rowOf(id: String, outingId: String, mimeType: String, byteLength: Long) = JSONObject()
        .put("id", checkedUuid(id)).put("user_id", ownerId).put("outing_id", checkedUuid(outingId))
        .put("object_key", KeepsakeRules.objectKey(ownerId, id)).put("mime_type", mimeType).put("byte_length", byteLength)

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
