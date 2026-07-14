package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import work.kumarfamilynet.cinemarchive.core.database.TitleDao
import work.kumarfamilynet.cinemarchive.core.database.ViewingDao
import work.kumarfamilynet.cinemarchive.core.model.LedgerStats
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaType

/**
 * Read-only Ledger hero-stat rollup — see [LedgerStats] and docs/android-contracts/ledger.md.
 * Pure client-side aggregation over already-synced Room data, same as the web app's
 * ledgerStats.ts; no network call, no write path.
 */
class LedgerRepository(
    private val titleDao: TitleDao,
    private val viewingDao: ViewingDao,
) {
    fun observeLedgerStats(): Flow<LedgerStats> = combine(
        titleDao.observeAllTitles(),
        viewingDao.observeTotalViewingCount(),
    ) { titles, viewingCount ->
        val ratings = titles.mapNotNull { it.rating }
        val watchedMovieMinutes = titles
            .filter { it.type == MediaType.MOVIE.name && it.status == LibraryStatus.WATCHED.name }
            .sumOf { it.runtime ?: 0 }

        LedgerStats(
            totalMovies = titles.count { it.type == MediaType.MOVIE.name },
            totalSeries = titles.count { it.type == MediaType.TV.name },
            totalViewings = viewingCount,
            averageRating = ratings.takeIf { it.isNotEmpty() }?.average(),
            totalWatchedMovieMinutes = watchedMovieMinutes,
        )
    }
}
