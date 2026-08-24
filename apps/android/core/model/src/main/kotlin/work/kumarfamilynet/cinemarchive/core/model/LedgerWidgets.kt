package work.kumarfamilynet.cinemarchive.core.model

/**
 * Data shapes for the 16 Ledger widgets beyond the hero ribbon ([LedgerStats]) and the
 * original fixed-order four ([LedgerBoard]'s original fields) — see
 * docs/android-contracts/ledger.md §2 for the source-of-truth widget registry each of these
 * mirrors. All still fixed-order, non-customizable placement (the customizable board is
 * covered separately by the local-only edit mode).
 */
data class LedgerWeeklyActivity(val weekLabel: String, val count: Int)

data class LedgerEncoreEntry(val titleId: String, val title: String, val year: Int?, val viewingCount: Int)

data class LedgerMonthlyCount(val monthLabel: String, val count: Int)

/** `ourRatingOn10` is `Title.rating * 2` (0-5★ → 0-10) per ledger.md §2's `verdicts` rule. */
data class LedgerVerdictEntry(
    val titleId: String,
    val title: String,
    val ourRatingOn10: Double,
    val imdbRating: Double,
    val delta: Double,
)

data class LedgerWeekdayCount(val weekday: String, val count: Int)

data class LedgerStreaks(
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    val recentActiveDates: List<String>,
    /** Screening activity for the trailing 30 days, oldest first — backs the additive
     *  "last 30 nights" grid alongside the existing streak-count text. */
    val last30Nights: List<Boolean> = emptyList(),
)

data class LedgerQuarterRating(val quarterLabel: String, val averageRating: Double, val titleCount: Int)

data class LedgerPremiereRevivalBucket(val monthLabel: String, val premieres: Int, val revivals: Int)

data class LedgerProgressEntry(
    val titleId: String,
    val title: String,
    val episodesWatched: Int,
    val episodeCount: Int,
)

/** A venue or format's ticket spend, e.g. "AMC Downtown" / $47.50 across 3 trips. Only
 *  outings with a recorded [work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity.ticketPrice]
 *  count toward [totalSpend]/[tripCount] — free/comped or unpriced trips widen the tally
 *  counts elsewhere but can't contribute a dollar figure here. */
data class LedgerSpendBreakdown(val label: String, val totalSpend: Double, val tripCount: Int) {
    val perTrip: Double get() = totalSpend / tripCount
}

/** A lightweight moviegoing milestone (issue #217) — "first IMAX outing", "25 visits to AMC
 *  Georgetown". [title] is the format or venue name; [detail] is the human-readable milestone
 *  itself, already formatted (no separate numeric field to re-derive text from). */
data class LedgerMilestoneBadge(val title: String, val detail: String)

/**
 * The "At the Movies" widget. [totalSpend], [formats], [venueSpend], [formatSpend], and
 * [bestValueVenue] are the owner-private fields (ledger.md §3 — sourced from
 * [work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity]); everything else
 * lives on the shared-readable `viewings` row. Android has no friend/shared viewer mode yet,
 * so this always renders the full (owner) view — see [LedgerRepository] kdoc.
 */
data class LedgerMoviegoingStats(
    val tripCount: Int,
    val byYear: List<LedgerCategoryCount>,
    val venues: List<LedgerCategoryCount>,
    val companions: List<LedgerCategoryCount>,
    val formats: List<LedgerCategoryCount>,
    val totalSpend: Double?,
    /** Total ticket spend per venue, descending. */
    val venueSpend: List<LedgerSpendBreakdown> = emptyList(),
    /** Total ticket spend per format, descending — the cost-per-outing-by-format breakdown. */
    val formatSpend: List<LedgerSpendBreakdown> = emptyList(),
    /** The venue with the lowest average price per trip, among venues with 2+ priced trips
     *  (a single trip can't establish a venue's typical value). Null with fewer than two such
     *  venues. */
    val bestValueVenue: LedgerSpendBreakdown? = null,
    /** Milestone badges (issue #217) — a format's first outing, and venue visit-count
     *  milestones. Unordered by significance; the UI sorts for display. */
    val milestoneBadges: List<LedgerMilestoneBadge> = emptyList(),
)
