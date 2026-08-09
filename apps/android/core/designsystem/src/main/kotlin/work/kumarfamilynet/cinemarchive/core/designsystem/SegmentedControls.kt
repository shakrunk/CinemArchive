package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A labeled option in a [SegmentedGroup] / [ConnectedToggleGroup].
 *
 * [icon] and [labelFontFamily] are both optional and both only honored by [SegmentedGroup]:
 * they exist for option sets where the label alone under-describes the choice. An [icon] gives
 * a set like System/Light/Dark a glyph to recognise before the word is read, and
 * [labelFontFamily] lets a font picker set each option's own label in the typeface that option
 * would apply — showing the thing rather than naming it.
 */
data class ChoiceOption<T>(
    val value: T,
    val label: String,
    val icon: ImageVector? = null,
    val labelFontFamily: FontFamily? = null,
)

/**
 * M3 Expressive "standard" button group: single-select, mutually exclusive (e.g. media-type
 * or theme-mode radios). The selected option grows and morphs from a squarish rect into a
 * full stadium pill with a leading checkmark, visibly shrinking its neighbors via shared
 * flex-grow — mirrors `segStyle()` in the design handoff (CinemArchive Android.dc.html).
 */
@Composable
fun <T> SegmentedGroup(
    options: List<ChoiceOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { index, option ->
            SegmentedGroupItem(
                label = option.label,
                icon = option.icon,
                labelFontFamily = option.labelFontFamily,
                isSelected = option.value == selected,
                isFirst = index == 0,
                isLast = index == options.lastIndex,
                onClick = { onSelect(option.value) },
            )
        }
    }
}

private val SegmentBigCorner = 23.dp
private val SegmentSmallCorner = 12.dp

@Composable
private fun RowScope.SegmentedGroupItem(
    label: String,
    icon: ImageVector?,
    labelFontFamily: FontFamily?,
    isSelected: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    val weight by animateFloatAsState(
        targetValue = if (isSelected) 1.5f else 1f,
        animationSpec = expressiveSpring(),
        label = "segWeight",
    )
    // Outer corners of the first/last item stay large even unselected, same cohesive-group
    // trick as ConnectedToggleItem — the selected item still pops into a full stadium on all
    // four corners regardless of position, unselected neighbors just shrink toward each other.
    val leading by animateDpAsState(if (isSelected || isFirst) SegmentBigCorner else SegmentSmallCorner, label = "segLeading")
    val trailing by animateDpAsState(if (isSelected || isLast) SegmentBigCorner else SegmentSmallCorner, label = "segTrailing")
    val bg = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // Same press language as MorphingBottomNav's icon: a quick crisp shrink on press-down,
    // springing back past 1x on release — the ripple alone read as flat for a toggle this size.
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = if (isPressed) tween(durationMillis = 100) else expressiveSpring(),
        label = "segPressScale",
    )

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .weight(weight.coerceAtLeast(0.01f))
            .height(46.dp)
            .scale(pressScale),
        shape = RoundedCornerShape(topStart = leading, bottomStart = leading, topEnd = trailing, bottomEnd = trailing),
        color = bg,
        contentColor = fg,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // An option carrying its own glyph keeps it in both states rather than swapping to
            // the checkmark on selection: the glyph is what identifies the option, and trading
            // it away exactly when the option is active loses that at the worst moment. The
            // container tint, bold weight, and the width/corner morph already carry selection.
            val leadingIcon = icon ?: Icons.Filled.Check.takeIf { isSelected }
            if (leadingIcon != null) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp).size(if (icon != null) 16.dp else 14.dp),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.let {
                    if (labelFontFamily != null) it.copy(fontFamily = labelFontFamily) else it
                },
                // Weight, not just colour: the selected option must stay readable as
                // "selected" without relying on the container tint alone.
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * M3 Expressive "connected" button group: independent multi-select toggles (e.g. library
 * status filters). No width interaction — selection only changes color and corner radius.
 * Outer corners of the first/last item stay large even when unselected so the row reads as
 * one cohesive shape; any activated item pops into a full pill on all four corners
 * regardless of position — mirrors `connectedGroupStyle()` in the design handoff.
 */
@Composable
fun <T> ConnectedToggleGroup(
    options: List<ChoiceOption<T>>,
    selected: Set<T>,
    onToggle: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { index, option ->
            ConnectedToggleItem(
                label = option.label,
                isSelected = option.value in selected,
                isFirst = index == 0,
                isLast = index == options.lastIndex,
                onClick = { onToggle(option.value) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private val ConnectedBigCorner = 22.dp
private val ConnectedSmallCorner = 8.dp

@Composable
private fun ConnectedToggleItem(
    label: String,
    isSelected: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val leading by animateDpAsState(
        if (isSelected || isFirst) ConnectedBigCorner else ConnectedSmallCorner,
        label = "connectedLeading",
    )
    val trailing by animateDpAsState(
        if (isSelected || isLast) ConnectedBigCorner else ConnectedSmallCorner,
        label = "connectedTrailing",
    )
    val bg = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = if (isPressed) tween(durationMillis = 100) else expressiveSpring(),
        label = "connectedPressScale",
    )

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier.height(46.dp).scale(pressScale),
        shape = RoundedCornerShape(
            topStart = leading,
            topEnd = trailing,
            bottomEnd = trailing,
            bottomStart = leading,
        ),
        color = bg,
        contentColor = fg,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
