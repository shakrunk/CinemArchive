package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Column counts a poster grid can pinch between. */
val PosterGridColumnRange = 1..4

/** How far apart / together the fingers must travel before the count steps. */
private const val ZOOM_IN_STEP = 1.25f
private const val ZOOM_OUT_STEP = 0.8f

/**
 * Pinch a poster grid between [PosterGridColumnRange] columns: spreading fingers gives fewer,
 * larger posters; pinching gives more, smaller ones.
 *
 * Hand-rolled rather than `Modifier.transformable`, which begins tracking on a single pointer
 * and so would swallow the grid's vertical scroll. This only engages once a second finger is
 * down, and steps at most once per gesture so a slow pinch doesn't race through the range.
 */
fun Modifier.pinchToResizeGrid(
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    range: IntRange = PosterGridColumnRange,
): Modifier = pointerInput(columns, range) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoom = 1f
        var stepped = false
        while (true) {
            // Initial pass: this modifier sits outside the grid's own scrollable, and the
            // Main pass runs inner-to-outer — so by the time it reached us the scroll would
            // already have claimed the drag and the list would slide during the pinch.
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.none { it.pressed }) break
            // One finger: leave the event alone so the grid scrolls as usual.
            if (event.changes.count { it.pressed } < 2) continue

            zoom *= event.calculateZoom()
            if (!stepped) {
                val next = when {
                    zoom >= ZOOM_IN_STEP -> columns - 1
                    zoom <= ZOOM_OUT_STEP -> columns + 1
                    else -> columns
                }
                if (next != columns && next in range) {
                    onColumnsChange(next)
                    stepped = true
                }
            }
            // Claim the pinch so it doesn't also scroll the grid underneath.
            event.changes.forEach { if (it.pressed) it.consume() }
        }
    }
}
