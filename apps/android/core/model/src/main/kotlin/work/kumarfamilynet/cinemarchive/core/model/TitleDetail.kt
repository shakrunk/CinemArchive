package work.kumarfamilynet.cinemarchive.core.model

/**
 * Read-only detail view of a title and its locally mirrored history and credits.
 */
data class TitleDetail(
    val id: String,
    val type: MediaType,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val synopsis: String?,
    val director: String?,
    val network: String?,
    val runtime: Int?,
    val status: LibraryStatus,
    val rating: Double?,
    val notes: String?,
    val genres: List<String>,
    val seasons: List<SeasonDetail>,
    val viewings: List<Viewing>,
    /** The soonest active (scheduled, not yet ended) outing for this title, if any — drives
     *  the drawer's scheduled banner. Null once completed/cancelled/missed. */
    val scheduledOuting: CinemaOuting? = null,
    /** "I want to see this in theaters" (issue #205) — see [LibraryTitle.interestedInTheaters]. */
    val interestedInTheaters: Boolean = false,
    /** Stable provider identity for recommendation snapshots; unavailable on legacy synthetic rows. */
    val tmdbId: Int? = null,
    val cast: List<PersonCredit> = emptyList(),
    val crew: List<PersonCredit> = emptyList(),
    val tags: List<String> = emptyList(),
    val originalLanguage: String? = null,
    val releaseDate: String? = null,
    val studios: List<String> = emptyList(),
    val collectionName: String? = null,
    val addedAt: String? = null,
    val imdbRating: Double? = null,
)

data class SeasonDetail(
    val id: String,
    val seasonNumber: Int,
    val episodeCount: Int,
    val episodesWatched: Int,
    val airYear: Int?,
    val episodes: List<EpisodeDetail>,
    val cast: List<PersonCredit> = emptyList(),
)

data class EpisodeDetail(
    val id: String,
    val episodeNumber: Int,
    val episodeName: String?,
    val airDate: String?,
    val runtime: Int?,
    val watchCount: Int,
    val latestRating: Double?,
    val synopsis: String? = null,
    val stillUrl: String? = null,
    /** Mean of all logged ratings, matching the web episode card. */
    val averageRating: Double? = latestRating,
    val watchEvents: List<EpisodeWatch> = emptyList(),
    val ratings: List<EpisodeRating> = emptyList(),
    val reviews: List<EpisodeReview> = emptyList(),
    val crew: List<PersonCredit> = emptyList(),
)

data class PersonCredit(val tmdbPersonId: Int, val name: String, val role: String?) {
    val person: LibraryPerson get() = LibraryPerson(tmdbPersonId, name)
}

data class EpisodeWatch(val id: String, val watchedAt: String?, val notes: String? = null)
data class EpisodeRating(val id: String, val rating: Double, val ratedAt: String)
data class EpisodeReview(val id: String, val reviewText: String, val reviewedAt: String)

data class Viewing(
    val id: String,
    val date: String?,
    val rating: Double?,
    val notes: String?,
    val venue: String?,
    val companions: List<String> = emptyList(),
    val outingId: String? = null,
)
