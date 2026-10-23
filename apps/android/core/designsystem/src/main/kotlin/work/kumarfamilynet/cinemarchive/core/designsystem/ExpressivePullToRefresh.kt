package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Pull-to-refresh carrying M3 Expressive's `LoadingIndicator` rather than the plain circular arc
 * [PullToRefreshBox] defaults to.
 *
 * Mirrors [PullToRefreshDefaults.LoadingIndicator] — the same [PullToRefreshDefaults.IndicatorBox]
 * sizing, container and elevation, with a [ContainedLoadingIndicator] that morphs with the drag
 * and then loops while refreshing — but adds a brief checkmark hold on completion, which gives
 * success feedback the stock indicator never shows.
 *
 * Wrapped here because all three refreshable screens (Library, Discover, Up Next) need the
 * same indicator, and the indicator has to share a [rememberPullToRefreshState] with the box
 * so it can animate against the drag.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressivePullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberPullToRefreshState()

    // Held true for a beat after `isRefreshing` drops so the checkmark below has time to
    // show — without this the box collapses the instant the caller's data finishes loading
    // and nothing ever communicates success.
    var showSuccess by remember { mutableStateOf(false) }
    var wasRefreshing by remember { mutableStateOf(isRefreshing) }
    LaunchedEffect(isRefreshing) {
        if (wasRefreshing && !isRefreshing) {
            showSuccess = true
            delay(SUCCESS_HOLD_DURATION_MS)
            showSuccess = false
        }
        wasRefreshing = isRefreshing
    }
    val indicatorVisible = isRefreshing || showSuccess

    PullToRefreshBox(
        isRefreshing = indicatorVisible,
        onRefresh = onRefresh,
        state = state,
        modifier = modifier,
        indicator = {
            val containerColor = PullToRefreshDefaults.loadingIndicatorContainerColor
            val indicatorColor = PullToRefreshDefaults.loadingIndicatorColor
            val indicatorModifier = Modifier.requiredSize(
                width = LoadingIndicatorDefaults.ContainerWidth,
                height = LoadingIndicatorDefaults.ContainerHeight,
            )
            PullToRefreshDefaults.IndicatorBox(
                state = state,
                isRefreshing = indicatorVisible,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .size(
                        width = LoadingIndicatorDefaults.ContainerWidth,
                        height = LoadingIndicatorDefaults.ContainerHeight,
                    ),
                containerColor = containerColor,
                elevation = PullToRefreshDefaults.LoadingIndicatorElevation,
            ) {
                val phase = when {
                    showSuccess -> GlyphPhase.Success
                    isRefreshing -> GlyphPhase.Refreshing
                    else -> GlyphPhase.Pulling
                }
                Crossfade(
                    targetState = phase,
                    animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                    label = "pullToRefreshGlyph",
                ) { current ->
                    when (current) {
                        GlyphPhase.Success -> Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = indicatorColor,
                            modifier = Modifier.size(SUCCESS_GLYPH_SIZE),
                        )

                        GlyphPhase.Refreshing -> ContainedLoadingIndicator(
                            modifier = indicatorModifier,
                            containerColor = containerColor,
                            indicatorColor = indicatorColor,
                        )

                        GlyphPhase.Pulling -> ContainedLoadingIndicator(
                            progress = { state.distanceFraction },
                            modifier = indicatorModifier,
                            containerColor = containerColor,
                            indicatorColor = indicatorColor,
                        )
                    }
                }
            }
        },
    ) {
        content()
    }
}

private enum class GlyphPhase { Pulling, Refreshing, Success }

private val SUCCESS_GLYPH_SIZE = 24.dp

private const val SUCCESS_HOLD_DURATION_MS = 550L
