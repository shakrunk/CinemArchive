package work.kumarfamilynet.cinemarchive.core.model

/**
 * The whole-library rollup that backs the Ledger's hero stat ribbon — mirrors
 * `computeLedgerStats` in `src/store/ledgerStats.ts` (see docs/android-contracts/ledger.md).
 * This is deliberately just the hero ribbon, not the 20-widget board: the board's
 * customizable layout depends on `user_prefs.ledger_layout` sync, which needs a real
 * `RemoteMutationWriter` (still stubbed — see docs/android-implementation-status.md).
 *
 * [totalWatchedMovieMinutes] covers movies only, matching runtime data already mirrored
 * locally — TV watched-minutes needs a per-episode runtime sum across watched episodes,
 * which the web app computes but Android doesn't reproduce yet; deferred, not a hard blocker.
 */
data class LedgerStats(
    val totalMovies: Int,
    val totalSeries: Int,
    val totalViewings: Int,
    val averageRating: Double?,
    val totalWatchedMovieMinutes: Int,
)
