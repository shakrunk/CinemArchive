package work.kumarfamilynet.cinemarchive.core.model

/** Lifecycle of a booked cinema trip — see docs/superpowers/plans/2026-07-11-cinema-outings.md
 *  §4.2. `SCHEDULED` → `COMPLETED` is automatic (local completion engine, no server RPC yet —
 *  see docs/superpowers/plans/2026-07-21-android-cinema-outings.md §5); `MISSED` is only ever
 *  reached by reverting a completion ("Didn't make it"); `CANCELLED` is a user action taken
 *  before the show ends. */
enum class OutingStatus { SCHEDULED, COMPLETED, MISSED, CANCELLED }

enum class CinemaFormat { STANDARD, IMAX, THREE_D, DOLBY, SEVENTY_MM, DRIVE_IN, OTHER }

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
    val seat: String?,
    val bookingRef: String?,
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
