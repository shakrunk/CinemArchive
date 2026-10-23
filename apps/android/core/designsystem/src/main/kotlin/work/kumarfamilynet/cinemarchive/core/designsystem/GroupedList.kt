package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/* ── M3 Expressive grouped list container ─────────────────────────────────────
   A run of rows reads as one grouped shape rather than a stack of separate cards:
   only the group's outer corners take the large radius, and the seams between
   rows stay tight. A single-row group is both first and last, so it rounds fully. */

/** Radius on the corners at the very top/bottom of a group. */
val GroupedOuterCorner = 20.dp

/** Radius on the corners that meet a neighbouring row. */
val GroupedInnerCorner = 6.dp

/** Gap to leave between rows so the seam reads as a hairline, not a margin. */
val GroupedSeamGap = 2.dp

fun groupedItemShape(isFirst: Boolean, isLast: Boolean): Shape = RoundedCornerShape(
    topStart = if (isFirst) GroupedOuterCorner else GroupedInnerCorner,
    topEnd = if (isFirst) GroupedOuterCorner else GroupedInnerCorner,
    bottomEnd = if (isLast) GroupedOuterCorner else GroupedInnerCorner,
    bottomStart = if (isLast) GroupedOuterCorner else GroupedInnerCorner,
)

/**
 * [groupedItemShape] for a group whose rows come and go, such as a filtered or synced list: when a
 * neighbour leaves or arrives the row's corners ease between [outerCorner] and [innerCorner]
 * instead of snapping, so the group re-forms around the gap as the rows glide into place (see
 * [animatedListItem]). A static group keeps [groupedItemShape].
 */
@Composable
fun animateGroupedItemShape(
    isFirst: Boolean,
    isLast: Boolean,
    outerCorner: Dp = GroupedOuterCorner,
    innerCorner: Dp = GroupedInnerCorner,
): Shape {
    val top by animateDpAsState(
        targetValue = if (isFirst) outerCorner else innerCorner,
        animationSpec = ArchiveMotion.defaultSpatialSpec(),
        label = "groupedTopCorner",
    )
    val bottom by animateDpAsState(
        targetValue = if (isLast) outerCorner else innerCorner,
        animationSpec = ArchiveMotion.defaultSpatialSpec(),
        label = "groupedBottomCorner",
    )
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}
