package work.kumarfamilynet.cinemarchive.core.model

/** The 20 Ledger panel ids — see docs/android-contracts/ledger.md §2. [raw] matches the
 *  web app's `LedgerPanelId` string exactly, since a stored layout should round-trip. */
enum class LedgerWidgetId(val raw: String) {
    ACTIVITY("activity"),
    ENCORES("encores"),
    RUN("run"),
    RATINGS("ratings"),
    GENRES("genres"),
    DECADES("decades"),
    AUTEURS("auteurs"),
    ENSEMBLE("ensemble"),
    RUNTIMES("runtimes"),
    NETWORKS("networks"),
    VERDICTS("verdicts"),
    LANGUAGES("languages"),
    WEEKDAYS("weekdays"),
    STREAKS("streaks"),
    TRAJECTORY("trajectory"),
    REVIVALS("revivals"),
    TIMEWARP("timewarp"),
    PROGRESS("progress"),
    ATTRACTIONS("attractions"),
    MOVIEGOING("moviegoing");

    companion object {
        fun fromRaw(raw: String): LedgerWidgetId? = entries.find { it.raw == raw }
    }
}

/** `sm`/`md`/`lg` = 4/6/8 of 12 cols at lg+ breakpoints; always full below lg (ledger.md §1).
 *  Android is phone-first with no lg+ grid yet, so today every width renders full-bleed —
 *  the value still round-trips through storage/normalization so a future responsive board
 *  doesn't need a layout migration. */
enum class LedgerWidgetWidth(val raw: String) {
    SM("sm"), MD("md"), LG("lg"), FULL("full");

    companion object {
        fun fromRaw(raw: String): LedgerWidgetWidth? = entries.find { it.raw == raw }
    }
}

/** Only [topN] and [title] are applied to a widget's rendered output today (as a post-hoc
 *  take(n)/header-override in the UI layer — no widget computation itself takes parameters).
 *  [timeRange]/[scope] persist and round-trip through normalization but aren't consumed by
 *  any widget's aggregation yet — a larger follow-on since it means re-deriving each panel's
 *  data per-widget instead of once for the whole board. */
data class LedgerWidgetSettings(
    val timeRange: String? = null,
    val scope: String? = null,
    val topN: Int? = null,
    val title: String? = null,
)

data class LedgerWidgetConfig(
    val id: String,
    val panel: LedgerWidgetId,
    val width: LedgerWidgetWidth,
    val settings: LedgerWidgetSettings? = null,
)

private const val MIN_TOP_N = 3
private const val MAX_TOP_N = 12
private const val MAX_TITLE_LENGTH = 60
private val VALID_TIME_RANGES = setOf("all", "12mo", "ytd", "5y")
private val VALID_SCOPES = setOf("all", "movies", "tv")

object LedgerLayoutRules {
    /** One widget per panel, in the canonical ledger.md §2 order, all full-width — the
     *  starting board before any local edit. */
    fun defaultLedgerWidgets(): List<LedgerWidgetConfig> =
        LedgerWidgetId.entries.mapIndexed { index, panel ->
            LedgerWidgetConfig(id = "widget-${panel.raw}-$index", panel = panel, width = LedgerWidgetWidth.FULL)
        }

    /**
     * Sanitizes a possibly legacy/malformed stored layout — drops unknown panels, backfills
     * an invalid width to [LedgerWidgetWidth.FULL], keeps only well-typed settings keys, and
     * re-clamps `topN`/`title`. Mirrors `normalizeLedgerWidgets()` in ledgerPanels.ts exactly
     * (ledger.md §1: "Android's own layout parser must apply the identical clamps").
     */
    fun normalize(raw: List<RawLedgerWidget>): List<LedgerWidgetConfig> = raw.mapNotNull { entry ->
        val panel = LedgerWidgetId.fromRaw(entry.panel) ?: return@mapNotNull null
        val width = LedgerWidgetWidth.fromRaw(entry.width ?: "") ?: LedgerWidgetWidth.FULL
        val settings = entry.settings?.let { s ->
            LedgerWidgetSettings(
                timeRange = s.timeRange?.takeIf { it in VALID_TIME_RANGES },
                scope = s.scope?.takeIf { it in VALID_SCOPES },
                topN = s.topN?.coerceIn(MIN_TOP_N, MAX_TOP_N),
                title = s.title?.take(MAX_TITLE_LENGTH),
            )
        }
        LedgerWidgetConfig(id = entry.id, panel = panel, width = width, settings = settings)
    }
}

/** Untyped intermediate shape for [LedgerLayoutRules.normalize] — deliberately permissive
 *  (nullable strings straight off a parser) since normalization's whole job is validating
 *  fields that might be missing, wrong-typed at the source, or from a legacy panel/width. */
data class RawLedgerWidget(
    val id: String,
    val panel: String,
    val width: String?,
    val settings: RawLedgerWidgetSettings?,
)

data class RawLedgerWidgetSettings(
    val timeRange: String?,
    val scope: String?,
    val topN: Int?,
    val title: String?,
)
