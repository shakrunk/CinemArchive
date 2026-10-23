package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Taps on the navigation item of the tab already on screen. The standard Android answer to "take
 * me back to the top of this long list" — no extra button over the content, and the nav item is
 * always in reach. Only the tab on screen is composed, so only its list hears the tap.
 */
@Stable
class TabReselect {
    private val taps = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val events: SharedFlow<Unit> get() = taps

    fun reselect() {
        taps.tryEmit(Unit)
    }
}

/** The [TabReselect] of the navigation the current tab sits under, null outside one. */
val LocalTabReselect = staticCompositionLocalOf<TabReselect?> { null }

/**
 * Past this many rows, a scroll to the top first jumps to this row and animates only the rest.
 * Animating the whole distance from deep in a long list travels on estimated row sizes and takes
 * long enough to read as lag.
 */
private const val SCROLL_TO_TOP_JUMP_INDEX = 12

/** Scrolls [state] back to its first row whenever the reader re-taps this tab's nav item. */
@Composable
fun ScrollToTopOnReselect(state: LazyListState) {
    val reselect = LocalTabReselect.current ?: return
    LaunchedEffect(reselect, state) {
        reselect.events.collect {
            if (state.firstVisibleItemIndex > SCROLL_TO_TOP_JUMP_INDEX) state.scrollToItem(SCROLL_TO_TOP_JUMP_INDEX)
            state.animateScrollToItem(0)
        }
    }
}

/** [ScrollToTopOnReselect] for a lazy grid. */
@Composable
fun ScrollToTopOnReselect(state: LazyGridState) {
    val reselect = LocalTabReselect.current ?: return
    LaunchedEffect(reselect, state) {
        reselect.events.collect {
            if (state.firstVisibleItemIndex > SCROLL_TO_TOP_JUMP_INDEX) state.scrollToItem(SCROLL_TO_TOP_JUMP_INDEX)
            state.animateScrollToItem(0)
        }
    }
}
