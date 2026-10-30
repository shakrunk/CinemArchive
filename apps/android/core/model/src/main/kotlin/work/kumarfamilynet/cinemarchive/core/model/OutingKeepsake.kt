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

object KeepsakeRules {
    /** The most photos one outing keeps; the database refuses a ninth. */
    const val MAX_PER_OUTING = 8

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
