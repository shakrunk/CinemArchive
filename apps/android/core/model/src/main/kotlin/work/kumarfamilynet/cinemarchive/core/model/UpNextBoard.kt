package work.kumarfamilynet.cinemarchive.core.model

/** A WATCHING title with its aggregated episode progress — the "next episode" cards on
 *  the Up Next screen. Movies (no seasons) never produce one of these; see
 *  LibraryRepository.observeUpNext(). [nextSeasonNumber]/[nextEpisodeNumber]/[nextEpisodeName]
 *  are null when every locally-known episode is already watched (e.g. a finale, still marked
 *  WATCHING). [nextEpisodeAirDate] is the raw TMDB `air_date` (YYYY-MM-DD) for that next
 *  episode, if known — used to show an air date instead of a "mark watched" action for an
 *  episode that hasn't aired yet on a still-airing series. */
data class UpNextWatching(
    val id: String,
    val name: String,
    val posterUrl: String?,
    val episodesWatched: Int,
    val episodesTotal: Int,
    val nextSeasonNumber: Int? = null,
    val nextEpisodeNumber: Int? = null,
    val nextEpisodeName: String? = null,
    val nextEpisodeAirDate: String? = null,
)

/** One "On the Marquee" card's presentation-ready shape (see [CinemaOutingRules]). */
data class UpNextOuting(
    val outing: CinemaOuting,
    val titleName: String,
    val posterUrl: String?,
)

/** One "on this day" memory-lane card's presentation-ready shape (issue #218,
 *  see [CinemaOutingRules.onThisDay]). [rating]/[notes] come from the outing's linked
 *  [Viewing], if it still exists and carries either. */
data class UpNextOnThisDay(
    val outing: CinemaOuting,
    val titleName: String,
    val posterUrl: String?,
    val yearsAgo: Int,
    val rating: Double? = null,
    val notes: String? = null,
)

data class UpNextBoard(
    val watching: List<UpNextWatching>,
    val watchlist: List<LibraryTitle>,
    val onTheMarquee: List<UpNextOuting> = emptyList(),
    /** Completed outings still awaiting "how was it?" — the "Fresh from the lobby" cards. */
    val freshFromTheLobby: List<UpNextOuting> = emptyList(),
    /** Past outings that happened on this same month/day in an earlier year. */
    val onThisDay: List<UpNextOnThisDay> = emptyList(),
)
