package work.kumarfamilynet.cinemarchive.core.model

import java.time.Instant

enum class PollStatus { OPEN, CLOSED, CANCELLED }

/** One candidate: a movie at a time, with who ticked it (#203, docs/android-contracts/outing-polls.md). */
data class PollOption(
    val id: String,
    val position: Int,
    val tmdbId: Int,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val showtime: String,
    val venue: String?,
    val votes: Int,
    /** True when the signed-in user ticked this option. */
    val mine: Boolean,
    val voters: List<String>,
)

data class PollMember(val userId: String, val name: String)

/** A question put to a handful of accepted friends, as the owner or one of those friends sees it. */
data class OutingPoll(
    val id: String,
    val question: String,
    val status: PollStatus,
    val isOwner: Boolean,
    val ownerName: String,
    val createdAt: String,
    val winnerOptionId: String?,
    val members: List<PollMember>,
    val options: List<PollOption>,
) {
    val winner: PollOption? get() = options.firstOrNull { it.id == winnerOptionId }
}

/** What the owner offers when starting a poll: one of their own movies at a future time. */
data class PollCandidate(val titleId: String, val showtime: Instant, val venue: String?)

object PollRules {
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 4
    const val MAX_FRIENDS = 8
    const val MAX_OPEN_POLLS = 5
    const val MAX_QUESTION = 100
    const val MAX_VENUE = 100

    /** A vote or a win only makes sense for a showtime that has not passed. */
    fun isUpcoming(option: PollOption, now: Instant): Boolean = Instant.parse(option.showtime).isAfter(now)

    fun canVote(poll: OutingPoll, now: Instant): Boolean = poll.status == PollStatus.OPEN && poll.options.any { isUpcoming(it, now) }

    /** The option(s) with the most ticks, among those still ahead. */
    fun leaders(poll: OutingPoll, now: Instant): List<PollOption> {
        val upcoming = poll.options.filter { isUpcoming(it, now) }
        val best = upcoming.maxOfOrNull { it.votes } ?: return emptyList()
        return if (best == 0) emptyList() else upcoming.filter { it.votes == best }
    }

    /** Checks a poll before it is sent, so the server's refusal is the exception, not the explanation. */
    fun problem(question: String, candidates: List<PollCandidate>, friendCount: Int, now: Instant): String? {
        if (question.trim().length > MAX_QUESTION) return "Keep the question to $MAX_QUESTION characters."
        if (candidates.size !in MIN_OPTIONS..MAX_OPTIONS) return "Offer $MIN_OPTIONS to $MAX_OPTIONS options."
        if (candidates.any { !it.showtime.isAfter(now) }) return "Every showtime must be in the future."
        if (candidates.any { (it.venue?.trim()?.length ?: 0) > MAX_VENUE }) return "Keep each venue to $MAX_VENUE characters."
        if (candidates.map { it.titleId to it.showtime }.toSet().size != candidates.size) return "Two options are the same."
        if (friendCount !in 1..MAX_FRIENDS) return "Choose 1 to $MAX_FRIENDS friends."
        return null
    }
}
