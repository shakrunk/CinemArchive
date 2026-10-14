package work.kumarfamilynet.cinemarchive.data

import work.kumarfamilynet.cinemarchive.core.model.TicketAttachment
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

internal object TicketAttachmentFixture {
    val scope = TicketOwnerScope("https://tickets.supabase.co", "10000000-0000-4000-8000-000000000001")
    const val outing = "20000000-0000-4000-8000-000000000001"
    const val attachment = "30000000-0000-4000-8000-000000000001"
    const val operation = "40000000-0000-4000-8000-000000000001"
    val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(128) { it.toByte() }
    fun descriptor(id: String = attachment) = TicketAttachment(id, "${scope.ownerId}/$id/original", "image/png", bytes.size.toLong(), ticketDigest(bytes))
    fun command() = TicketAttachmentCommand(operation, scope, outing, descriptor(), null)
}
