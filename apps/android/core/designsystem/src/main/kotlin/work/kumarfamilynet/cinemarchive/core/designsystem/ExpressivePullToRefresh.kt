package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import kotlinx.coroutines.delay

/**
 * Pull-to-refresh carrying the M3 Expressive indicator shape rather than the plain circle
 * [PullToRefreshBox] defaults to.
 *
 * Built from [PullToRefreshDefaults.IndicatorBox] plus the expressive `indicatorShape` /
 * `indicatorContainerColor` / `loadingIndicatorElevation` tokens, because material3 1.4.0 —
 * the stable release that ships M3 Expressive — exposes those tokens but not yet the
 * `LoadingIndicator` composable itself (that is 1.5.0-alpha only, which this app does not
 * take a dependency on). The indeterminate glyph is instead hand-rolled from the stable
 * `androidx.graphics:graphics-shapes` library — the same [RoundedPolygon]/[Morph] primitives
 * `LoadingIndicator` itself is built on — so refreshing genuinely morphs between expressive
 * shapes rather than spinning a plain circular arc. A brief checkmark hold on completion
 * gives success feedback the plain arc never showed.
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
            PullToRefreshDefaults.IndicatorBox(
                state = state,
                isRefreshing = indicatorVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                shape = PullToRefreshDefaults.indicatorShape,
                containerColor = PullToRefreshDefaults.indicatorContainerColor,
            ) {
                val phase = when {
                    showSuccess -> GlyphPhase.Success
                    isRefreshing -> GlyphPhase.Refreshing
                    else -> GlyphPhase.Pulling
                }
                Crossfade(targetState = phase, label = "pullToRefreshGlyph") { current ->
                    when (current) {
                        GlyphPhase.Success -> Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = PullToRefreshDefaults.indicatorColor,
                            modifier = Modifier.size(INDICATOR_GLYPH_SIZE),
                        )

                        GlyphPhase.Refreshing -> ExpressiveMorphingGlyph(
                            color = PullToRefreshDefaults.indicatorColor,
                        )

                        GlyphPhase.Pulling -> {
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
                }
            }
        },
    ) {
        content()
    }
}

private enum class GlyphPhase { Pulling, Refreshing, Success }

/**
 * Indeterminate glyph that continuously morphs between a circle and two M3 Expressive
 * "cookie"/"burst" polygons while slowly rotating — the same shape-morph language
 * `LoadingIndicator` uses, composed directly from [RoundedPolygon]/[Morph] since that
 * composable itself isn't available until material3 1.5.0.
 */
@Composable
private fun ExpressiveMorphingGlyph(color: Color, modifier: Modifier = Modifier) {
    val radiusPx = with(LocalDensity.current) { (INDICATOR_GLYPH_SIZE / 2).toPx() }

    val morphs = remember(radiusPx) {
        val circle = RoundedPolygon.circle(
            numVertices = CIRCLE_VERTICES,
            radius = radiusPx,
            centerX = radiusPx,
            centerY = radiusPx,
        )
        val cookie = RoundedPolygon.star(
            numVerticesPerRadius = COOKIE_VERTICES,
            radius = radiusPx,
            innerRadius = radiusPx * COOKIE_INNER_RADIUS_RATIO,
            rounding = CornerRounding(radiusPx * COOKIE_ROUNDING_RATIO),
            centerX = radiusPx,
            centerY = radiusPx,
        )
        val burst = RoundedPolygon.star(
            numVerticesPerRadius = BURST_VERTICES,
            radius = radiusPx,
            innerRadius = radiusPx * BURST_INNER_RADIUS_RATIO,
            rounding = CornerRounding(radiusPx * BURST_ROUNDING_RATIO),
            centerX = radiusPx,
            centerY = radiusPx,
        )
        listOf(Morph(circle, cookie), Morph(cookie, burst), Morph(burst, circle))
    }

    val loopDurationMs = morphs.size * MORPH_SEGMENT_DURATION_MS
    val infiniteTransition = rememberInfiniteTransition(label = "expressiveLoadingMorph")
    val cycle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = morphs.size.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = loopDurationMs, easing = LinearEasing),
        ),
        label = "morphCycle",
    )
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = loopDurationMs, easing = LinearEasing),
        ),
        label = "morphRotation",
    )

    val segment = cycle.toInt().coerceIn(0, morphs.size - 1)
    val segmentProgress = (cycle - segment).coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .size(INDICATOR_GLYPH_SIZE)
            .graphicsLayer { rotationZ = rotation },
    ) {
        val path = morphs[segment].toPath(progress = segmentProgress).asComposePath()
        drawPath(path, color = color)
    }
}

private val INDICATOR_GLYPH_SIZE = 24.dp
private val INDICATOR_STROKE = 3.dp

private const val SUCCESS_HOLD_DURATION_MS = 550L
private const val MORPH_SEGMENT_DURATION_MS = 650

private const val CIRCLE_VERTICES = 16
private const val COOKIE_VERTICES = 8
private const val COOKIE_INNER_RADIUS_RATIO = 0.85f
private const val COOKIE_ROUNDING_RATIO = 0.4f
private const val BURST_VERTICES = 4
private const val BURST_INNER_RADIUS_RATIO = 0.55f
private const val BURST_ROUNDING_RATIO = 0.25f
