package work.kumarfamilynet.cinemarchive.feature.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import work.kumarfamilynet.cinemarchive.core.designsystem.ProportionalStackBar
import work.kumarfamilynet.cinemarchive.core.model.LedgerCategoryCount
import work.kumarfamilynet.cinemarchive.core.model.LedgerWatchlistEntry
import kotlin.math.roundToInt

/**
 * Two more Ledger panels rebuilt around their own data shape, sharing
 * [LedgerWidgetPanels.kt][PanelHeading]'s chrome — split into a second file only because that
 * one had grown past comfortable reading, not because these are a different kind of thing:
 *
 * - [AttractionsPanel] (Coming Attractions) — a part-to-whole backlog bar plus a *running*
 *   total, because a watchlist's real question is "how much have I signed up for", which a
 *   list of names answers nowhere.
 * - [RatingsPanel] (Critical Record) — a distribution with its centre of gravity flagged,
 *   because ten fixed ordinal buckets carry their meaning in their shape.
 */

// ---------------------------------------------------------------------------------------
// Coming Attractions — the runtime backlog
// ---------------------------------------------------------------------------------------

/** Beyond this the individual segments become hairlines; the rest collapses into one muted
 *  block so the bar keeps meaning something. */
private const val QUEUE_SEGMENT_LIMIT = 12

/**
 * Coming Attractions as a backlog rather than a list. The titles are already legible as text;
 * what the old rows never answered is how much viewing is queued up. So the total leads, the
 * queue is drawn as one bar broken into per-film segments, and every row carries a running
 * total — read down the column and you can stop where the evening runs out.
 *
 * Only films count toward the estimate, matching `watchlistMovieMinutesOwed` and ledger.md §2's
 * rule that `hoursOwed` sums movie runtimes only. A series is listed but marked untimed rather
 * than folded in on the strength of an episode runtime, which is why
 * [LedgerWatchlistEntry.isMovie] exists — without it the per-title figures would drift from the
 * header total.
 */
private const val QUEUE_PREVIEW_ROWS = 3

