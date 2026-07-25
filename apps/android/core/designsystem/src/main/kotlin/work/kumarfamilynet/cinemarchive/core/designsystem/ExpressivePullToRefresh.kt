package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Pull-to-refresh carrying the M3 Expressive indicator shape rather than the plain circle
 * [PullToRefreshBox] defaults to.
 *
 * Built from [PullToRefreshDefaults.IndicatorBox] plus the expressive `indicatorShape` /
 * `indicatorContainerColor` / `loadingIndicatorElevation` tokens, because material3 1.4.0 —
 * the stable release that ships M3 Expressive — exposes those tokens but not yet the
 * `LoadingIndicator` composable itself (that is 1.5.0-alpha only, which this app does not
 * take a dependency on).
 *
 * Wrapped here because all three refreshable screens (Library, Discover, Up Next) need the
 * same indicator, and the indicator has to share a [rememberPullToRefreshState] with the box
 * so it can animate against the drag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressivePullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = modifier,
        indicator = {
            PullToRefreshDefaults.IndicatorBox(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                shape = PullToRefreshDefaults.indicatorShape,
                containerColor = PullToRefreshDefaults.indicatorContainerColor,
            ) {
                // Determinate against the drag before release, indeterminate once the
                // refresh is actually running — the same two phases the platform
                // indicator has, just inside the expressive container.
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(INDICATOR_GLYPH_SIZE),
                        color = PullToRefreshDefaults.indicatorColor,
                        strokeWidth = INDICATOR_STROKE,
                    )
                } else {
                    val progress by animateFloatAsState(
                        targetValue = state.distanceFraction.coerceIn(0f, 1f),
                        label = "pullProgress",
                    )
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(INDICATOR_GLYPH_SIZE),
                        color = PullToRefreshDefaults.indicatorColor,
                        strokeWidth = INDICATOR_STROKE,
                    )
                }
            }
        },
    ) {
        content()
    }
}

private val INDICATOR_GLYPH_SIZE = 24.dp
private val INDICATOR_STROKE = 3.dp
