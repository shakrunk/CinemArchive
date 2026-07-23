package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A labeled option in a [SegmentedGroup] / [ConnectedToggleGroup]. */
data class ChoiceOption<T>(val value: T, val label: String)

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
        options.forEach { option ->
            SegmentedGroupItem(
                label = option.label,
                isSelected = option.value == selected,
                onClick = { onSelect(option.value) },
            )
        }
    }
}

@Composable
private fun RowScope.SegmentedGroupItem(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val weight by animateFloatAsState(
        targetValue = if (isSelected) 1.5f else 1f,
        animationSpec = expressiveSpring(),
        label = "segWeight",
    )
    val radius by animateDpAsState(if (isSelected) 23.dp else 12.dp, label = "segRadius")
    val bg = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer

    Surface(
        onClick = onClick,
        modifier = Modifier
            .weight(weight.coerceAtLeast(0.01f))
            .height(46.dp),
        shape = RoundedCornerShape(radius),
        color = bg,
        contentColor = fg,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSelected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp).size(14.dp),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
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

    Surface(
        onClick = onClick,
        modifier = modifier.height(46.dp),
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
