package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/**
 * Simple bucket/value pair for the bar and heatmap primitives below — deliberately not
 * shared with any Ledger widget's own model type, since a chart primitive shouldn't know
 * about domain concepts like "decade" or "week".
 */
data class ChartDatum(val label: String, val value: Float)

/**
 * A minimal Canvas bar chart, purely decorative — every widget using this pairs it with a
 * real, focusable per-datum list rendered alongside it (see
 * docs/android-contracts/ledger.md §5: "Android should give every widget a genuine
 * accessible alternative... rather than mirroring the web app's current tooltip-only
 * fallback"). [clearAndSetSemantics] with no properties makes TalkBack skip straight past
 * the canvas to that list instead of announcing an empty, data-free node.
 */
@Composable
fun BarChartCanvas(
    data: List<ChartDatum>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
) {
    if (data.isEmpty()) return
    val maxValue = data.maxOf { it.value }.coerceAtLeast(1f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .clearAndSetSemantics {},
    ) {
        val barWidth = size.width / data.size
        val gap = barWidth * 0.2f
        data.forEachIndexed { index, datum ->
            val barHeight = size.height * (datum.value / maxValue)
            drawRect(
                color = barColor,
                topLeft = Offset(index * barWidth + gap / 2, size.height - barHeight),
                size = Size(barWidth - gap, barHeight),
            )
        }
    }
}

/**
 * A row of intensity-shaded cells (one per bucket, e.g. one per week) — the simplified
 * Android analogue of the web app's daily 52-week Activity heatmap, bucketed at week
 * granularity to match ledger.md §2's own visible-week-count description rather than a
 * 364-cell daily grid. Decorative only, same accessible-list pairing as [BarChartCanvas].
 */
@Composable
fun HeatmapRow(
    values: List<Int>,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
) {
    if (values.isEmpty()) return
    val maxValue = values.max().coerceAtLeast(1)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        values.forEach { count ->
            val alpha = if (count == 0) 0.08f else 0.25f + 0.75f * (count.toFloat() / maxValue)
            Canvas(modifier = Modifier.weight(1f).height(24.dp)) {
                drawRect(color = activeColor.copy(alpha = alpha))
            }
        }
    }
}

/**
 * A true daily 7×52 grid (columns = weeks, rows = Monday..Sunday), the higher-resolution
 * decorative primitive [HeatmapRow] left room for — see
 * [work.kumarfamilynet.cinemarchive.data.LedgerRepository]'s `dailyActivity()` kdoc. [values]
 * must have exactly `weeks * 7` entries, oldest first, Monday-aligned (index 0 = the Monday
 * `weeks` weeks ago). Decorative only, same accessible-list pairing as [BarChartCanvas] — the
 * widget's existing weekly accessible list is untouched by this upgrade.
 */
@Composable
fun DailyHeatmapGrid(
    values: List<Int>,
    modifier: Modifier = Modifier,
    weeks: Int = 52,
    activeColor: Color = MaterialTheme.colorScheme.primary,
) {
    if (values.isEmpty()) return
    val maxValue = values.max().coerceAtLeast(1)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .clearAndSetSemantics {},
    ) {
        val cellWidth = size.width / weeks
        val cellHeight = size.height / 7
        val gap = cellWidth.coerceAtMost(cellHeight) * 0.15f
        values.forEachIndexed { index, count ->
            val week = index / 7
            val dayOfWeek = index % 7
            val alpha = if (count == 0) 0.08f else 0.25f + 0.75f * (count.toFloat() / maxValue)
            drawRect(
                color = activeColor.copy(alpha = alpha),
                topLeft = Offset(week * cellWidth + gap / 2, dayOfWeek * cellHeight + gap / 2),
                size = Size(cellWidth - gap, cellHeight - gap),
            )
        }
    }
}

/**
 * A stroke-path line chart with an optional gradient fill beneath — the primitive
 * [BarChartCanvas] didn't cover for trend widgets (Shifting Standards, Premieres & Revivals)
 * where a connected line reads as "trend" more clearly than discrete bars. No animation
 * (Compose Canvas doesn't get web's CSS `pathLength` draw-in for free, and chasing that exact
 * effect wasn't judged worth the implementation cost — see this repository's Ledger parity
 * plan). Decorative only, same accessible-list pairing as [BarChartCanvas].
 */
@Composable
fun LineChartCanvas(
    data: List<ChartDatum>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    showGradientFill: Boolean = true,
) {
    if (data.isEmpty()) return
    val maxValue = data.maxOf { it.value }.coerceAtLeast(0.0001f)
    val minValue = data.minOf { it.value }.coerceAtMost(0f)
    val range = (maxValue - minValue).coerceAtLeast(0.0001f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .clearAndSetSemantics {},
    ) {
        val step = if (data.size > 1) size.width / (data.size - 1) else 0f
        val points = data.mapIndexed { index, datum ->
            val x = if (data.size > 1) index * step else size.width / 2
            val y = size.height * (1f - (datum.value - minValue) / range)
            Offset(x, y)
        }
        val linePath = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (point in points.drop(1)) lineTo(point.x, point.y)
        }
        if (showGradientFill) {
            val fillPath = Path().apply {
                addPath(linePath)
                lineTo(points.last().x, size.height)
                lineTo(points.first().x, size.height)
                close()
            }
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.25f), lineColor.copy(alpha = 0f))),
                style = Fill,
            )
        }
        drawPath(path = linePath, color = lineColor, style = Stroke(width = 3.dp.toPx()))
        points.forEach { point -> drawCircle(color = lineColor, radius = 3.dp.toPx(), center = point) }
    }
}

/**
 * A thin scatter/line plot for delta-style data (e.g. rating-vs-IMDb comparisons) — points
 * only, no smoothing, no axis chrome (labels live in the paired accessible list). Values are
 * pre-normalized to 0f..1f by the caller.
 */
@Composable
fun DeltaScatterCanvas(
    normalizedPositions: List<Float>,
    modifier: Modifier = Modifier,
    pointColor: Color = MaterialTheme.colorScheme.primary,
) {
    if (normalizedPositions.isEmpty()) return
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clearAndSetSemantics {},
    ) {
        val midY = size.height / 2
        drawLine(
            color = pointColor.copy(alpha = 0.3f),
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = Stroke.DefaultMiter,
        )
        val step = if (normalizedPositions.size > 1) size.width / (normalizedPositions.size - 1) else 0f
        normalizedPositions.forEachIndexed { index, position ->
            val x = if (normalizedPositions.size > 1) index * step else size.width / 2
            val y = size.height * (1f - position)
            drawCircle(color = pointColor, radius = 4.dp.toPx(), center = Offset(x, y))
        }
    }
}