@Composable
internal fun ColumnScope.AttractionsPanel(
    title: String,
    entries: List<LedgerWatchlistEntry>,
    movieMinutesOwed: Int,
    disclosure: PanelDisclosure,
) {
    PanelHeading(title, "What's queued up, and how long it would take to clear")
    if (entries.isEmpty()) {
        PanelEmpty("Nothing on the watchlist — the queue is clear.")
        return
    }

    val timed = entries.filter { it.isMovie && (it.runtimeMinutes ?: 0) > 0 }
    val untimedCount = entries.size - timed.size

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        MonoFigure(
            formatRuntime(movieMinutesOwed),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.padding(start = 10.dp, bottom = 5.dp)) {
            Overline("runtime owed")
            Text(
                if (timed.size == 1) "across 1 film" else "across ${timed.size} films",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (timed.isNotEmpty()) {
        ProportionalStackBar(
            values = timed.take(QUEUE_SEGMENT_LIMIT).map { (it.runtimeMinutes ?: 0).toFloat() },
            remainder = timed.drop(QUEUE_SEGMENT_LIMIT).sumOf { it.runtimeMinutes ?: 0 }.toFloat(),
            modifier = Modifier.padding(top = 10.dp),
        )
        if (timed.size > QUEUE_SEGMENT_LIMIT) {
            Text(
                "Bar shows the first $QUEUE_SEGMENT_LIMIT films; the rest is the block at the end.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    if (untimedCount > 0) {
        Text(
            if (untimedCount == 1) {
                "1 more title has no runtime to count — series aren't part of the estimate."
            } else {
                "$untimedCount more titles have no runtime to count — series aren't part of the estimate."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    // A running total, not just each film's runtime: the column answers "how far in am I by the
    // time this one ends", which is the only reason to put a backlog in an order at all.
    // Accumulated up front rather than during rendering, so a collapsed tail doesn't reset the
    // running figure the visible rows are counting toward.
    var cumulative = 0
    val queue = entries.map { entry ->
        val counts = entry.isMovie && (entry.runtimeMinutes ?: 0) > 0
        if (counts) cumulative += entry.runtimeMinutes ?: 0
        QueueStep(entry, cumulative.takeIf { counts })
    }
    DisclosedList(queue, disclosure, "queued titles", previewCount = QUEUE_PREVIEW_ROWS, spacing = 7.dp) { step ->
        QueueRow(entry = step.entry, cumulativeMinutes = step.cumulativeMinutes)
    }
}

/** One watchlist row plus the running total it lands on — precomputed so the disclosure can
 *  split the list anywhere without the totals restarting. Null [cumulativeMinutes] means the
 *  title doesn't count toward the estimate (a series, or a film with no runtime). */
private data class QueueStep(val entry: LedgerWatchlistEntry, val cumulativeMinutes: Int?)

@Composable
private fun QueueRow(entry: LedgerWatchlistEntry, cumulativeMinutes: Int?) {
    val description = buildString {
        append(entry.title)
        entry.year?.let { append(", $it") }
        if (cumulativeMinutes != null) {
            append(", ${formatRuntime(entry.runtimeMinutes ?: 0)}")
            append(", ${formatRuntime(cumulativeMinutes)} into the queue")
        } else {
            append(", no runtime counted")
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    entry.year?.toString(),
                    if (cumulativeMinutes != null) formatRuntime(entry.runtimeMinutes ?: 0) else "series",
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MonoFigure(
            cumulativeMinutes?.let { formatRuntime(it) } ?: "—",
            style = MaterialTheme.typography.bodySmall,
            color = if (cumulativeMinutes != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** `154` → `2h 34m`, `48` → `48m`. Hours are omitted rather than shown as `0h` so short
 *  entries don't read as padded. */
private fun formatRuntime(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0)
    val hours = safe / 60
    val remainder = safe % 60
    return if (hours > 0) "${hours}h ${remainder}m" else "${remainder}m"
}

// ---------------------------------------------------------------------------------------
// Critical Record — the rating distribution
// ---------------------------------------------------------------------------------------

private const val RATING_TOP = 5.0
private const val RATING_STEP = 0.5

/**
 * Critical Record as a distribution with its centre of gravity marked. The buckets are ordinal
 * and fixed — ten of them, 5.0 down to 0.5, always all present including the empty ones (see
 * `LedgerRepository.ratingBuckets`) — so the *shape* is the information: where the mass sits,
 * how lopsided it is, whether anything at all lives below three stars. A label/count list gives
 * you ten numbers and no shape.
 *
 * Bars fade as the rating drops, the most-used bucket is picked out, and the row the average
 * falls in is flagged, so "I mostly give fours, and I average just under that" reads without
 * arithmetic. Bucket values come from position rather than from parsing the `★4.5` labels back
 * apart — that sequence is fixed by the repository, and the label is display text.
 *
 * The one panel with no [PanelDisclosure]: its rows *are* its visualization, so collapsing them
 * would leave a card with nothing on it but a sentence. Ten fixed buckets at one line each is
 * short enough not to need it.
 */
@Composable
internal fun ColumnScope.RatingsPanel(title: String, buckets: List<LedgerCategoryCount>) {
    PanelHeading(title, "How your ratings fall, five stars down to half a star")
    val total = buckets.sumOf { it.count }
    if (buckets.isEmpty() || total == 0) {
        PanelEmpty("No ratings logged yet.")
        return
    }

    val average = buckets.withIndex()
        .sumOf { (index, bucket) -> (RATING_TOP - index * RATING_STEP) * bucket.count } / total
    val modalIndex = buckets.indices.maxBy { buckets[it].count }
    // Which row the average lands in, so it's flagged where the reader is already looking.
    val averageIndex = ((RATING_TOP - average) / RATING_STEP).roundToInt().coerceIn(buckets.indices)
    val maxCount = buckets.maxOf { it.count }.coerceAtLeast(1)

    Text(
        "You average %.1f★ — %s is your most common verdict, %d of %d rated.".format(
            average,
            buckets[modalIndex].label,
            buckets[modalIndex].count,
            total,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        buckets.forEachIndexed { index, bucket ->
            RatingBucketRow(
                bucket = bucket,
                fraction = bucket.count.toFloat() / maxCount,
                fade = index.toFloat() / (buckets.size - 1).coerceAtLeast(1),
                isModal = index == modalIndex && bucket.count > 0,
                isAverage = index == averageIndex,
                share = bucket.count * 100.0 / total,
            )
        }
    }
}

@Composable
private fun RatingBucketRow(
    bucket: LedgerCategoryCount,
    fraction: Float,
    fade: Float,
    isModal: Boolean,
    isAverage: Boolean,
    share: Double,
) {
    val barColor = if (isModal) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.9f - 0.5f * fade)
    }
    val description = "%s: %d %s, %d%%%s".format(
        bucket.label,
        bucket.count,
        if (bucket.count == 1) "title" else "titles",
        share.roundToInt(),
        if (isAverage) ", your average sits here" else "",
    )
    Row(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoFigure(
            bucket.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isAverage) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(end = 8.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(14.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(barColor),
                )
            }
        }
        MonoFigure(
            bucket.count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 8.dp).width(28.dp),
        )
        // Reserved even when absent so the count column stays aligned down the ten rows.
        Text(
            if (isAverage) "avg" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(start = 6.dp).width(22.dp),
        )
    }
}
