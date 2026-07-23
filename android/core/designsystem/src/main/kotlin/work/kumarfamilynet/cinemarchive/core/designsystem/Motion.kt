package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * M3 Expressive-style spatial spring: fast settle with a visible, punchy bounce — for
 * indicators/shapes moving or resizing in response to a selection change, not for press
 * feedback (which should stay crisp and non-bouncy).
 */
fun <T> expressiveSpring(): SpringSpec<T> =
    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
