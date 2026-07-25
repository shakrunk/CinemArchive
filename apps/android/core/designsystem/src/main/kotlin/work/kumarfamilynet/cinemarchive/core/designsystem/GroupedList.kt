package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
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
