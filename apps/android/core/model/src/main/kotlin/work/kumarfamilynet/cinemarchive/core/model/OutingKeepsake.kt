package work.kumarfamilynet.cinemarchive.core.model

/**
 * A keepsake photo kept with a cinema outing (#208): a ticket stub, a poster board, a popcorn
 * selfie. Owner-private and separate from the single scannable ticket attachment. The bytes live
 * in the private `outing-keepsakes` bucket; this is the row that points at them
 * (docs/android-contracts/outing-keepsakes.md).
 */
data class OutingKeepsake(
    val id: String,
    val outingId: String,
    val mimeType: String,
    val byteLength: Long,
    val createdAt: String,
)

/**
 * A photo whose bytes uploaded but whose row did not save (the connection dropped, or the outing was
 * still syncing). Remembered on the device per account so "Finish saving" can retry only the row
 * insert; the photo itself is not kept, so a failed upload starts over from the picker.
 */
data class PendingKeepsake(val id: String, val outingId: String, val mimeType: String, val byteLength: Long)

/** The row insert failed after the bytes uploaded. [pending] is set when finishing later can still
 *  work, and null when it cannot (the outing is full, gone or not the caller's). */
class KeepsakeSaveException(message: String, val pending: PendingKeepsake?, cause: Throwable? = null) :
    IllegalStateException(message, cause) {
    /** Set by a replace that had to remove the old photo and could not put it back, so the new one is all that is left to finish. */
    var originalRemoved: Boolean = false
}

/** The outcome of swapping a photo: [oldRemoved] is false when the new photo is saved but the old
 *  row could not be removed yet. */
data class KeepsakeReplacement(val saved: OutingKeepsake, val oldRemoved: Boolean)

object KeepsakeRules {
    /** The most photos one outing keeps; the database refuses a ninth. */
    const val MAX_PER_OUTING = 8

    /** Row-insert failures a retry can never fix: the guard trigger's mismatched object (22023), ninth
     *  photo (54000) and someone else's outing (42501). */
    val FINAL_INSERT_CODES = setOf("22023", "54000", "42501")

    /** Per photo, after the client has downscaled it; the bucket refuses anything larger. */
    const val MAX_BYTES = 10 * 1024 * 1024

    /** Longest edge a photo is downscaled to before upload. */
    const val MAX_EDGE_PX = 2048

    const val JPEG = "image/jpeg"
    val MIME_TYPES = setOf(JPEG, "image/png", "image/webp")

    /** Storage object name: `<owner id>/<keepsake id>`, the only shape the bucket accepts. */
    fun objectKey(ownerId: String, id: String): String = "$ownerId/$id"

    fun isValidPhoto(mimeType: String, byteLength: Int): Boolean =
        mimeType in MIME_TYPES && byteLength in 1..MAX_BYTES

    fun canAddAnother(count: Int): Boolean = count < MAX_PER_OUTING

    /** The size to decode a [width] by [height] photo at so its longest edge is at most
     *  [MAX_EDGE_PX], keeping the aspect ratio; unchanged when it already fits. */
    fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Invalid image size" }
        val longest = maxOf(width, height)
        if (longest <= MAX_EDGE_PX) return width to height
        val scale = MAX_EDGE_PX.toDouble() / longest
        return Math.round(width * scale).toInt().coerceAtLeast(1) to Math.round(height * scale).toInt().coerceAtLeast(1)
    }
}
