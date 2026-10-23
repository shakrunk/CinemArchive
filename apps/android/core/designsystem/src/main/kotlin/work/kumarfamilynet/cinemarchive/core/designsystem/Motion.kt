package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntSize

/**
 * M3 Expressive-style spatial spring: fast settle with a visible, punchy bounce — for
 * indicators/shapes moving or resizing in response to a selection change, not for press
 * feedback (which should stay crisp and non-bouncy).
 */
fun <T> expressiveSpring(): SpringSpec<T> =
    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

/**
 * The app's motion vocabulary in one place, so a list, a disclosure and a FAB label all move on
 * the same springs and a retune lands everywhere at once.
 *
 * The spatial/effects springs are M3 Expressive's own token values (material3's internal
 * `ExpressiveMotionTokens`), named after `MotionScheme`'s slots. material3 1.4.0 keeps
 * `MotionScheme` and the `MaterialTheme(motionScheme = …)` overload internal, so these can't be
 * handed to the library's components yet; once a material3 bump makes them public, this object is
 * the scheme to pass.
 *
 * Spatial springs move things (position, size, shape) and may overshoot; effects springs change
 * color and opacity and never do, since an alpha past 1 or a color past its target reads as a glitch.
 */
object ArchiveMotion {
    fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)
    fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)
    fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 200f)
    fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
    fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)
    fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 800f)

    /** How long a press takes to sink. Short and linear-ish, so the control answers on contact. */
    private const val PRESS_DOWN_MILLIS = 100

    /** Press-down half of [Modifier.pressScale][pressScale]: a quick, crisp sink with no bounce. */
    fun <T> pressDown(): FiniteAnimationSpec<T> = tween(durationMillis = PRESS_DOWN_MILLIS)

    /** Release half of a press: springs back past rest before settling, which reads as "bolder". */
    fun <T> pressRelease(): FiniteAnimationSpec<T> = expressiveSpring()

    /**
     * Placement spring for rows of a lazy list that filters, sorts or drops an item. Damped below
     * 1 for a slight settle, but stiffer than [slowSpatialSpec] so a long list doesn't trail.
     */
    fun <T> listPlacement(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

    // Folds clip their content, so their size spring is critically damped: an overshoot would dip
    // below the content's height on the way back and cut its last line off for a frame.
    private val sizeSpec: FiniteAnimationSpec<IntSize> = spring(dampingRatio = 1f, stiffness = 380f)

    /** Content folding open under what stands above it — a filter panel, a disclosure body. */
    val foldIn: EnterTransition =
        fadeIn(defaultEffectsSpec()) + expandVertically(sizeSpec, expandFrom = Alignment.Top)

    /** Reverse of [foldIn]. */
    val foldOut: ExitTransition =
        fadeOut(fastEffectsSpec()) + shrinkVertically(sizeSpec, shrinkTowards = Alignment.Top)

    /** A label opening sideways next to an icon, as an extended FAB's does. */
    val revealHorizontally: EnterTransition = fadeIn(defaultEffectsSpec()) + expandHorizontally(sizeSpec)

    /** Reverse of [revealHorizontally]. */
    val concealHorizontally: ExitTransition = fadeOut(fastEffectsSpec()) + shrinkHorizontally(sizeSpec)
}

