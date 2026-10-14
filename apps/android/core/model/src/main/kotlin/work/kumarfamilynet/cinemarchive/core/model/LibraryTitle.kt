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
    /** Drives the Library filter sheet's genre chips (#120/KP-050). */
    val genres: List<String> = emptyList(),
    /** ISO-8601 instant of the most recent interaction with this title — added, (re)watched, or
     *  any per-episode watch/rating/review event. Backs [LibrarySortOrder.LAST_INTERACTION]; see
     *  `TitleDao.observeLastInteractions` for how it's rolled up and why it's a comparable
     *  string. Null when nothing about the title carries a usable timestamp. */
    val lastInteractionAt: String? = null,
    /** "I want to see this in theaters" (issue #205) — set from the title detail screen,
     *  independent of [status]/[hasScheduledOuting]. Drives the Up Next watchlist card's
     *  prompt to schedule an outing once [releaseDate] has passed with no outing booked yet. */
    val interestedInTheaters: Boolean = false,
    val addedAt: String? = null,
    val originalLanguage: String? = null,
    val tags: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val collectionId: Int? = null,
    val collectionName: String? = null,
    val castNames: List<String> = emptyList(),
    val people: List<LibraryPerson> = emptyList(),
)

/** Provider identity is authoritative: two credited people may have the same name. */
data class LibraryPerson(val tmdbPersonId: Int, val name: String)

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
