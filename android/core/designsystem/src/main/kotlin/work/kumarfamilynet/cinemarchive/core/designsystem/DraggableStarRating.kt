package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Star rating field. Collapses to a plain read-only star row + numeric value (tap to change,
 * pencil icon signals it's editable) and opens a modal picker to actually set the rating —
 * mirrors the M3 TimePicker's dialog-collapses-to-text pattern rather than exposing the drag
 * surface inline.
 *
 * The inline drag row this replaced wasn't discoverable as a slider on its own (testing
 * surfaced a user who only tapped individual stars, assuming whole-star precision was the
 * ceiling). Rather than layer more hints onto a compact inline control, the picker now gets a
 * dedicated modal with room for an actual slider: a gradient track that fills as you drag and
 * an elevated, ringed handle sitting on top of it — the same visual language as a standard
 * Material slider thumb, which reads as "grab this" without needing an explainer.
 */
@Composable
fun DraggableStarRating(
    rating: Double,
    onRatingChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    maxStars: Int = 5,
) {
    var showPicker by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { showPicker = true }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row {
            for (n in 1..maxStars) {
                val state = when {
                    rating >= n -> StarState.Full
                    rating >= n - 0.5 -> StarState.Half
                    else -> StarState.Empty
                }
                StarGlyph(state = state, popKey = 0, boxSize = 28.dp, iconSize = 18.dp)
            }
        }
        Text(
            "%.1f".format(rating),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
        Icon(
            Icons.Filled.Edit,
            contentDescription = "Change rating",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 8.dp).size(14.dp),
        )
    }

    if (showPicker) {
        Dialog(
            onDismissRequest = { showPicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            StarRatingPicker(
                rating = rating,
                onRatingChange = onRatingChange,
                maxStars = maxStars,
                onDone = { showPicker = false },
            )
        }
    }
}

@Composable
private fun StarRatingPicker(
    rating: Double,
    onRatingChange: (Double) -> Unit,
    maxStars: Int,
    onDone: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf<Double?>(null) }
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    val starPop = remember { mutableStateMapOf<Int, Int>() }
    val nudgeOnMount = remember { rating <= 0.0 }

    val displayed = dragValue ?: rating

    fun valueFromX(x: Float): Double {
        if (trackWidthPx <= 0f) return displayed
        val perStar = trackWidthPx / maxStars
        val idx = (x / perStar).toInt().coerceIn(0, maxStars - 1)
        val frac = (x - idx * perStar) / perStar
        val half = if (frac < 0.5f) 0.5 else 1.0
        return (idx + half).coerceIn(0.5, maxStars.toDouble())
    }

    fun bump(old: Double, new: Double) {
        for (n in 1..maxStars) {
            val o = if (old >= n) 2 else if (old >= n - 0.5) 1 else 0
            val j = if (new >= n) 2 else if (new >= n - 0.5) 1 else 0
            if (o != j) starPop[n] = (starPop[n] ?: 0) + 1
        }
    }

    val dragModifier = Modifier.pointerInput(maxStars) {
        awaitEachGesture {
            val down = awaitFirstDown()
            isDragging = true
            var current = valueFromX(down.position.x)
            bump(rating, current)
            dragValue = current
            onRatingChange(current)
            down.consume()
            var pressed = true
            while (pressed) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) {
                    pressed = false
                } else if (change.pressed) {
                    val next = valueFromX(change.position.x)
                    if (next != current) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        bump(current, next)
                        current = next
                        dragValue = current
                        onRatingChange(current)
                    }
                    change.consume()
                } else {
                    pressed = false
                }
            }
            isDragging = false
            dragValue = null
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(0.9f),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                "Rate this title",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "%.1f".format(displayed),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
            Column(modifier = dragModifier.onSizeChanged { trackWidthPx = it.width.toFloat() }) {
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    for (n in 1..maxStars) {
                        val state = when {
                            displayed >= n -> StarState.Full
                            displayed >= n - 0.5 -> StarState.Half
                            else -> StarState.Empty
                        }
                        StarGlyph(state = state, popKey = starPop[n] ?: 0, boxSize = 48.dp, iconSize = 30.dp)
                    }
                }
                SliderTrack(
                    fraction = (displayed / maxStars).toFloat().coerceIn(0f, 1f),
                    isActive = isDragging,
                    nudgeOnMount = nudgeOnMount,
                    modifier = Modifier.padding(top = 10.dp, start = 10.dp, end = 10.dp),
                )
            }
            Text(
                "Drag anywhere above — half-star precision",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 14.dp, start = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                TextButton(onClick = onDone) { Text("Done") }
            }
        }
    }
}

