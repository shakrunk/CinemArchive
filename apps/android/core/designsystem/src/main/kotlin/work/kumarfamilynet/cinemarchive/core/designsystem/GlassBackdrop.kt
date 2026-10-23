package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/* ── Glass chrome ─────────────────────────────────────────────────────────────
   Bars that float over scrolling content blur what passes beneath them instead
   of standing as a solid slab, so the posters keep reading through the chrome.
   Haze does the blurring; it stays an implementation detail of this file so the
   rest of the app deals only in [GlassBackdrop]. Blur needs RenderEffect (API 31),
   which is this app's minSdk, so there's no older-device path to handle. */

/** The content a glass bar blurs, marked with [Modifier.glassBackdropSource]. */
@Stable
class GlassBackdrop internal constructor(internal val haze: HazeState)

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val haze = rememberHazeState()
    return remember(haze) { GlassBackdrop(haze) }
}

/**
 * The backdrop glass bars over the current screen blur, or null where they stand solid — no
 * content runs beneath them, or the reader turned blur off in Appearance.
 */
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/**
 * How much of the bottom of the screen a bar floating over the content covers. Scrolling content
 * that runs on beneath the bar adds this to the end of its padding, so its last row can still
 * scroll clear of the bar.
 */
val LocalBottomBarClearance = compositionLocalOf { 0.dp }

/** Marks this node's content as what [backdrop]'s glass bars blur. Never applied to a bar itself. */
fun Modifier.glassBackdropSource(backdrop: GlassBackdrop): Modifier = hazeSource(backdrop.haze)

/** How heavily the glass blurs what's under it — enough to turn a poster into a wash of its colors. */
private val GlassBlurRadius = 24.dp

/** Share of the bar's own color laid over the blur, so labels keep their contrast over any poster. */
private const val GLASS_TINT_ALPHA = 0.72f

/**
 * Fills a bar with [backdrop] blurred under a [color] tint thin enough to hint at what passes
 * beneath. Without a backdrop it is left to the bar's own solid [color].
 */
fun Modifier.glassFill(
    backdrop: GlassBackdrop?,
    color: Color,
    background: Color,
    blurRadius: Dp = GlassBlurRadius,
): Modifier {
    if (backdrop == null) return this
    return hazeEffect(
        state = backdrop.haze,
        style = HazeStyle(
            backgroundColor = background,
            tint = HazeTint(color.copy(alpha = GLASS_TINT_ALPHA)),
            blurRadius = blurRadius,
            noiseFactor = 0f,
        ),
    )
}
