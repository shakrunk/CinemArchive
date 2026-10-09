package work.kumarfamilynet.cinemarchive.core.model

/**
 * The whole-library rollup that backs the Ledger's hero stat ribbon — mirrors
 * `computeLedgerStats` in `src/store/ledgerStats.ts` (see docs/android-contracts/ledger.md).
 * This is deliberately just the hero ribbon, not the 20-widget board: the board's
 * customizable layout depends on `user_prefs.ledger_layout` sync, which needs a real
 * `RemoteMutationWriter` (still stubbed — see docs/android-implementation-status.md).
 *
 * Films count once when watched; each watched TV episode counts once, including Specials.
 * Rewatches do not multiply runtime, and missing runtimes contribute no invented minutes.
 */
data class LedgerStats(
    val totalMovies: Int,
    val totalSeries: Int,
    val totalViewings: Int,
    val averageRating: Double?,
    val totalWatchedMovieMinutes: Int,
    val totalWatchedEpisodeMinutes: Int = 0,
) {
    val totalWatchedMinutes: Int get() = totalWatchedMovieMinutes + totalWatchedEpisodeMinutes
    val roundedHours: Int get() = kotlin.math.floor(totalWatchedMinutes / 60.0 + 0.5).toInt()
}
