package work.kumarfamilynet.cinemarchive.core.model

/** A stable, local-first representation of a library item, as shown in the Library list. */
data class LibraryTitle(
    val id: String,
    val name: String,
    val year: Int?,
    val posterUrl: String?,
    val status: LibraryStatus,
    val type: MediaType,
    val director: String?,
    val network: String?,
    val rating: Double?,
    /** True while a cinema outing is scheduled for this title — drives the poster wall's
     *  amber 🎟 corner badge (docs/superpowers/plans/2026-07-21-android-cinema-outings.md §7). */
    val hasScheduledOuting: Boolean = false,
)

enum class LibraryStatus {
    WATCHLIST,
    WATCHING,
    WATCHED,
    DROPPED,
}

enum class MediaType {
    MOVIE,
    TV,
}
