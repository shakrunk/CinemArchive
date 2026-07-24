package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Derives a collapse signal from a scrollable list's (firstVisibleItemIndex,
 * firstVisibleItemScrollOffset) pair: true once the user scrolls down past the top item,
 * false again as soon as they scroll back up — and always false while pinned to the very
 * top, so a tiny overscroll wobble there can't falsely trigger a collapse. Shared by a
 * screen's collapsing search/filter header and the "New Title" FAB's expanded state.
 */
@Composable
fun rememberCollapseOnScroll(index: Int, offset: Int): Boolean {
    var collapsed by remember { mutableStateOf(false) }
    var previousIndex by remember { mutableStateOf(index) }
    var previousOffset by remember { mutableStateOf(offset) }

    LaunchedEffect(index, offset) {
        collapsed = when {
            index == 0 && offset == 0 -> false
            index != previousIndex -> index > previousIndex
            else -> offset > previousOffset
        }
        previousIndex = index
        previousOffset = offset
    }

    return collapsed
}
