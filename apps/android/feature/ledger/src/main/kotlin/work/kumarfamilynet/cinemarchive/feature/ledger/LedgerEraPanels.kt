package work.kumarfamilynet.cinemarchive.feature.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import work.kumarfamilynet.cinemarchive.core.designsystem.DeviationAreaChart
import work.kumarfamilynet.cinemarchive.core.designsystem.EraSpine
import work.kumarfamilynet.cinemarchive.core.model.LedgerCategoryCount
import work.kumarfamilynet.cinemarchive.core.model.LedgerQuarterRating
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Two panels about time — the eras a library draws from, and how a viewer's own scoring has
 * drifted — sharing [LedgerWidgetPanels.kt][PanelHeading]'s chrome:
 *
 * - [DecadesPanel] (By the Era) — a timeline spine, because decades are contiguous positions
 *   rather than free-floating categories and the empty ones carry meaning.
 * - [TrajectoryPanel] (Shifting Standards) — quarterly averages against your own all-time
 *   average, because "shifting" is a claim about a reference the old chart never drew.
 */

// ---------------------------------------------------------------------------------------
// By the Era — the timeline spine
// ---------------------------------------------------------------------------------------

private const val DECADE_STEP = 10

/**
 * By the Era on an actual timeline. Decades are contiguous positions in time, and a plain
 * label/count list throws away the only relationship they have — that the 1970s come before the
 * 1980s, and that a decade you own nothing from is a *gap*, not an absent row.
 *
 * The repository tallies only decades that have titles, so this gap-fills the run from earliest
 * to latest before drawing: an empty decade gets a hollow tick on the spine and a zero row in
 * the list, which is what makes "my library skips the nineties entirely" visible at all.
 * Labels shorten to `'70s` on the spine, where a dozen `1970s` would collide, and stay long in
 * the list.
 */
