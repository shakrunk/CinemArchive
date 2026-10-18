package work.kumarfamilynet.cinemarchive.feature.library

import work.kumarfamilynet.cinemarchive.data.rethrowIfCancelled

import work.kumarfamilynet.cinemarchive.data.userMessage

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
                        catch (failure: Exception) { failure.rethrowIfCancelled(); error = failure.userMessage("Could not save the filter. Retry.") }
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
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        Text("Viewing mode", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            FilterChip(selected == null, onClick = { onSelect(null) }, enabled = !busy, label = { Text("Normal") })
            for (mode in listOf("bw", "color").filter(progress.unlocked::contains)) {
                val label = if (mode == "bw") "B&W" else "Color"
                FilterChip(selected == mode, onClick = { onSelect(mode) }, enabled = !busy, label = { Text(label) })
            }
            // Pinning keeps the selected earned mode on after leaving the title.
            val pinnable = selected?.takeIf { it in progress.earned }
            if (pinnable != null) {
                val label = if (pinnable == "bw") "B&W" else "Color"
                IconToggleButton(checked = pinned == pinnable, enabled = !busy,
                    onCheckedChange = { onPin(if (pinned == pinnable) null else pinnable) },
                    modifier = Modifier.semantics { contentDescription = if (pinned == pinnable) "Unpin $label mode" else "Pin $label mode" }) {
                    Icon(if (pinned == pinnable) Icons.Filled.PushPin else Icons.Outlined.PushPin, contentDescription = null)
                }
            }
        }
        val note = when {
            pending -> "Saved on this device; syncing soon."
            pinned != null -> "Stays on when you leave this title."
            else -> null
        }
        note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        error?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = onRetry) { Text("Retry") }
            }
        }
    }
}