@Composable
private fun SliderTrack(
    fraction: Float,
    isActive: Boolean,
    nudgeOnMount: Boolean,
    modifier: Modifier = Modifier,
) {
    val trackHeight by animateDpAsState(if (isActive) 10.dp else 7.dp, label = "ratingTrackHeight")
    val handleSize by animateDpAsState(if (isActive) 30.dp else 24.dp, label = "ratingHandleSize")

    val nudge = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (nudgeOnMount) {
            val bump = tween<Float>(220)
            nudge.animateTo(1f, bump)
            nudge.animateTo(0f, bump)
            nudge.animateTo(1f, bump)
            nudge.animateTo(0f, bump)
        }
    }

    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val fillStart = MaterialTheme.colorScheme.primaryContainer
    val fillEnd = MaterialTheme.colorScheme.primary
    val handleColor = MaterialTheme.colorScheme.primary
    val handleRing = MaterialTheme.colorScheme.surfaceContainerHigh
    val glowColor = MaterialTheme.colorScheme.primary

    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(handleSize + 8.dp)) {
        val widthDp = maxWidth
        val thumbFraction = (fraction + nudge.value * 0.1f).coerceIn(0f, 1f)
        val rawOffset = widthDp * thumbFraction - handleSize / 2
        val handleOffset = rawOffset.coerceIn(0.dp, (widthDp - handleSize).coerceAtLeast(0.dp))

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(widthDp)
                .height(trackHeight)
                .clip(RoundedCornerShape(50)),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(trackColor))
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(Brush.horizontalGradient(listOf(fillStart, fillEnd))),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = handleOffset - 8.dp)
                .size(handleSize + 16.dp)
                .background(
                    Brush.radialGradient(listOf(glowColor.copy(alpha = 0.28f), Color.Transparent)),
                    CircleShape,
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = handleOffset)
                .shadow(elevation = 4.dp, shape = CircleShape)
                .size(handleSize)
                .clip(CircleShape)
                .background(handleColor)
                .border(2.5.dp, handleRing, CircleShape),
        )
    }
}

private enum class StarState { Full, Half, Empty }

@Composable
private fun StarGlyph(state: StarState, popKey: Int, boxSize: Dp = 40.dp, iconSize: Dp = 24.dp) {
    val pop = remember { Animatable(1f) }
    LaunchedEffect(popKey) {
        if (popKey > 0) {
            pop.snapTo(0.55f)
            pop.animateTo(1f, expressiveSpring())
        }
    }
    val bgFraction = if (boxSize.value > 0f) boxSize.value / 40f else 1f
    val bgScale by animateDpAsState(
        if (state == StarState.Empty) 0.dp else if (state == StarState.Half) 30.dp * bgFraction else 34.dp * bgFraction,
        label = "starBgScale",
    )
    val bgColor = when (state) {
        StarState.Full -> MaterialTheme.colorScheme.primaryContainer
        StarState.Half -> MaterialTheme.colorScheme.secondaryContainer
        StarState.Empty -> Color.Transparent
    }
    val bgShape = if (state == StarState.Half) RoundedCornerShape(45) else CircleShape
    val tint = if (state != StarState.Empty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline

    Box(
        modifier = Modifier
            .size(boxSize)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(bgScale)
                .clip(bgShape)
                .background(bgColor),
        )
        Icon(Icons.Filled.Star, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}
