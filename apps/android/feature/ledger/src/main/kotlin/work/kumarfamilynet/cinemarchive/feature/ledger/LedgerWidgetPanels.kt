package work.kumarfamilynet.cinemarchive.feature.ledger

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import work.kumarfamilynet.cinemarchive.core.designsystem.BubbleCloud
import work.kumarfamilynet.cinemarchive.core.designsystem.ChartDatum
import work.kumarfamilynet.cinemarchive.core.designsystem.ComparisonDumbbell
import work.kumarfamilynet.cinemarchive.core.designsystem.DmMonoFamily
import work.kumarfamilynet.cinemarchive.core.designsystem.FilmstripTrack
import work.kumarfamilynet.cinemarchive.core.designsystem.LedgerTrackInset
import work.kumarfamilynet.cinemarchive.core.designsystem.RadialSpokePlot
import work.kumarfamilynet.cinemarchive.core.designsystem.TicketStub
import work.kumarfamilynet.cinemarchive.core.model.LedgerCategoryCount
import work.kumarfamilynet.cinemarchive.core.model.LedgerMoviegoingStats
import work.kumarfamilynet.cinemarchive.core.model.LedgerStreaks
import work.kumarfamilynet.cinemarchive.core.model.LedgerVerdictEntry
import work.kumarfamilynet.cinemarchive.core.model.LedgerWeekdayCount
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Ledger widgets whose data doesn't reduce to "count per bucket," rendered in a visual
 * language of their own rather than the shared label/count row every other panel uses. Each
 * one pairs a purpose-built primitive from `core:designsystem`'s `LedgerWidgetPrimitives.kt`
 * with a plain-language subtitle and a full set of real, screen-reader-reachable figures
 * (ledger.md §5):
 *
 * - [VerdictsPanel] (Second Opinions) — dumbbell rows on one shared 0–10 scale, because the
 *   widget's subject is the *gap* between two scores, not either score alone.
 * - [MarathonPanel] (The Marathon) — a 30-frame filmstrip, because a streak is about
 *   consecutive runs, which a per-day intensity grid actively obscures.
 * - [MoviegoingPanel] (At the Movies) — a torn ticket, because trips/spend/venue are
 *   unrelated headline figures from one physical event, not a series.
 * - [WeekdaysPanel] (Screening Nights) — a seven-spoke dial, because day-of-week is cyclical
 *   and a left-to-right axis puts Sunday and Monday at opposite ends of the widget.
 * - [GenresPanel] (By the Genre) — a bubble field sized on √(count/max), restoring the
 *   encoding the web app uses and Android had flattened into a list.
 *
 * [WeekdaysPanel] and [GenresPanel] also close parity gaps the contract names: the web app
 * draws a radar for Screening Nights and bubbles for By the Genre, both of which Android had
 * reduced to bars or rows. Further panels share this file's chrome from
 * [LedgerQueuePanels.kt][AttractionsPanel] (Coming Attractions, Critical Record),
 * [LedgerCadencePanels.kt][RevivalsPanel] (Premieres & Revivals, Still Rolling), and
 * [LedgerCreditsPanel.kt][EnsemblePanel] (The Ensemble) — split off only for file length.
 *
 * All of them render inside `LedgerScreen`'s fixed-height, internally scrolling widget card
 * and are also what the edit-mode palette thumbnail scales down, so they stay legible at 0.28×.
 */

// ---------------------------------------------------------------------------------------
// Shared chrome
// ---------------------------------------------------------------------------------------

/** Title plus a one-line, jargon-free statement of what the widget is actually counting —
 *  the panel labels ("Second Opinions", "The Marathon") are deliberately cinematic and don't
 *  survive being read cold, so every overhauled panel spells its subject out underneath. */
