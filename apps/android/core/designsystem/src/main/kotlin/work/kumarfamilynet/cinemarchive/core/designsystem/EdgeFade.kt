package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** How far in from an edge a [horizontalEdgeFade] reaches: enough to read as "more this way". */
val EdgeFadeWidth = 24.dp

/**
 * Fades a sideways-scrolling row out at whichever end still has more to scroll to, so a card cut
 * off by the edge reads as "more this way" rather than a hard crop. At the very start or end the
 * edge stays crisp, so the first card never looks clipped.
 *
 * Fades the content itself (a destination-in mask on an offscreen layer) rather than drawing a
 * strip of background color over it, so it works over any surface, tinted cards included. The
 * scroll position is read while drawing, so scrolling redraws the fade without recomposing.
 */
fun Modifier.horizontalEdgeFade(state: ScrollableState, length: Dp = EdgeFadeWidth): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = length.toPx().coerceAtMost(size.width / 2)
            // canScrollBackward is toward the layout's start, which is the right-hand edge in RTL
            val rtl = layoutDirection == LayoutDirection.Rtl
            val fadeLeft = if (rtl) state.canScrollForward else state.canScrollBackward
            val fadeRight = if (rtl) state.canScrollBackward else state.canScrollForward
            if (fadeLeft) {
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
                    size = Size(fade, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (fadeRight) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Black, Color.Transparent),
                        startX = size.width - fade,
                        endX = size.width,
                    ),
                    topLeft = Offset(size.width - fade, 0f),
                    size = Size(fade, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
