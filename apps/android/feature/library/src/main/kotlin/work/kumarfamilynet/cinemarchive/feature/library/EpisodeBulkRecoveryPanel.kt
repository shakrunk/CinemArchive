package work.kumarfamilynet.cinemarchive.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.data.EpisodeBulkPending
import work.kumarfamilynet.cinemarchive.data.EpisodeBulkRepository

/** Remains reachable after a local title deletion, when its detail screen no longer exists. */
@Composable
fun EpisodeBulkRecoveryPanel(source: EpisodeBulkRepository) {
    key(source) {
        val pending by source.savedRequests.collectAsStateWithLifecycle(initialValue = emptyList())
        val retained by source.retainedCount.collectAsStateWithLifecycle(initialValue = 0)
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        var message by remember { mutableStateOf<String?>(null) }
        val document = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null && source.isActive()) scope.launch {
                try {
                    val original = source.exportOriginals()
                    withContext(Dispatchers.IO) {
                        check(source.isActive()) { "This sign-in has ended." }
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(original) }
                            ?: error("Could not open the selected file.")
                    }
                    if (source.isActive()) message = "Original requests exported."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { if (source.isActive()) message = failure.message ?: "Could not export originals." }
            }
        }
        if (source.isActive()) {
            EpisodeBulkRecoveryPanel(pending, source::retry, source::discard, retained) {
                document.launch("cinemarchive-pre-platform-originals.json")
            }
            message?.let { Text(it) }
        }
    }
}

@Composable
fun EpisodeBulkRecoveryPanel(
    pending: List<EpisodeBulkPending>,
    retry: suspend () -> Unit,
    discard: suspend (String) -> Unit,
    retainedCount: Int = 0,
    exportOriginals: () -> Unit = {},
) {
    val coroutine = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        coroutine.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not confirm this request. Retry." }
            finally { busy = false }
        }
    }
    if (pending.isNotEmpty() || retainedCount > 0) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Saved pre-platform watches", style = MaterialTheme.typography.titleMedium)
        pending.forEach { change ->
            Text("${change.titleName}: ${change.count} undated watches")
            Text(change.error ?: if (change.needsReview) "Needs review before sync." else "Waiting for sync.")
            if (change.needsReview) {
                Text("Compare these watches in the series detail, or discard this unaccepted request. Removed series can still be discarded here.")
                TextButton(enabled = !busy, onClick = { selected = change.id }) { Text("Discard saved request") }
            } else TextButton(enabled = !busy, onClick = { run { retry() } }) { Text("Retry sync") }
        }
        if (retainedCount > 0) {
            Text("$retainedCount original requests retained, including discarded and synced requests.")
            TextButton(enabled = !busy, onClick = exportOriginals) { Text("Export original requests") }
            if (pending.isEmpty()) TextButton(enabled = !busy, onClick = { run { retry() } }) { Text("Retry sync") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    selected?.let { id -> AlertDialog(
        onDismissRequest = { if (!busy) selected = null },
        title = { Text("Discard saved pre-platform watches?") },
        text = { Column {
            Text("Only this rejected request and its pending undated watches are removed. Existing history and later edits remain. The original request stays available to export.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = { run { discard(id); selected = null } }) { Text("Discard") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { selected = null }) { Text("Cancel") } },
    ) }
}
