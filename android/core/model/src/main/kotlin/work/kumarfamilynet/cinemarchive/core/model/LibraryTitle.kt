package work.kumarfamilynet.cinemarchive.core.model

/** A stable, local-first representation of a library item. */
data class LibraryTitle(
    val id: String,
    val name: String,
    val year: Int?,
    val posterUrl: String?,
    val status: LibraryStatus,
)

enum class LibraryStatus {
    WATCHLIST,
    WATCHING,
    WATCHED,
    DROPPED,
}
