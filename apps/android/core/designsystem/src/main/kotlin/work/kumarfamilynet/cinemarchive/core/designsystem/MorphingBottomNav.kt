package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * [icon] is the resting/unselected glyph (typically an outlined variant); [selectedIcon]
 * (defaults to [icon] when not given) is swapped in for the active tab — mirrors the M3
 * Expressive convention of a bolder filled icon marking the current destination.
 */
data class NavDestination<T>(
    val value: T,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
)

/**
 * M3 Expressive bottom nav: a pill indicator slides between destinations instead of each
 * item getting its own static highlight — mirrors the design handoff's `navIndicatorLeft`
 * sliding-pill nav bar.
 */
@Composable
fun <T> MorphingBottomNav(
    destinations: List<NavDestination<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(80.dp)
                .padding(horizontal = 8.dp),
        ) {
            val itemWidth = maxWidth / destinations.size
            val selectedIndex = destinations.indexOfFirst { it.value == selected }.coerceAtLeast(0)
            val indicatorOffset by animateDpAsState(
                targetValue = itemWidth * selectedIndex,
                animationSpec = expressiveSpring(),
                label = "navIndicatorOffset",
            )

            Box(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .offset(x = indicatorOffset + 6.dp)
                    .size(width = itemWidth - 12.dp, height = 34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            )

            Box(modifier = Modifier.fillMaxWidth().fillMaxHeight().zIndex(1f)) {
                androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                    destinations.forEach { destination ->
                        val isSelected = destination.value == selected
                        val color = if (isSelected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val interactionSource = remember { MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()
                        // Press-down is a quick, crisp shrink; release springs back past 1x
                        // before settling — the bounce itself reads as "bolder" on landing.
                        val iconScale by animateFloatAsState(
                            targetValue = if (isPressed) 0.8f else 1f,
                            animationSpec = if (isPressed) tween(durationMillis = 100) else expressiveSpring(),
                            label = "navIconScale",
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = LocalIndication.current,
                                ) { onSelect(destination.value) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                if (isSelected) destination.selectedIcon else destination.icon,
                                contentDescription = destination.label,
                                tint = color,
                                modifier = Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                            )
                            Text(
                                destination.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = color,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
