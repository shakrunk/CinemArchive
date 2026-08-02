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
 *
 * [contentKey] identifies the shape of the list's content (e.g. which header rows are
 * present). When it changes, the lazy list re-anchors by key and firstVisibleItemIndex can
 * shift even though the user didn't scroll — without this, that shift reads as a downward
 * scroll, collapsing the header, which resizes the viewport, which shifts the index again,
 * feeding back into an oscillation that never settles (#187). A content-key change just
 * rebases the tracked index/offset instead of updating [collapsed].
 */
@Composable
fun rememberCollapseOnScroll(index: Int, offset: Int, contentKey: Any? = Unit): Boolean {
    var collapsed by remember { mutableStateOf(false) }
    var previousIndex by remember { mutableStateOf(index) }
    var previousOffset by remember { mutableStateOf(offset) }
    var previousContentKey by remember { mutableStateOf(contentKey) }

    LaunchedEffect(index, offset, contentKey) {
        if (contentKey != previousContentKey) {
            previousContentKey = contentKey
            previousIndex = index
            previousOffset = offset
            return@LaunchedEffect
        }
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
