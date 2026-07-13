package work.kumarfamilynet.cinemarchive.core.model

/**
 * Read-only detail view of a single title, mirroring the shape documented in
 * docs/android-contracts/title-detail.md (a subset of the full web contract — cast/crew
 * are deferred until the network sync layer lands, since they add real weight to every
 * Room read for a screen that doesn't need them to demonstrate the read-only spine).
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
)

data class SeasonDetail(
    val id: String,
    val seasonNumber: Int,
    val episodeCount: Int,
    val episodesWatched: Int,
    val airYear: Int?,
    val episodes: List<EpisodeDetail>,
)

data class EpisodeDetail(
    val id: String,
    val episodeNumber: Int,
    val episodeName: String?,
    val airDate: String?,
    val runtime: Int?,
    val watchCount: Int,
    val latestRating: Double?,
)

data class Viewing(
    val id: String,
    val date: String?,
    val rating: Double?,
    val notes: String?,
    val venue: String?,
)
