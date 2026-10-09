package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.data.TitlePinsSource

@Composable
fun NoirDetailControls(detail: TitleDetail, source: TitlePinsSource, loggedMode: String?, onMode: (String?) -> Unit) {
    if (detail.tmdbId != SPIDER_NOIR_TMDB_ID) return
    key(source, detail.id) {
        val state by source.state.collectAsStateWithLifecycle(initialValue = work.kumarfamilynet.cinemarchive.data.TitlePinsState())
        val progress = noirProgress(detail)
        var seeded by rememberSaveable { mutableStateOf(false) }
        var selected by rememberSaveable { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val currentMode by rememberUpdatedState(onMode)
        LaunchedEffect(state.loaded) {
            if (state.loaded && !seeded) { selected = state.pins[detail.id] ?: progress.lastMode; seeded = true }
            if (state.loaded) currentMode(selected)
        }
        LaunchedEffect(loggedMode) {
            loggedMode?.substringAfter('|')?.takeIf(NOIR_MODES::contains)?.let { selected = it; seeded = true; currentMode(it) }
        }
        NoirModeSelector(progress, selected, state.pins[detail.id], busy || !state.loaded,
            detail.id in state.pending, error ?: state.errors[detail.id],
            onSelect = { selected = it; currentMode(it) },
            onPin = { variant ->
                if (!busy) {
                    busy = true; error = null
                    scope.launch {
                        try { source.set(detail.id, variant) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "Could not save the filter. Retry." }
                        finally { busy = false }
                    }
                }
            }, onRetry = source::retry)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoirModeSelector(
    progress: NoirProgress,
    selected: String?,
    pinned: String?,
    busy: Boolean = false,
    pending: Boolean = false,
    error: String? = null,
    onSelect: (String?) -> Unit,
    onPin: (String?) -> Unit,
    onRetry: () -> Unit = {},
) {
    if (progress.unlocked.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected == null, onClick = { onSelect(null) }, enabled = !busy, label = { Text("Normal") })
            for (mode in listOf("bw", "color").filter(progress.unlocked::contains)) {
                val label = if (mode == "bw") "B&W" else "Color"
                FilterChip(selected == mode, onClick = { onSelect(mode) }, enabled = !busy, label = { Text(label) })
                if (selected == mode && mode in progress.earned) TextButton(enabled = !busy,
                    onClick = { onPin(if (pinned == mode) null else mode) }) { Text(if (pinned == mode) "Unpin $label mode" else "Pin $label mode") }
            }
        }
        if (pinned != null) Text("Filter stays on when you leave", style = MaterialTheme.typography.labelSmall)
        if (pending) Text("Filter saved on this device. Waiting for sync.", style = MaterialTheme.typography.labelSmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(enabled = !busy, onClick = onRetry) { Text("Retry filter sync") } }
    }
}
