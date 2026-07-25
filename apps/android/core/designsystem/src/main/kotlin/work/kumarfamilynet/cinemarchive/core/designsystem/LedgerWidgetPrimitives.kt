package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shape-language primitives for Ledger widgets whose data doesn't read as a bar, line, or
 * heatmap — the three shapes in [LedgerCharts.kt][BarChartCanvas]'s file cover
 * "count per bucket" and "value over time," and every widget drawn with them ends up looking
 * like every other one. Each primitive here exists because one widget's data has a shape of
 * its own:
 *
 * - [ComparisonDumbbell] — two values of the *same* quantity on one shared scale, where the
 *   gap between them is the point (Second Opinions: your score vs. IMDb's).
 * - [FilmstripTrack] — a dense run of binary days where *consecutive* runs are the signal,
 *   not the per-day total (The Marathon's 30 nights).
 * - [TicketStub] — a container for a small set of unrelated headline figures that belong to
 *   one physical event (At the Movies' trip count, spend, and venue).
 * - [RadialSpokePlot] — a *cyclical* series where the last bucket is adjacent to the first,
 *   which a left-to-right axis actively misrepresents (Screening Nights' Mon–Sun).
 * - [BubbleCloud] — a ranked set where relative magnitude matters more than exact values and
 *   the long tail should still be visible (By the Genre).
 *
 * All of them are decorative in the same sense [BarChartCanvas] is: callers pair them with
 * real text (ledger.md §5). [FilmstripTrack] is the exception that takes a [String] description,
 * because the web app's equivalent grid has *no* per-night label at all and a run-encoded
 * summary is the accessible alternative §5 asks for.
 */

/** Horizontal padding [ComparisonDumbbell] reserves at each end so a marker sitting at 0.0 or
 *  1.0 draws fully inside the canvas. Exposed so a caller's own scale labels line up with the
 *  track the markers actually sit on. */
val LedgerTrackInset: Dp = 9.dp

/**
 * Two markers on one shared 0f..1f scale, joined by a bar — a dumbbell/barbell plot. Reads as
 * "these two disagree, and here's by how much," which is exactly the question Second Opinions
 * asks and which two separate numbers in a text row don't answer at a glance.
 *
 * [subjectFraction] is the viewer's own value (solid marker); [referenceFraction] is what it's
 * being compared against (hollow marker) — the fill/hollow contrast is what makes the pair
 * readable without a per-row legend. Both are clamped, so an out-of-domain value pins to an
 * end rather than drawing off-canvas. [knockoutColor] must match the surface behind the track:
 * each marker punches a hole in the connector so the two ends stay legible when they nearly
 * touch.
 */
@Composable
fun ComparisonDumbbell(
    subjectFraction: Float,
    referenceFraction: Float,
    modifier: Modifier = Modifier,
    subjectColor: Color = MaterialTheme.colorScheme.primary,
    referenceColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    connectorColor: Color = subjectColor.copy(alpha = 0.55f),
    railColor: Color = MaterialTheme.colorScheme.outlineVariant,
    knockoutColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    height: Dp = 22.dp,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics {},
    ) {
        val inset = LedgerTrackInset.toPx()
        val trackWidth = (size.width - inset * 2).coerceAtLeast(1f)
        val centerY = size.height / 2
        fun x(fraction: Float) = inset + trackWidth * fraction.coerceIn(0f, 1f)

        drawLine(
            color = railColor,
            start = Offset(inset, centerY),
            end = Offset(inset + trackWidth, centerY),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
        // Midpoint tick: the only axis chrome on the row, so a marker's rough position is
        // readable without tracing back up to the caller's scale labels.
        drawLine(
            color = railColor,
            start = Offset(x(0.5f), centerY - 7.dp.toPx()),
            end = Offset(x(0.5f), centerY + 7.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = connectorColor,
            start = Offset(x(subjectFraction), centerY),
            end = Offset(x(referenceFraction), centerY),
            strokeWidth = 7.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(knockoutColor, radius = 6.dp.toPx(), center = Offset(x(referenceFraction), centerY))
        drawCircle(
            color = referenceColor,
            radius = 6.dp.toPx(),
            center = Offset(x(referenceFraction), centerY),
            style = Stroke(width = 2.dp.toPx()),
        )
        drawCircle(knockoutColor, radius = 8.dp.toPx(), center = Offset(x(subjectFraction), centerY))
        drawCircle(subjectColor, radius = 6.dp.toPx(), center = Offset(x(subjectFraction), centerY))
    }
}

/**
 * A strip of 35mm film: sprocket-perforated bands top and bottom, one frame per entry in
 * [frames], lit where that day had activity. Where a heatmap answers "how much, per cell,"
 * this answers "how long a run" — adjacent lit frames merge into one continuous bright band,
 * which is the whole point of a streak widget.
 *
 * [highlight] marks the run that is still live (the current streak) in [highlightColor] plus
 * an underline in the lower sprocket band, so "my current run" is distinguishable from older
 * runs of the same length. Indices outside [frames] are ignored.
 *
 * Unlike the other primitives here, this one carries [contentDescription] rather than clearing
 * its semantics: ledger.md §5 calls the web app's unlabeled 30-night dot grid a real gap, and
 * a single run-encoded sentence ("23 of the last 30 nights: Jun 28 to Jul 2, Jul 5…") covers
 * every night while staying one TalkBack stop instead of thirty.
 */
@Composable
fun FilmstripTrack(
    frames: List<Boolean>,
    modifier: Modifier = Modifier,
    highlight: IntRange? = null,
    contentDescription: String? = null,
    litColor: Color = MaterialTheme.colorScheme.primary,
    highlightColor: Color = MaterialTheme.colorScheme.tertiary,
    unlitColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    stripColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    perforationColor: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    height: Dp = 66.dp,
) {
    if (frames.isEmpty()) return
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier.clearAndSetSemantics {}
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(semanticsModifier),
    ) {
        val corner = CornerRadius(6.dp.toPx())
        drawRoundRect(color = stripColor, size = size, cornerRadius = corner)

        val bandHeight = size.height * 0.21f
        val cellWidth = size.width / frames.size
        val gap = (cellWidth * 0.14f).coerceAtMost(2.dp.toPx())
        val frameTop = bandHeight
        val frameHeight = size.height - bandHeight * 2

        val holeWidth = (cellWidth * 0.42f).coerceAtLeast(1f)
        val holeHeight = bandHeight * 0.46f
        val holeCorner = CornerRadius(1.5.dp.toPx())
        frames.indices.forEach { index ->
            val holeX = index * cellWidth + (cellWidth - holeWidth) / 2
            listOf(bandHeight * 0.27f, size.height - bandHeight * 0.73f).forEach { holeY ->
                drawRoundRect(
                    color = perforationColor,
                    topLeft = Offset(holeX, holeY),
                    size = Size(holeWidth, holeHeight),
                    cornerRadius = holeCorner,
                )
            }
        }

        frames.forEachIndexed { index, lit ->
            val inRun = highlight != null && index in highlight
            val color = when {
                lit && inRun -> highlightColor
                lit -> litColor.copy(alpha = 0.85f)
                else -> unlitColor.copy(alpha = 0.16f)
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(index * cellWidth + gap / 2, frameTop),
                size = Size((cellWidth - gap).coerceAtLeast(1f), frameHeight),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
        }

        if (highlight != null && !highlight.isEmpty()) {
            val start = highlight.first.coerceIn(frames.indices)
            val end = highlight.last.coerceIn(frames.indices)
            val underlineY = size.height - bandHeight * 0.22f
            drawLine(
                color = highlightColor,
                start = Offset(start * cellWidth + gap / 2, underlineY),
                end = Offset((end + 1) * cellWidth - gap / 2, underlineY),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * A torn cinema ticket: one card with a dashed tear line and two punched notches separating a
 * [header] (the part you keep) from a [stub]. Built for widgets whose data is a handful of
 * unrelated headline figures about one physical event — a chart would imply a relationship
 * between "trips," "spend," and "venue" that doesn't exist, whereas a ticket is exactly the
 * object those figures were printed on.
 *
 * The tear tracks the header's *measured* height rather than a fixed offset, so it stays put
 * under the app's own font-scale setting (see [CinemArchiveTheme]) instead of drifting into
 * the header's last line. [minHeaderHeight] only sets the header's floor, which keeps the
 * proportions ticket-like when the header is short.
 */
@Composable
fun TicketStub(
    modifier: Modifier = Modifier,
    minHeaderHeight: Dp = 132.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    tearColor: Color = MaterialTheme.colorScheme.outlineVariant,
    notchRadius: Dp = 9.dp,
    cornerRadius: Dp = 14.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    header: @Composable ColumnScope.() -> Unit,
    stub: @Composable ColumnScope.() -> Unit,
) {
    var headerHeightPx by remember { mutableIntStateOf(0) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val tearY = if (headerHeightPx > 0) headerHeightPx.toFloat() else minHeaderHeight.toPx()
                val radius = notchRadius.toPx()
                val body = Path().apply {
                    addRoundRect(
                        RoundRect(
                            Rect(0f, 0f, size.width, size.height),
                            CornerRadius(cornerRadius.toPx()),
                        ),
                    )
                }
                val notches = Path().apply {
                    addOval(Rect(center = Offset(0f, tearY), radius = radius))
                    addOval(Rect(center = Offset(size.width, tearY), radius = radius))
                }
                val ticket = Path().apply { op(body, notches, PathOperation.Difference) }
                drawPath(ticket, containerColor)
                drawPath(ticket, borderColor, style = Stroke(width = 1.dp.toPx()))
                drawLine(
                    color = tearColor,
                    start = Offset(radius + 6.dp.toPx(), tearY),
                    end = Offset(size.width - radius - 6.dp.toPx(), tearY),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())),
                )
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = minHeaderHeight)
                .onSizeChanged { headerHeightPx = it.height }
                .padding(contentPadding),
            content = header,
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(contentPadding),
            content = stub,
        )
    }
}

/**
 * A closed radial plot: [values] laid out as evenly-spaced spokes around a circle, first entry
 * at twelve o'clock, joined into a filled polygon. Day-of-week counts are *cyclical* — Sunday
 * sits next to Monday — and a bar chart's left-to-right axis asserts a beginning and an end
 * that the data doesn't have. Wrapping the axis into a ring says "this repeats," and the
 * polygon's lopsidedness reads as "these nights, not those" at a glance.
 *
 * [values] are raw, not normalized: the outermost ring is the largest of them, so the shape
 * shows *relative* distribution and the caller's paired list carries the absolute figures.
 * [highlightIndex] emphasizes one vertex (typically the peak). An all-zero [values] draws the
 * grid alone rather than collapsing the polygon onto the centre point.
 *
 * Decorative — semantics are cleared, same pairing rule as [BarChartCanvas].
 */
@Composable
fun RadialSpokePlot(
    values: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    highlightIndex: Int = -1,
    fillColor: Color = MaterialTheme.colorScheme.primary,
    gridColor: Color = MaterialTheme.colorScheme.outlineVariant,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    highlightColor: Color = MaterialTheme.colorScheme.tertiary,
    labelStyle: TextStyle = MaterialTheme.typography.labelSmall,
    height: Dp = 196.dp,
) {
    if (values.isEmpty()) return
    val measurer = rememberTextMeasurer()
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics {},
    ) {
        val labelInset = 20.dp.toPx()
        val radius = (min(size.width, size.height) / 2f) - labelInset
        if (radius <= 0f) return@Canvas
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxValue = values.max().coerceAtLeast(0.0001f)
        val allZero = values.all { it <= 0f }
        val step = 2.0 * PI / values.size

        fun angleAt(index: Int): Double = -PI / 2.0 + index * step
        fun pointAt(index: Int, distance: Float): Offset {
            val angle = angleAt(index)
            return Offset(
                center.x + (distance * cos(angle)).toFloat(),
                center.y + (distance * sin(angle)).toFloat(),
            )
        }

        listOf(0.34f, 0.67f, 1f).forEach { ring ->
            drawCircle(color = gridColor, radius = radius * ring, center = center, style = Stroke(1.dp.toPx()))
        }
        values.indices.forEach { index ->
            drawLine(gridColor, center, pointAt(index, radius), strokeWidth = 1.dp.toPx())
        }

        if (!allZero) {
            val points = values.mapIndexed { index, value -> pointAt(index, radius * (value / maxValue)) }
            val shape = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(shape, fillColor.copy(alpha = 0.22f))
            drawPath(shape, fillColor, style = Stroke(width = 2.dp.toPx()))
            points.forEachIndexed { index, point ->
                val isPeak = index == highlightIndex
                drawCircle(
                    color = if (isPeak) highlightColor else fillColor,
                    radius = if (isPeak) 5.dp.toPx() else 3.5f.dp.toPx(),
                    center = point,
                )
            }
        }

        labels.forEachIndexed { index, label ->
            if (index >= values.size) return@forEachIndexed
            val anchor = pointAt(index, radius + labelInset * 0.55f)
            val laid = measurer.measure(label, labelStyle)
            drawText(
                textLayoutResult = laid,
                color = if (index == highlightIndex) highlightColor else labelColor,
                topLeft = Offset(anchor.x - laid.size.width / 2f, anchor.y - laid.size.height / 2f),
            )
        }
    }
}

/**
 * A field of circles, one per datum, area-ranked by value — the encoding the web app's genre
 * panel already uses (`docs/android-contracts/ledger.md` §2: "bubble size ∝ √(count/max)"),
 * which Android had flattened into a plain list. Diameter interpolates on √(value/max) rather
 * than value/max so the *area* tracks the count, which is how a reader actually judges circle
 * size; a linear diameter would exaggerate the leader by its square.
 *
 * Labels are real text inside the circles, not a canvas, so they stay crisp at any font scale
 * — but the whole field is cleared for screen readers, since a caller pairs it with a ranked
 * list carrying the same figures and announcing both would just double every entry.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BubbleCloud(
    data: List<ChartDatum>,
    modifier: Modifier = Modifier,
    minDiameter: Dp = 46.dp,
    maxDiameter: Dp = 98.dp,
    leadColor: Color = MaterialTheme.colorScheme.primary,
    onLeadColor: Color = MaterialTheme.colorScheme.onPrimary,
    bubbleColor: Color = MaterialTheme.colorScheme.primaryContainer,
    onBubbleColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    if (data.isEmpty()) return
    val maxValue = data.maxOf { it.value }.coerceAtLeast(0.0001f)
    FlowRow(
        modifier = modifier.fillMaxWidth().clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        data.forEachIndexed { index, datum ->
            val diameter = minDiameter + (maxDiameter - minDiameter) * sqrt(datum.value / maxValue)
            val isLead = index == 0
            Box(
                modifier = Modifier
                    .size(diameter)
                    .clip(CircleShape)
                    .background(if (isLead) leadColor else bubbleColor),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 6.dp),
                ) {
                    // Below ~62dp there isn't room for a name and a figure without one of them
                    // wrapping to an unreadable sliver, so small bubbles keep only the count and
                    // lean on the caller's ranked list for identification.
                    if (diameter >= 62.dp) {
                        Text(
                            datum.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isLead) onLeadColor else onBubbleColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Text(
                        datum.value.toInt().toString(),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isLead) onLeadColor else onBubbleColor,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
