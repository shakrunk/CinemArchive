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
    /** YYYY-MM-DD if known — drives the Up Next watchlist card's "releases <date>" label for
     *  a title that hasn't come out yet (see UpNextBoard.kt). */
    val releaseDate: String? = null,
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
