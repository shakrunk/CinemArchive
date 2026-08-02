package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * The tinted/poster card shared by Discover, Library and Up Next: a rounded tile that shows
 * a real poster image when [imageUrl] is available, falling back to a flat [tint] otherwise,
 * with a bottom-anchored gradient scrim so overlaid text/badges stay legible — mirrors the
 * design handoff's repeated poster-card pattern (linear-gradient scrim over `t.tint`).
 */
@Composable
fun PosterSurface(
    tint: Color,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    aspectRatio: Float = 2f / 3f,
    // #159: was 26.dp — noticeably rounder than the app's shape scale (CinemArchiveTypography's
    // own "large" shape token is 16.dp), giving posters a softer look than intended.
    cornerRadius: androidx.compose.ui.unit.Dp = 16.dp,
    scrimStop: Float = 0.4f,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(cornerRadius))
            .background(tint)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            scrimStop to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.85f),
                        ),
                        startY = 0f,
                    ),
                ),
        )
        content()
    }
}

/**
 * Corner radius for a pinch-to-resize poster grid card (Discover/Library — see
 * [PosterGridColumnRange]), scaled to how much room [columns] actually leaves each tile. A flat
 * radius reads chunky once four columns narrow the poster and stingy at a single wide one.
 */
fun posterGridCornerRadius(columns: Int): androidx.compose.ui.unit.Dp = when {
    columns <= 1 -> 20.dp
    columns == 2 -> 16.dp
    columns == 3 -> 12.dp
    else -> 10.dp
}

/**
 * Minimum tile width to feed [androidx.compose.foundation.lazy.grid.GridCells.Adaptive] for a
 * given density preference ([PosterGridColumnRange] — pinch-adjustable, see
 * [Modifier.pinchToResizeGrid][pinchToResizeGrid]). `columns` is a density preference, not a
 * literal column count: `Adaptive` derives the actual count from whatever width it's given, so
 * the same preference naturally yields more columns on a wider screen instead of the same fixed
 * count stretched into oversized tiles.
 *
 * Solved (not estimated) against three real widths so each density's actual column count comes
 * out as intended at every one of them: a foldable's cover display (317dp physical / 277dp
 * usable after 20dp+20dp padding), a conservative phone (320dp usable), and that same foldable
 * unfolded (674dp physical / 634dp usable). At the default density (2), that resolves to
 * 2/2/4 columns respectively — i.e. the unfolded width shows twice as many columns as either
 * phone-width state, matching two phone-widths of content side by side rather than one phone's
 * column count stretched across both.
 */
fun posterMinTileWidth(columns: Int): androidx.compose.ui.unit.Dp = when (columns) {
    1 -> 260.dp
    2 -> 125.dp
    3 -> 96.dp
    4 -> 68.dp
    else -> 125.dp
}

/** A deterministic, decorative fallback tint derived from a title's id/name — used when no
 *  poster image is available, so cards stay visually distinct rather than uniformly gray. */
fun tintForKey(key: String): Color {
    val palette = listOf(
        Color(0xFF6B7480), Color(0xFF8A6A4F), Color(0xFF506B66), Color(0xFF7A5C6E),
        Color(0xFF5E5A72), Color(0xFF5C6B5A), Color(0xFF7C6B54), Color(0xFF6F5450),
        Color(0xFF4F6675), Color(0xFF7A6048),
    )
    val idx = (key.sumOf { it.code } % palette.size).let { if (it < 0) it + palette.size else it }
    return palette[idx]
}
