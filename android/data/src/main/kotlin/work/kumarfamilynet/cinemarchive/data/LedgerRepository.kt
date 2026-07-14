package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import work.kumarfamilynet.cinemarchive.core.database.TitleDao
import work.kumarfamilynet.cinemarchive.core.database.ViewingDao
import work.kumarfamilynet.cinemarchive.core.model.LedgerBoard
import work.kumarfamilynet.cinemarchive.core.model.LedgerCategoryCount
import work.kumarfamilynet.cinemarchive.core.model.LedgerStats
import work.kumarfamilynet.cinemarchive.core.model.LedgerWatchlistEntry
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

    /** Feature Lengths / On the Air / By the Era / Coming Attractions — see [LedgerBoard]. */
    fun observeLedgerBoard(): Flow<LedgerBoard> = titleDao.observeAllTitles().map { titles ->
        val movies = titles.filter { it.type == MediaType.MOVIE.name }
        val series = titles.filter { it.type == MediaType.TV.name }

        val runtimeBuckets = listOf(
            "< 90 min" to movies.count { (it.runtime ?: 0) < 90 },
            "90–120 min" to movies.count { (it.runtime ?: 0) in 90..119 },
            "120–150 min" to movies.count { (it.runtime ?: 0) in 120..149 },
            "150+ min" to movies.count { (it.runtime ?: 0) >= 150 },
        ).map { (label, count) -> LedgerCategoryCount(label, count) }

        val networks = series
            .mapNotNull { it.network }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { LedgerCategoryCount(it.key, it.value) }

        val decades = titles
            .mapNotNull { it.year }
            .map { year -> "${(year / 10) * 10}s" }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedBy { it.key }
            .map { LedgerCategoryCount(it.key, it.value) }

        val watchlisted = titles.filter { it.status == LibraryStatus.WATCHLIST.name }
        val watchlist = watchlisted.map {
            LedgerWatchlistEntry(titleId = it.id, title = it.title, year = it.year, runtimeMinutes = it.runtime)
        }
        val watchlistMovieMinutesOwed = watchlisted
            .filter { it.type == MediaType.MOVIE.name }
            .sumOf { it.runtime ?: 0 }

        LedgerBoard(runtimeBuckets, networks, decades, watchlist, watchlistMovieMinutesOwed)
    }
}
