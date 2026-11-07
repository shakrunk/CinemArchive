package work.kumarfamilynet.cinemarchive.core.model

import java.time.Instant

enum class SharedOutingStatus { SCHEDULED, COMPLETED, CANCELLED }

enum class MemberStatus { INVITED, ACCEPTED, DECLINED }

/**
 * An outing a friend invited the signed-in user to (#206, docs/android-contracts/shared-outings.md).
 * Only the minimal snapshot the plan-sharing contract exposes: never the owner's booking reference,
 * price, notes, seats or ticket.
 */
data class SharedOuting(
    val id: String,
    val status: SharedOutingStatus,
    val myStatus: MemberStatus,
    val ownerId: String,
    val ownerName: String,
    val showtime: String,
    val endsAt: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val venue: String?,
    val format: CinemaFormat?,
    val companions: List<String>,
)

/** One invited friend, as the owner of an outing sees them. */
data class OutingMember(val userId: String, val name: String, val status: MemberStatus, val sharedOutingId: String)

object SharedOutingRules {
    /** The most friends one outing invites at once; the database refuses a ninth. */
    const val MAX_INVITEES = 8

    /** A response is only taken while the outing is still scheduled and has not ended. */
    fun canRespond(outing: SharedOuting, now: Instant): Boolean =
        outing.status == SharedOutingStatus.SCHEDULED && Instant.parse(outing.endsAt).isAfter(now)

    /** Invitations still waiting on an answer, soonest first. */
    fun invitations(outings: List<SharedOuting>, now: Instant): List<SharedOuting> =
        outings.filter { it.myStatus == MemberStatus.INVITED && canRespond(it, now) }.sortedBy { Instant.parse(it.showtime) }

    /** Outings the user said yes to, including a cancelled one so the change is not silent. */
    fun going(outings: List<SharedOuting>): List<SharedOuting> =
        outings.filter { it.myStatus == MemberStatus.ACCEPTED }.sortedBy { Instant.parse(it.showtime) }
}
