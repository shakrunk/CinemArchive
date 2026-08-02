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
import androidx.compose.foundation.layout.width
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

/** Geometry of the sliding pill indicator. Each item's icon box matches it exactly, so the
 *  glyph is centred in the pill rather than in the taller bar. */
private val INDICATOR_TOP_INSET = 10.dp
private val INDICATOR_HEIGHT = 34.dp

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
                    .padding(top = INDICATOR_TOP_INSET)
                    .offset(x = indicatorOffset + 6.dp)
                    .size(width = itemWidth - 12.dp, height = INDICATOR_HEIGHT)
                    .clip(RoundedCornerShape(INDICATOR_HEIGHT / 2))
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
                        // Lay the item out from the indicator's own top inset rather
                        // than centring icon+label in the full 80dp bar: centring
                        // pushed the icon's centre below the pill's, so the glyph
                        // sat low in its container.
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = LocalIndication.current,
                                ) { onSelect(destination.value) }
                                .padding(top = INDICATOR_TOP_INSET),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Top,
                        ) {
                            Box(
                                modifier = Modifier.height(INDICATOR_HEIGHT),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (isSelected) destination.selectedIcon else destination.icon,
                                    contentDescription = destination.label,
                                    tint = color,
                                    modifier = Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                                )
                            }
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

/** Fixed per-item height for [MorphingNavigationRail] — deliberately not "available height /
 *  item count" the way [MorphingBottomNav]'s `itemWidth` divides the bar's width, since a rail
 *  runs the full device height: dividing that by 4 would make each item enormous. The item
 *  stack is a fixed-height block instead, centered in whatever height the rail is given. */
private val RAIL_WIDTH = 88.dp
private val RAIL_ITEM_HEIGHT = 72.dp

/**
 * Leading-edge counterpart to [MorphingBottomNav] for wide/unfolded layouts — same sliding-pill
 * M3 Expressive language, transposed to a vertical, fixed-width rail instead of a bar stretched
 * across the full device width.
 */
@Composable
fun <T> MorphingNavigationRail(
    destinations: List<NavDestination<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxHeight(),
    ) {
        Box(
            modifier = Modifier
                .width(RAIL_WIDTH)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val stackHeight = RAIL_ITEM_HEIGHT * destinations.size
            Box(modifier = Modifier.width(RAIL_WIDTH).height(stackHeight)) {
                val selectedIndex = destinations.indexOfFirst { it.value == selected }.coerceAtLeast(0)
                val indicatorOffset by animateDpAsState(
                    targetValue = RAIL_ITEM_HEIGHT * selectedIndex,
                    animationSpec = expressiveSpring(),
                    label = "railIndicatorOffset",
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = indicatorOffset + INDICATOR_TOP_INSET)
                        .size(width = RAIL_WIDTH - 24.dp, height = INDICATOR_HEIGHT)
                        .clip(RoundedCornerShape(INDICATOR_HEIGHT / 2))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                )

                Column(modifier = Modifier.fillMaxWidth().fillMaxHeight().zIndex(1f)) {
                    destinations.forEach { destination ->
                        val isSelected = destination.value == selected
                        val color = if (isSelected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val interactionSource = remember { MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()
                        val iconScale by animateFloatAsState(
                            targetValue = if (isPressed) 0.8f else 1f,
                            animationSpec = if (isPressed) tween(durationMillis = 100) else expressiveSpring(),
                            label = "railIconScale",
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(RAIL_ITEM_HEIGHT)
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = LocalIndication.current,
                                ) { onSelect(destination.value) }
                                .padding(top = INDICATOR_TOP_INSET),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Top,
                        ) {
                            Box(
                                modifier = Modifier.height(INDICATOR_HEIGHT),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (isSelected) destination.selectedIcon else destination.icon,
                                    contentDescription = destination.label,
                                    tint = color,
                                    modifier = Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                                )
                            }
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
