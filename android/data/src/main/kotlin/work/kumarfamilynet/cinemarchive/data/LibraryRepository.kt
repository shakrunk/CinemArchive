package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import work.kumarfamilynet.cinemarchive.core.database.EpisodeDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeRatingDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeRatingEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeWatchCount
import work.kumarfamilynet.cinemarchive.core.database.EpisodeWatchEventDao
import work.kumarfamilynet.cinemarchive.core.database.SeasonDao
import work.kumarfamilynet.cinemarchive.core.database.SeasonEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleDao
import work.kumarfamilynet.cinemarchive.core.database.ViewingDao
import work.kumarfamilynet.cinemarchive.core.model.EpisodeDetail
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.LibraryTitle
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.SeasonDetail
import work.kumarfamilynet.cinemarchive.core.model.TitleDetail
import work.kumarfamilynet.cinemarchive.core.model.Viewing

private data class EpisodeAggregate(
    val seasons: List<SeasonEntity>,
    val episodes: List<EpisodeEntity>,
    val watchCounts: List<EpisodeWatchCount>,
    val ratings: List<EpisodeRatingEntity>,
)

/**
 * The app reads the Library and Title detail through this Room-backed repository. Network
 * sync is added here, rather than being called from UI, once the protected Android contract
 * is available (docs/android-sync-contract.md).
 */
class LibraryRepository(
    private val titleDao: TitleDao,
    private val seasonDao: SeasonDao,
    private val episodeDao: EpisodeDao,
    private val watchEventDao: EpisodeWatchEventDao,
    private val ratingDao: EpisodeRatingDao,
    private val viewingDao: ViewingDao,
) {
    fun observeLibrary(): Flow<List<LibraryTitle>> = titleDao.observeLibrary().map { rows ->
        rows.map { row ->
            LibraryTitle(
                id = row.id,
                name = row.title,
                year = row.year,
                posterUrl = row.posterUrl,
                status = LibraryStatus.valueOf(row.status),
            )
        }
    }

    fun observeTitleDetail(titleId: String): Flow<TitleDetail?> {
        val episodeAggregate = combine(
            seasonDao.observeSeasons(titleId),
            episodeDao.observeEpisodes(titleId),
            watchEventDao.observeWatchCounts(titleId),
            ratingDao.observeRatings(titleId),
        ) { seasons, episodes, watchCounts, ratings ->
            EpisodeAggregate(seasons, episodes, watchCounts, ratings)
        }

        return combine(
            titleDao.observeTitle(titleId),
            episodeAggregate,
            viewingDao.observeViewings(titleId),
        ) { title, aggregate, viewings ->
            if (title == null) return@combine null

            val watchCountByEpisode = aggregate.watchCounts.associate { it.episodeId to it.watchCount }
            // aggregate.ratings is newest-first (see EpisodeRatingDao), so the first match per
            // episode is the latest rating.
            val latestRatingByEpisode = mutableMapOf<String, Double>()
            for (rating in aggregate.ratings) {
                latestRatingByEpisode.getOrPut(rating.episodeId) { rating.rating }
            }

            val episodesBySeason = aggregate.episodes.groupBy { it.seasonId }

            TitleDetail(
                id = title.id,
                type = MediaType.valueOf(title.type),
                title = title.title,
                year = title.year,
                posterUrl = title.posterUrl,
                backdropUrl = title.backdropUrl,
                synopsis = title.synopsis,
                director = title.director,
                network = title.network,
                runtime = title.runtime,
                status = LibraryStatus.valueOf(title.status),
                rating = title.rating,
                notes = title.notes,
                genres = title.genres,
                seasons = aggregate.seasons.map { season ->
                    SeasonDetail(
                        id = season.id,
                        seasonNumber = season.seasonNumber,
                        episodeCount = season.episodeCount,
                        episodesWatched = season.episodesWatched,
                        airYear = season.airYear,
                        episodes = (episodesBySeason[season.id] ?: emptyList()).map { episode ->
                            EpisodeDetail(
                                id = episode.id,
                                episodeNumber = episode.episodeNumber,
                                episodeName = episode.episodeName,
                                airDate = episode.airDate,
                                runtime = episode.runtime,
                                watchCount = watchCountByEpisode[episode.id] ?: 0,
                                latestRating = latestRatingByEpisode[episode.id],
                            )
                        },
                    )
                },
                viewings = viewings.map { viewing ->
                    Viewing(
                        id = viewing.id,
                        date = viewing.date,
                        rating = viewing.rating,
                        notes = viewing.notes,
                        venue = viewing.venue,
                    )
                },
            )
        }
    }
}
