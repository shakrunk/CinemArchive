package work.kumarfamilynet.cinemarchive.core.model

import java.time.Instant

/**
 * How one person on a shared outing said it went (#207, docs/android-contracts/shared-outing-reactions.md).
 * Shared on purpose and separate from anyone's private viewing log: nothing here is copied into or
 * out of a library.
 */
data class OutingReaction(
    val userId: String,
    val name: String,
    val isMe: Boolean,
    /** Half stars from 0.5 to 5.0, or null for a note-only reaction. */
    val rating: Double?,
    val note: String?,
    val updatedAt: String,
)

/** What the group reactions panel needs from one shared outing, so the panel itself knows no repository. */
class GroupReactionActions(
    val load: suspend () -> List<OutingReaction>,
    val share: suspend (rating: Double?, note: String?) -> Unit,
    val withdraw: suspend () -> Unit,
)

object ReactionRules {
    const val MAX_NOTE = 500

    /** Reactions open once the show has ended (or the owner's outing completed) and stay closed if it was cancelled. */
    fun isOpen(status: SharedOutingStatus, endsAt: String, now: Instant): Boolean = when (status) {
        SharedOutingStatus.CANCELLED -> false
        SharedOutingStatus.COMPLETED -> true
        SharedOutingStatus.SCHEDULED -> !Instant.parse(endsAt).isAfter(now)
    }

    fun isOpen(outing: SharedOuting, now: Instant): Boolean = isOpen(outing.status, outing.endsAt, now)

    /** Half stars between 0.5 and 5.0. */
    fun isValidRating(rating: Double): Boolean = rating in 0.5..5.0 && rating * 2 == Math.floor(rating * 2)

    /** The note as it will be stored: trimmed, or null when blank. */
    fun cleanNote(note: String?): String? = note?.trim()?.takeIf { it.isNotEmpty() }

    /** A reaction needs a valid rating, a note within the limit, or both. */
    fun canShare(rating: Double?, note: String?): Boolean {
        val cleaned = cleanNote(note)
        if (rating == null && cleaned == null) return false
        if (rating != null && !isValidRating(rating)) return false
        return cleaned == null || cleaned.length <= MAX_NOTE
    }
}