@Composable
internal fun ColumnScope.DecadesPanel(
    title: String,
    decades: List<LedgerCategoryCount>,
    disclosure: PanelDisclosure,
) {
    PanelHeading(title, "Which eras your library actually draws from")
    if (decades.isEmpty()) {
        PanelEmpty("No dated titles logged yet.")
        return
    }

    val filled = gapFilledDecades(decades)
    val total = filled.sumOf { it.count }
    val peakIndex = filled.indices.maxBy { filled[it].count }
    val span = filled.size
    val populated = filled.count { it.count > 0 }

    val emptyDecades = span - populated
    Text(
        buildString {
            append(
                "%d titles across %d %s, from the %s to the %s".format(
                    total,
                    span,
                    if (span == 1) "decade" else "decades",
                    filled.first().label,
                    filled.last().label,
                ),
            )
            if (emptyDecades > 0) append(" — %d of them empty".format(emptyDecades))
            append(".")
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    EraSpine(
        values = filled.map { it.count.toFloat() },
        labels = filled.map { shortDecadeLabel(it.label) },
        highlightIndex = peakIndex,
        modifier = Modifier.padding(top = 4.dp),
    )

    PanelDetail(disclosure, "Show all ${filled.size} decades") {
        filled.forEach { entry ->
            val share = if (total > 0) entry.count * 100.0 / total else 0.0
            val description = "%s: %d %s, %d%%".format(
                entry.label,
                entry.count,
                if (entry.count == 1) "title" else "titles",
                share.roundToInt(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { contentDescription = description },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (entry.count == 0) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                MonoFigure(
                    if (entry.count == 0) "—" else "%d · %d%%".format(entry.count, share.roundToInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** `1970s` → `1970`; anything unparseable sorts to the end rather than throwing, since the
 *  label is repository-formatted display text and this is only a presentation concern. */
private fun decadeValue(label: String): Int? = label.removeSuffix("s").toIntOrNull()

private fun shortDecadeLabel(label: String): String =
    decadeValue(label)?.let { "'%02d".format(it % 100) + "s" } ?: label

/** Inserts the decades the repository omitted because they hold nothing — a timeline with the
 *  empty slots closed up would imply an unbroken run that doesn't exist. */
private fun gapFilledDecades(decades: List<LedgerCategoryCount>): List<LedgerCategoryCount> {
    val byDecade = decades.mapNotNull { entry -> decadeValue(entry.label)?.let { it to entry.count } }.toMap()
    if (byDecade.isEmpty()) return decades
    val first = byDecade.keys.min()
    val last = byDecade.keys.max()
    return generateSequence(first) { (it + DECADE_STEP).takeIf { next -> next <= last } }
        .map { decade -> LedgerCategoryCount("${decade}s", byDecade[decade] ?: 0) }
        .toList()
}

// ---------------------------------------------------------------------------------------
// Shifting Standards — drift against your own average
// ---------------------------------------------------------------------------------------

/**
 * Shifting Standards against the reference the widget's name implies. The old chart drew
 * quarterly averages as a bare line, which shows movement but not *drift* — "shifting" is a
 * claim relative to something, and with no baseline on the canvas the reader had to hold the
 * long-run average in their head and eyeball the comparison.
 *
 * The all-time average is now drawn as a dashed reference and the area between it and the line
 * is filled by sign, so a run of harsher quarters reads as a block below the line rather than as
 * a slightly lower squiggle. That average is weighted by each quarter's title count, not a mean
 * of the quarterly means — a quarter holding one title shouldn't pull the reference as hard as
 * one holding twenty.
 *
 * Ratings are on the 0–5 star scale a title carries, and a title lands in the quarter of its
 * first dated viewing (ledger.md §2).
 */
@Composable
internal fun ColumnScope.TrajectoryPanel(
    title: String,
    quarters: List<LedgerQuarterRating>,
    disclosure: PanelDisclosure,
) {
    PanelHeading(title, "Whether you've been scoring harder or softer than usual")
    if (quarters.isEmpty()) {
        PanelEmpty("No rated, dated titles yet.")
        return
    }

    val ratedTitles = quarters.sumOf { it.titleCount }
    if (ratedTitles == 0) {
        PanelEmpty("No rated, dated titles in this window.")
        return
    }
    val allTimeAverage = quarters.sumOf { it.averageRating * it.titleCount } / ratedTitles
    val latest = quarters.last()
    val drift = latest.averageRating - allTimeAverage

    Text(
        buildString {
            append("Your all-time average is %.1f★. ".format(allTimeAverage))
            when {
                abs(drift) < 0.05 -> append("${latest.quarterLabel} sits right on it.")
                drift > 0 -> append("${latest.quarterLabel} runs %.1f above it.".format(drift))
                else -> append("${latest.quarterLabel} runs %.1f below it.".format(-drift))
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    DeviationAreaChart(
        values = quarters.map { it.averageRating.toFloat() },
        baseline = allTimeAverage.toFloat(),
        modifier = Modifier.padding(top = 6.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Overline(quarters.first().quarterLabel)
        Overline("avg %.1f★".format(allTimeAverage))
        Overline(quarters.last().quarterLabel)
    }

    PanelDetail(disclosure, "Show all ${quarters.size} quarters") {
        quarters.forEach { quarter ->
            val delta = quarter.averageRating - allTimeAverage
            val description = "%s: averaged %.1f stars across %d %s, %s".format(
                quarter.quarterLabel,
                quarter.averageRating,
                quarter.titleCount,
                if (quarter.titleCount == 1) "title" else "titles",
                if (abs(delta) < 0.05) {
                    "level with your all-time average"
                } else {
                    "%.1f %s your all-time average".format(abs(delta), if (delta > 0) "above" else "below")
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { contentDescription = description },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(quarter.quarterLabel, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${quarter.titleCount} ${if (quarter.titleCount == 1) "title" else "titles"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    MonoFigure(
                        "%.1f★".format(quarter.averageRating),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    MonoFigure(
                        if (abs(delta) < 0.05) "±0.0" else "%s%.1f".format(if (delta > 0) "+" else "−", abs(delta)),
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            abs(delta) < 0.05 -> MaterialTheme.colorScheme.onSurfaceVariant
                            delta > 0 -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.secondary
                        },
                    )
                }
            }
        }
    }
}