@Composable
internal fun PanelHeading(title: String, subtitle: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(width = 22.dp, height = 2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
internal fun PanelEmpty(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Small-caps overline used for figure labels on the ticket and the marathon's rails. */
@Composable
internal fun Overline(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** A figure rendered in the tabular mono face — every number in these three panels goes
 *  through here so digits line up column-to-column across rows. */
@Composable
internal fun MonoFigure(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    textAlign: TextAlign? = null,
) {
    Text(
        text,
        style = style.copy(fontFamily = DmMonoFamily),
        color = color,
        maxLines = 1,
        textAlign = textAlign,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------------------
// Second Opinions — dumbbell rows
// ---------------------------------------------------------------------------------------

private const val VERDICT_SCALE_MAX = 10f

/**
 * Second Opinions as a dumbbell plot: one shared 0–10 rail per row, your score as a solid
 * marker, IMDb's as a hollow one, the disagreement as the bar between them. Rows arrive
 * already sorted by |delta| (see `LedgerRepository.verdicts`), so the longest bars sit at the
 * top and the widget's actual claim — "here's where we disagree most" — is the first thing
 * the eye lands on.
 *
 * Every row is one merged accessibility node carrying both raw scores and the direction of
 * the gap, since the dumbbell canvas itself is decorative.
 */
@Composable
internal fun VerdictsPanel(title: String, entries: List<LedgerVerdictEntry>) {
    PanelHeading(title, "Your score against IMDb's, on one 0–10 scale")
    if (entries.isEmpty()) {
        PanelEmpty("No title carries both your rating and an IMDb score yet.")
        return
    }

    val higher = entries.count { it.delta > 0 }
    val averageGap = entries.sumOf { it.delta } / entries.size
    Text(
        buildString {
            append("You rate $higher of these ${entries.size} above IMDb")
            when {
                averageGap > 0.05 -> append(" — %.1f points warmer on average.".format(averageGap))
                averageGap < -0.05 -> append(" — %.1f points harsher on average.".format(-averageGap))
                else -> append(" — dead even on average.")
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendKey(filled = true, color = MaterialTheme.colorScheme.primary, label = "You")
        LegendKey(filled = false, color = MaterialTheme.colorScheme.onSurfaceVariant, label = "IMDb")
    }

    ScaleRule()
    entries.forEach { VerdictDumbbellRow(it) }
}

@Composable
private fun LegendKey(filled: Boolean, color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(11.dp)
                .clip(RoundedCornerShape(50))
                .then(
                    if (filled) Modifier.background(color)
                    else Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .drawBehind {
                            drawCircle(color, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                        },
                ),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

/** The 0/5/10 gradations, inset to line up with the rail every [ComparisonDumbbell] below
 *  draws on ([LedgerTrackInset]) — labelled once at the top rather than repeated per row. */
@Composable
private fun ScaleRule() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LedgerTrackInset - 5.dp)
            .clearAndSetSemantics {},
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        listOf("0", "5", "10").forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = DmMonoFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VerdictDumbbellRow(entry: LedgerVerdictEntry) {
    val weRateHigher = entry.delta >= 0
    val accent = if (weRateHigher) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    val description = "%s — you %.1f, IMDb %.1f, %s by %.1f".format(
        entry.title,
        entry.ourRatingOn10,
        entry.imdbRating,
        if (weRateHigher) "higher" else "lower",
        abs(entry.delta),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
            )
            DeltaChip(entry.delta, accent)
        }
        ComparisonDumbbell(
            subjectFraction = (entry.ourRatingOn10 / VERDICT_SCALE_MAX).toFloat(),
            referenceFraction = (entry.imdbRating / VERDICT_SCALE_MAX).toFloat(),
            subjectColor = accent,
            connectorColor = accent.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 1.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = LedgerTrackInset - 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MonoFigure("you %.1f".format(entry.ourRatingOn10), style = MaterialTheme.typography.labelSmall, color = accent)
            MonoFigure(
                "IMDb %.1f".format(entry.imdbRating),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeltaChip(delta: Double, accent: Color) {
    Surface(
        color = accent.copy(alpha = 0.16f),
        contentColor = accent,
        shape = RoundedCornerShape(50),
    ) {
        MonoFigure(
            "%s%.1f".format(if (delta >= 0) "+" else "−", abs(delta)),
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------
// The Marathon — filmstrip
// ---------------------------------------------------------------------------------------

/**
 * The Marathon as a strip of film: the current run as a single oversized numeral, the last 30
 * nights as lit and dark frames, and the run still in progress picked out in the accent colour
 * so it separates from older runs of the same length. The personal-best rail underneath turns
 * "14 vs 21" — two numbers that mean nothing next to each other as text — into a distance.
 *
 * `last30Nights` is trailing-30-days, oldest first, with the final frame being *today*
 * (`LedgerRepository.streaks`); the live run therefore ends at either the last or second-to-
 * last frame, since a streak stays current while yesterday had activity (ledger.md §2).
 */
@Composable
internal fun MarathonPanel(title: String, streaks: LedgerStreaks) {
    PanelHeading(title, "Nights in a row with a screening or an episode logged")

    val nights = streaks.last30Nights
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        MonoFigure(
            streaks.currentStreakDays.toString(),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.padding(start = 10.dp, bottom = 5.dp).weight(1f)) {
            Overline(if (streaks.currentStreakDays == 1) "night" else "nights")
            Text(
                "current run",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StreakMoodPill(streaks)
    }

    if (nights.isNotEmpty()) {
        FilmstripTrack(
            frames = nights,
            highlight = liveRunRange(nights),
            contentDescription = last30NightsDescription(nights),
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Overline("30 nights ago")
            Overline("tonight")
        }
    }

    if (streaks.longestStreakDays > 0) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Overline("personal best")
                MonoFigure(
                    "${streaks.currentStreakDays} / ${streaks.longestStreakDays}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            BestRunRail(current = streaks.currentStreakDays, longest = streaks.longestStreakDays)
        }
    }

    if (streaks.recentActiveDates.isNotEmpty()) {
        Text(
            "Latest screening nights",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        RecentNightsGrid(streaks.recentActiveDates)
    } else {
        PanelEmpty("Nothing logged with a date yet — a streak starts on the first dated night.")
    }
}

@Composable
private fun StreakMoodPill(streaks: LedgerStreaks) {
    val current = streaks.currentStreakDays
    val matchedBest = current > 1 && current >= streaks.longestStreakDays
    val label = when {
        matchedBest -> "Record pace"
        current == 0 -> "No run yet"
        current < 3 -> "Getting going"
        current < 7 -> "On a run"
        else -> "Marathon"
    }
    val accent = if (current == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary
    Surface(color = accent.copy(alpha = 0.16f), contentColor = accent, shape = RoundedCornerShape(50)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Current run drawn as a share of the all-time best — the figures above it stay the
 *  accessible source of truth, so this is cleared for screen readers. */
@Composable
private fun BestRunRail(current: Int, longest: Int) {
    val fraction = if (longest <= 0) 0f else (current.toFloat() / longest).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp)
            .height(9.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.outlineVariant)
            .clearAndSetSemantics {},
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.horizontalGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                    ),
                ),
        )
    }
}

/** The 6 most recent dated nights as a tidy 3-up grid of chips — [LedgerStreaks
 *  .recentActiveDates] arrives oldest-first, so it's reversed here to lead with the newest. */
@Composable
private fun RecentNightsGrid(isoDates: List<String>) {
    val formatter = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    val labels = isoDates.reversed().take(6).map { iso ->
        runCatching { LocalDate.parse(iso).format(formatter) }.getOrDefault(iso)
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.chunked(3).forEach { rowLabels ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                rowLabels.forEach { label ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        MonoFigure(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 6.dp).fillMaxWidth(),
                        )
                    }
                }
                repeat(3 - rowLabels.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The trailing consecutive run of lit nights, or null if the streak is already broken.
 *  Skips a dark final frame first: a run counts as current while *yesterday* had activity,
 *  so tonight being empty doesn't end it (ledger.md §2). */
private fun liveRunRange(nights: List<Boolean>): IntRange? {
    if (nights.isEmpty()) return null
    var end = nights.lastIndex
    if (!nights[end]) end--
    if (end < 0 || !nights[end]) return null
    var start = end
    while (start > 0 && nights[start - 1]) start--
    return start..end
}

/**
 * Run-length description of all 30 nights, e.g. "23 of the last 30 nights had a screening:
 * Jun 28 to Jul 2, Jul 5, Jul 8 to Jul 12." ledger.md §5 flags the web app's equivalent dot
 * grid as having no per-night label at all; this accounts for every night while staying a
 * single TalkBack stop rather than thirty. Index `lastIndex` is today, matching
 * `LedgerRepository.streaks`.
 */
private fun last30NightsDescription(nights: List<Boolean>): String {
    val formatter = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    val today = LocalDate.now()
    fun dateAt(index: Int): String = today.minusDays((nights.lastIndex - index).toLong()).format(formatter)

    val runs = mutableListOf<String>()
    var index = 0
    while (index < nights.size) {
        if (!nights[index]) {
            index++
            continue
        }
        val start = index
        while (index < nights.size && nights[index]) index++
        val end = index - 1
        runs += if (start == end) dateAt(start) else "${dateAt(start)} to ${dateAt(end)}"
    }
    if (runs.isEmpty()) return "No screenings in the last ${nights.size} nights."
    return "${nights.count { it }} of the last ${nights.size} nights had a screening: ${runs.joinToString(", ")}."
}

// ---------------------------------------------------------------------------------------
// At the Movies — ticket stub
// ---------------------------------------------------------------------------------------

private const val YEAR_BAR_LIMIT = 8

/**
 * At the Movies as the artifact the data came from: a torn ticket. Trip count, total spend,
 * and average price are unrelated quantities that share only an occasion, so they're printed
 * as ticket fields rather than forced into a chart; the years strip below the tear is the one
 * genuinely serial figure and gets bars. Venue/companion/format tallies follow as receipt-style
 * leader rows — every value the old text dump carried is still here, just no longer
 * undifferentiated.
 *
 * `totalSpend` and `formats` are the owner-private half of this widget (ledger.md §3); Android
 * has no shared-viewer mode yet, so both always render.
 */
@Composable
internal fun MoviegoingPanel(title: String, stats: LedgerMoviegoingStats) {
    PanelHeading(title, "Trips out to an actual cinema, off the ticket")

    val theatre = stats.venues.firstOrNull()?.label ?: "No venue on record"
    val averageSpend = stats.totalSpend?.takeIf { stats.tripCount > 0 }?.div(stats.tripCount)
    TicketStub(
        modifier = Modifier.padding(top = 6.dp),
        header = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Overline(theatre, modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp))
                MonoFigure(
                    "N° %04d".format(stats.tripCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Overline("admit", modifier = Modifier.padding(bottom = 6.dp))
                    MonoFigure(
                        stats.tripCount.toString(),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Overline("total paid")
                    MonoFigure(
                        stats.totalSpend?.let { "$%,.2f".format(it) } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TicketField("avg / trip", averageSpend?.let { "$%,.2f".format(it) } ?: "—")
                TicketField("companions", stats.companions.firstOrNull()?.label ?: "—", Alignment.CenterHorizontally)
                TicketField("format", stats.formats.firstOrNull()?.label ?: "—", Alignment.End)
            }
        },
        stub = {
            if (stats.byYear.isNotEmpty()) {
                Overline("trips by year")
                // byYear arrives ranked by count (LedgerRepository.tally); a year strip only
                // reads as a trend in calendar order, so re-sort and keep the recent end.
                YearBars(stats.byYear.sortedBy { it.label }.takeLast(YEAR_BAR_LIMIT))
            } else {
                Text(
                    if (stats.tripCount == 0) "No cinema trips logged yet — nothing to tear." else "No dated trips yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )

    FacetLedger("Venues", stats.venues)
    FacetLedger("In good company", stats.companions)
    FacetLedger("Formats", stats.formats)
}

@Composable
private fun TicketField(
    label: String,
    value: String,
    alignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(horizontalAlignment = alignment) {
        Overline(label)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Trips per year as short columns under the tear line — the ticket's "date" field. Each
 *  column carries its own count and year as real text, so the bars add nothing a screen
 *  reader needs and the whole strip stays readable at palette-thumbnail scale. */
@Composable
private fun YearBars(byYear: List<LedgerCategoryCount>) {
    val maxCount = byYear.maxOf { it.count }.coerceAtLeast(1)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        byYear.forEach { entry ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                MonoFigure(
                    entry.count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier.fillMaxWidth().height(40.dp).padding(vertical = 3.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.62f)
                            .height((34.dp * entry.count / maxCount).coerceAtLeast(3.dp))
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                MonoFigure(entry.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** One tally group as receipt rows: label on the left, count on the right, dashed leader
 *  bridging the gap. The leader is drawn behind the whole row and knocked out by the two
 *  labels' own backgrounds, so it lands correctly for any label length. */
@Composable
private fun FacetLedger(heading: String, entries: List<LedgerCategoryCount>) {
    if (entries.isEmpty()) return
    val leaderColor = MaterialTheme.colorScheme.outlineVariant
    val knockout = MaterialTheme.colorScheme.surfaceContainer
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Overline(heading, color = MaterialTheme.colorScheme.onSurface)
        entries.forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .drawBehind {
                        val y = size.height / 2 + 2.dp.toPx()
                        drawLine(
                            color = leaderColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
                        )
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .background(knockout)
                        .padding(end = 6.dp),
                )
                MonoFigure(
                    entry.count.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.background(knockout).padding(start = 6.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Screening Nights — the week as a dial
// ---------------------------------------------------------------------------------------

/**
 * Screening Nights as a radial plot rather than seven bars. Day-of-week is cyclical — Sunday
 * runs back into Monday — so a left-to-right axis invents a start and an end the data doesn't
 * have, and makes "weekends" (the two ends of the axis) look maximally far apart when they're
 * actually adjacent. Wrapped into a ring, a weekend habit reads as one lobe.
 *
 * `weekdays` always arrives as exactly seven entries in Monday..Sunday order with localized
 * short names (`LedgerRepository.weekdays`), including zero-count days, so the ring is always
 * complete and the panel never has to gap-fill. The paired list underneath carries the counts
 * and each day's share, which is what the plot deliberately doesn't encode.
 */
@Composable
internal fun WeekdaysPanel(title: String, weekdays: List<LedgerWeekdayCount>) {
    PanelHeading(title, "Which nights of the week you actually watch")
    val total = weekdays.sumOf { it.count }
    if (total == 0) {
        PanelEmpty("No dated viewings logged yet — a screening needs a date to land on a night.")
        return
    }

    val peakIndex = weekdays.indices.maxBy { weekdays[it].count }
    val peak = weekdays[peakIndex]
    Text(
        "%s is your night — %d of %d screenings (%d%%).".format(
            peak.weekday,
            peak.count,
            total,
            (peak.count * 100.0 / total).roundToInt(),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    RadialSpokePlot(
        values = weekdays.map { it.count.toFloat() },
        labels = weekdays.map { it.weekday },
        highlightIndex = peakIndex,
        modifier = Modifier.padding(top = 4.dp),
    )

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        weekdays.forEachIndexed { index, entry ->
            val share = entry.count * 100.0 / total
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.weekday,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (index == peakIndex) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                )
                MonoFigure(
                    "%d · %d%%".format(entry.count, share.roundToInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (index == peakIndex) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// By the Genre — bubble field
// ---------------------------------------------------------------------------------------

/**
 * By the Genre as a bubble field, restoring the encoding the web app already uses and Android
 * had flattened to a plain list (ledger.md §2: "bubble size ∝ √(count/max)"). A tally of
 * overlapping labels — a title is Drama *and* Thriller — isn't really a ranking; it's a shape,
 * and circles show one genre dominating while the tail stays visible in a way a top-6 list of
 * numbers doesn't.
 *
 * The ranked list underneath is both the accessible pairing for the (cleared) bubbles and the
 * precise read the sizes deliberately don't give — it also mirrors the web app's own ranked-
 * list fallback at `sm` width. Entries arrive ranked by count and already capped to the
 * widget's effective `topN` by the caller.
 */
@Composable
internal fun GenresPanel(title: String, entries: List<LedgerCategoryCount>) {
    PanelHeading(title, "Your library's shape, by how often each genre is tagged")
    if (entries.isEmpty()) {
        PanelEmpty("No genres logged yet.")
        return
    }

    val shown = entries.sumOf { it.count }
    val lead = entries.first()
    Text(
        "%s leads — %d of the %d tags shown (%d%%).".format(
            lead.label,
            lead.count,
            shown,
            (lead.count * 100.0 / shown).roundToInt(),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    BubbleCloud(
        data = entries.map { ChartDatum(it.label, it.count.toFloat()) },
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
    )

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        entries.forEachIndexed { index, entry ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                    MonoFigure(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        entry.label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MonoFigure(
                    "%d · %d%%".format(entry.count, (entry.count * 100.0 / shown).roundToInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
