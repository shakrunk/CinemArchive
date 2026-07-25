package work.kumarfamilynet.cinemarchive.feature.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import work.kumarfamilynet.cinemarchive.core.designsystem.DivergingColumns
import work.kumarfamilynet.cinemarchive.core.designsystem.SegmentedProgressTrack
import work.kumarfamilynet.cinemarchive.core.model.LedgerPremiereRevivalBucket
import work.kumarfamilynet.cinemarchive.core.model.LedgerProgressEntry
import kotlin.math.roundToInt

/**
 * Two panels about the rhythm of watching rather than its totals, sharing
 * [LedgerWidgetPanels.kt][PanelHeading]'s chrome:
 *
 * - [RevivalsPanel] (Premieres & Revivals) — first watches against rewatches on a diverging
 *   baseline, replacing a chart that plotted the two *summed* and so answered nothing the
 *   widget's name asks.
 * - [ProgressPanel] (Still Rolling) — countable episode cells with the remainder called out,
 *   because "six episodes left" is what you act on, not "75% complete".
 */

// ---------------------------------------------------------------------------------------
// Premieres & Revivals — diverging months
// ---------------------------------------------------------------------------------------

/**
 * Premieres & Revivals as two counterpart series against a shared baseline: first watches
 * rising, rewatches falling.
 *
 * The old rendering plotted `premieres + revivals` as a single line, which is the one
 * transformation that makes this widget meaningless — the whole question is the *balance*
 * between discovering something and returning to it, and a sum answers only "did you watch
 * things that month". Split across a baseline, a month of pure rediscovery and a month of pure
 * rewatching stop looking identical.
 *
 * A premiere is a title's first-ever dated viewing and a revival is any later one
 * (`LedgerRepository.revivals`), so every dated viewing lands on exactly one side. Buckets are
 * gap-filled and chronological, default window 12 months.
 */
@Composable
internal fun RevivalsPanel(title: String, buckets: List<LedgerPremiereRevivalBucket>) {
    PanelHeading(title, "Discovering something new, against going back for more")
    if (buckets.isEmpty()) {
        PanelEmpty("No dated viewings logged yet.")
        return
    }

    val premieres = buckets.sumOf { it.premieres }
    val revivals = buckets.sumOf { it.revivals }
    val total = premieres + revivals
    if (total == 0) {
        PanelEmpty("No dated viewings in this window.")
        return
    }

    Text(
        "%d of %d screenings were first watches (%d%%); the rest were returns.".format(
            premieres,
            total,
            (premieres * 100.0 / total).roundToInt(),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeriesKey(MaterialTheme.colorScheme.primary, "First watch")
        SeriesKey(MaterialTheme.colorScheme.secondary, "Rewatch")
    }

    DivergingColumns(
        above = buckets.map { it.premieres.toFloat() },
        below = buckets.map { it.revivals.toFloat() },
        modifier = Modifier.padding(top = 4.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Overline(buckets.first().monthLabel)
        Overline(buckets.last().monthLabel)
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        buckets.forEach { bucket ->
            val description = "%s: %d first %s, %d %s".format(
                bucket.monthLabel,
                bucket.premieres,
                if (bucket.premieres == 1) "watch" else "watches",
                bucket.revivals,
                if (bucket.revivals == 1) "rewatch" else "rewatches",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { contentDescription = description },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    bucket.monthLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MonoFigure(
                        "${bucket.premieres} new",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    MonoFigure(
                        "${bucket.revivals} again",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesKey(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(color))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------
// Still Rolling — countable episodes left
// ---------------------------------------------------------------------------------------

/**
 * Still Rolling as a set of countable episode tracks. `12 / 24 episodes` as text is a fact you
 * have to subtract before it means anything; what you actually decide on is how many are left,
 * so the remainder leads each row and the track draws one cell per episode — you can see four
 * dark cells and know it's a short evening without reading a number at all.
 *
 * The header sums the remainder across every series shown, which is the figure the widget was
 * implicitly about and never stated. Entries arrive capped to the widget's effective `topN`
 * (default 5), so that total covers what's on screen rather than the whole library, and it says
 * so.
 */
@Composable
internal fun ProgressPanel(title: String, entries: List<LedgerProgressEntry>) {
    PanelHeading(title, "Series you're partway through, and what's left of them")
    if (entries.isEmpty()) {
        PanelEmpty("Nothing in progress — every series is either finished or unstarted.")
        return
    }

    val remaining = entries.sumOf { (it.episodeCount - it.episodesWatched).coerceAtLeast(0) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        MonoFigure(
            remaining.toString(),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.padding(start = 10.dp, bottom = 5.dp)) {
            Overline(if (remaining == 1) "episode left" else "episodes left")
            Text(
                if (entries.size == 1) "in 1 series shown" else "across ${entries.size} series shown",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        entries.forEach { entry -> ProgressTrackRow(entry) }
    }
}

@Composable
private fun ProgressTrackRow(entry: LedgerProgressEntry) {
    val left = (entry.episodeCount - entry.episodesWatched).coerceAtLeast(0)
    val description = "%s: %d of %d episodes watched, %d left".format(
        entry.title,
        entry.episodesWatched,
        entry.episodeCount,
        left,
    )
    Column(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
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
            MonoFigure(
                if (left == 0) "done" else "$left left",
                style = MaterialTheme.typography.labelMedium,
                color = if (left == 0) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        SegmentedProgressTrack(
            completed = entry.episodesWatched,
            total = entry.episodeCount,
            modifier = Modifier.padding(top = 5.dp),
        )
        Text(
            "${entry.episodesWatched} of ${entry.episodeCount} watched",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
