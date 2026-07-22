package work.kumarfamilynet.cinemarchive.core.model

/** A WATCHING title with its aggregated episode progress — the "continue watching" cards on
 *  the Up Next screen. Movies (no seasons) never produce one of these; see
 *  LibraryRepository.observeUpNext(). */
data class UpNextWatching(
    val id: String,
    val name: String,
    val posterUrl: String?,
    val episodesWatched: Int,
    val episodesTotal: Int,
)

/** One "On the Marquee" card's presentation-ready shape (see [CinemaOutingRules]). */
data class UpNextOuting(
    val outing: CinemaOuting,
    val titleName: String,
    val posterUrl: String?,
)

data class UpNextBoard(
    val watching: List<UpNextWatching>,
    val watchlist: List<LibraryTitle>,
    val onTheMarquee: List<UpNextOuting> = emptyList(),
    /** Completed outings still awaiting "how was it?" — the "Fresh from the lobby" cards. */
    val freshFromTheLobby: List<UpNextOuting> = emptyList(),
)
