package work.kumarfamilynet.cinemarchive.core.model

/** Lifecycle of a booked cinema trip — see docs/superpowers/plans/2026-07-11-cinema-outings.md
 *  §4.2. `SCHEDULED` → `COMPLETED` is automatic (local completion engine, no server RPC yet —
 *  see docs/superpowers/plans/2026-07-21-android-cinema-outings.md §5); `MISSED` is only ever
 *  reached by reverting a completion ("Didn't make it"); `CANCELLED` is a user action taken
 *  before the show ends. */
enum class OutingStatus { SCHEDULED, COMPLETED, MISSED, CANCELLED }

enum class CinemaFormat { STANDARD, IMAX, THREE_D, DOLBY, SEVENTY_MM, DRIVE_IN, OTHER }

/** Symbology of a captured ticket barcode (issue #219) — stored alongside the decoded payload
 *  rather than inferred later, because re-encoding a 1D payload (e.g. [CODE_128]) as a QR code
 *  produces a code no turnstile scanner will read. See
 *  docs/superpowers/plans/2026-08-19-android-ticket-capture.md §3/§5. */
enum class TicketBarcodeFormat { QR_CODE, CODE_128, PDF_417, AZTEC, ITF, CODABAR, OTHER }

/** A booked cinema trip — the Android-local analogue of the web app's `cinema_outings` row.
 *  Timestamps are ISO-8601 instants (`Instant.toString()`), matching every other timestamp
 *  field on this layer (e.g. [Viewing.date], [TitleDetail]'s `addedAt`/`updatedAt` at the
 *  entity layer) rather than introducing `java.time.Instant` into the model surface. */
data class CinemaOuting(
    val id: String,
    val titleId: String,
    val showtime: String,
    val previewsMinutes: Int,
    val runtimeMinutes: Int,
    val endsAt: String,
    val venue: String?,
    val companions: List<String>,
    val format: CinemaFormat?,
    val ticketPrice: Double?,
    /** Legacy free-text seat string — the display fallback when [seats]/[seatRow]/[auditorium]
     *  are all empty. Never written by this client any more; see [SeatAssignment]. */
    val seat: String?,
    val auditorium: String?,
    val seatRow: String?,
    val seats: List<String>,
    val bookingRef: String?,
    /** Local file URI in v1 (`content://`/`file://`); becomes a Supabase Storage object path
     *  once the remote writer is wired live — see the ticket-capture plan's §3. Null when no
     *  ticket has been captured for this outing. */
    val ticketImagePath: String?,
    /** The barcode's decoded payload, captured from [ticketImagePath] on-device. Not the same
     *  thing as [bookingRef] (a manually-typed confirmation code) — this is the actual
     *  scannable value read off the ticket. */
    val ticketBarcodePayload: String?,
    val ticketBarcodeFormat: TicketBarcodeFormat?,
    val notes: String?,
    val status: OutingStatus,
    val previousStatus: LibraryStatus?,
    val completedViewingId: String?,
    val followUpDismissedAt: String?,
    val createdAt: String,
)

/** One outing's local completion transition — returned by the completion engine so the UI
 *  can show a toast / "Fresh from the lobby" card without a separate query. */
data class OutingTransition(
    val outingId: String,
    val titleId: String,
    val titleName: String,
    val posterUrl: String?,
    val viewingId: String,
    val newTitleStatus: LibraryStatus,
    val previousStatus: LibraryStatus?,
)
