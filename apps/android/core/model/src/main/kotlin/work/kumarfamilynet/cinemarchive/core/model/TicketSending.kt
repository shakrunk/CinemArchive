package work.kumarfamilynet.cinemarchive.core.model

import java.time.Instant

/** A friend an outing's ticket was sent to, as the owner sees them (#220, docs/android-contracts/ticket-sending.md). */
data class TicketGrant(
    val grantId: String,
    val userId: String,
    val name: String,
    val sentAt: String,
    /** When the friend first opened it, or null while it is unopened. */
    val openedAt: String?,
)

/** A ticket a friend sent the signed-in user. Listing it shows no barcode; opening it does. */
data class ReceivedTicket(
    val grantId: String,
    val senderId: String,
    val senderName: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val showtime: String,
    val endsAt: String,
    val venue: String?,
    val sentAt: String,
    val openedAt: String?,
)

/** Everything needed to show a received ticket: the file to download and the decoded barcode. */
data class OpenedTicket(
    val objectKey: String,
    val mimeType: String,
    val byteLength: Long,
    val barcode: TicketBarcode?,
    val title: String,
    val showtime: String,
    val venue: String?,
)

object TicketSendingRules {
    /** The most friends one ticket is sent to; the database refuses a ninth. */
    const val MAX_RECIPIENTS = 8

    /** Received tickets stay listed until a day after the show ends. */
    fun isCurrent(ticket: ReceivedTicket, now: Instant): Boolean =
        Instant.parse(ticket.endsAt).isAfter(now.minusSeconds(24 * 60 * 60L))

    /** Soonest show first. */
    fun ordered(tickets: List<ReceivedTicket>, now: Instant): List<ReceivedTicket> =
        tickets.filter { isCurrent(it, now) }.sortedBy { Instant.parse(it.showtime) }

    /** The wire value for a format (`QR_CODE`...), tolerating one this build does not know. */
    fun parseFormat(wire: String?): TicketBarcodeFormat =
        TicketBarcodeFormat.entries.firstOrNull { it.name == wire } ?: TicketBarcodeFormat.OTHER
}
