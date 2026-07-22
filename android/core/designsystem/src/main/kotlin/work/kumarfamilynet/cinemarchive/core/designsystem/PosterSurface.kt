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
    cornerRadius: androidx.compose.ui.unit.Dp = 26.dp,
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
