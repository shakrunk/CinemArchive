package work.kumarfamilynet.cinemarchive.core.model

/** Private original-image metadata. Object keys are never public URLs or local filenames. */
data class TicketAttachment(
    val id: String,
    val objectKey: String,
    val mimeType: String,
    val byteLength: Long,
    val sha256: String,
    val barcode: TicketBarcode? = null,
)

data class TicketBarcode(val payload: String, val format: TicketBarcodeFormat)

/** An explicit managed clear must never revive the legacy local photo. */
data class TicketAssociation(val outingId: String, val attachment: TicketAttachment?)

data class TicketOwnerScope(val projectId: String, val ownerId: String)
