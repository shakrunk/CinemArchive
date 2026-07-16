package work.kumarfamilynet.cinemarchive.core.model

/**
 * All 20 Ledger widgets from docs/android-contracts/ledger.md §2, as a fixed-order board —
 * not yet the customizable widget layout (that's a separate local-only edit mode; syncing it
 * to `user_prefs.ledger_layout` stays blocked on a real `RemoteMutationWriter`, itself
 * blocked on a physical device — see docs/android-implementation-status.md). Every widget is
 * a pure client-side aggregation over already-local data (Room-mirrored `titles`/`seasons`/
 * `episodes`/watch-episode-rating-review logs/`viewings`/`title_cast`/`title_crew`/
 * `cinema_outings`), same as the web app's own `useMemo`'d derivations — no widget queries
 * the DB directly. See [LedgerWidgets.kt][work.kumarfamilynet.cinemarchive.core.model] for
 * the data shapes of the 16 widgets beyond this file's original four plus [LedgerStats].
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
    /** Time in the Dark: weekly viewing counts, oldest to newest (see [LedgerWeeklyActivity]). */
    val weeklyActivity: List<LedgerWeeklyActivity>,
    /** Encore Performances: titles with >=2 viewings. */
    val encores: List<LedgerEncoreEntry>,
    /** The Run: monthly viewing counts, gap-filled, default 12mo window. */
    val monthlyRun: List<LedgerMonthlyCount>,
    /** Critical Record: 0.5-star rating buckets, 5.0 descending to 0.5. */
    val ratingBuckets: List<LedgerCategoryCount>,
    /** By the Genre: genre tallies, ranked-list form. */
    val genres: List<LedgerCategoryCount>,
    /** The Auteurs: director tallies (crew rows where job == "Director"). */
    val auteurs: List<LedgerCategoryCount>,
    /** The Ensemble: leading-cast (order < 5) tallies. */
    val ensemble: List<LedgerCategoryCount>,
    /** Second Opinions: our rating vs IMDb, sorted by |delta| descending. */
    val verdicts: List<LedgerVerdictEntry>,
    /** In Translation: original-language tallies, display names. */
    val languages: List<LedgerCategoryCount>,
    /** Screening Nights: viewing counts by local day-of-week, Monday..Sunday. */
    val weekdays: List<LedgerWeekdayCount>,
    /** The Marathon: current/longest consecutive-day streaks. */
    val streaks: LedgerStreaks,
    /** Shifting Standards: average rating by the quarter each title "lands" in. */
    val trajectory: List<LedgerQuarterRating>,
    /** Premieres & Revivals: monthly premiere vs. revival viewing counts. */
    val revivals: List<LedgerPremiereRevivalBucket>,
    /** The Revival House: viewing-age buckets (viewing year - release year, floored at 0). */
    val timewarp: List<LedgerCategoryCount>,
    /** Still Rolling: in-progress TV titles and their episode-watched tallies. */
    val stillRolling: List<LedgerProgressEntry>,
    /** At the Movies: cinema-trip aggregates (see [LedgerMoviegoingStats]). */
    val moviegoing: LedgerMoviegoingStats,
)
