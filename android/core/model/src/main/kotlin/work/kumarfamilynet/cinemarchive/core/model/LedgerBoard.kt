package work.kumarfamilynet.cinemarchive.core.model

/**
 * A handful of the simplest remaining Ledger widgets (docs/android-contracts/ledger.md §2) —
 * chosen because each is a pure bucket/filter over fields [LedgerStats] already needs
 * ([work.kumarfamilynet.cinemarchive.core.database.TitleEntity]'s type/network/year/runtime/
 * status), so none require new local data (unlike Auteurs/Ensemble/Second Opinions/In
 * Translation/At the Movies, which need cast, crew, imdbRating, originalLanguage, or
 * companions/outingId — none mirrored locally yet). Still just the fixed-order board, not
 * the customizable widget layout (blocked on `user_prefs.ledger_layout` sync).
 */
data class LedgerCategoryCount(val label: String, val count: Int)

data class LedgerWatchlistEntry(val titleId: String, val title: String, val year: Int?, val runtimeMinutes: Int?)

data class LedgerBoard(
    /** Feature Lengths: movie runtime histogram, buckets <90/90-120/120-150/150+. */
    val runtimeBuckets: List<LedgerCategoryCount>,
    /** On the Air: TV title counts by network, descending. */
    val networks: List<LedgerCategoryCount>,
    /** By the Era: title counts by release decade, ascending. */
    val decades: List<LedgerCategoryCount>,
    /** Coming Attractions: the watchlist. */
    val watchlist: List<LedgerWatchlistEntry>,
    /** Coming Attractions: total runtime owed across watchlisted movies only (TV excluded). */
    val watchlistMovieMinutesOwed: Int,
)
