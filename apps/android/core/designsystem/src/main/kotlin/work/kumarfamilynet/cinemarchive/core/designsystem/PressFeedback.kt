package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/* ── Shared press feedback ────────────────────────────────────────────────────
   One press language for every tappable control: a quick, crisp sink on
   press-down that springs back past rest on release, plus a haptic tick on the
   click itself. Kept here so the segmented groups, the nav, the FAB and any
   one-off button all answer a touch the same way. */

/** How far a control sinks while held. */
const val PressedScale = 0.92f

/**
 * The scale a control holds as [interactionSource] reports presses: [pressedScale] while held,
 * 1 at rest, on [ArchiveMotion.pressDown] going down and [ArchiveMotion.pressRelease] coming back.
 * Hand the result to [Modifier.pressScale].
 */
@Composable
fun rememberPressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = PressedScale,
    enabled: Boolean = true,
): State<Float> {
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressed = enabled && isPressed
    return animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (pressed) ArchiveMotion.pressDown() else ArchiveMotion.pressRelease(),
        label = "pressScale",
    )
}

/**
 * Scales the node by [scale], from [rememberPressScale]. The value is read inside the layer, so a
 * held press only redraws the control rather than recomposing it on every frame.
 */
fun Modifier.pressScale(scale: State<Float>): Modifier = graphicsLayer {
    scaleX = scale.value
    scaleY = scale.value
}

/**
 * [onClick] with a haptic tick of [type] fired first. Remembered against [onClick], so it hands
 * back the same lambda across recompositions. The system's touch-feedback setting still decides
 * whether the tick is felt.
 */
@Composable
fun rememberHapticClick(
    type: HapticFeedbackType = HapticFeedbackType.VirtualKey,
    onClick: () -> Unit,
): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics, type, onClick) {
        {
            haptics.performHapticFeedback(type)
            onClick()
        }
    }
}
