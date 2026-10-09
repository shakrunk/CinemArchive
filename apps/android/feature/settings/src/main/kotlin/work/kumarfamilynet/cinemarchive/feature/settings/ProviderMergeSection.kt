package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.data.*

@Composable
fun ProviderMergeSection(source: ProviderMergeSource): Unit = key(source) {
    val rows by source.changes.collectAsState(emptyList())
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var comparison by remember { mutableStateOf<ProviderMergeComparison?>(null) }
    var exporting by remember { mutableStateOf<String?>(null) }
    fun run(block: suspend () -> Unit) {
        if (busy || !source.isCurrent()) return
        busy = true; error = null
        scope.launch {
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (source.isCurrent()) error = failure.message ?: "Could not finish. The saved request is retained." }
            finally { if (source.isCurrent()) busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = exporting; exporting = null
        if (uri != null && id != null) run { source.export(id) {
            context.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("Could not open the destination.")
        } }
    }
    if (source.isCurrent() && rows.isNotEmpty()) Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Saved provider changes", style = MaterialTheme.typography.titleMedium)
            Text("Changes to existing titles remain visible offline until confirmed. Retrying keeps the same request.")
            rows.forEach { row ->
                Text(row.title, style = MaterialTheme.typography.titleSmall)
                Text(row.message ?: "Waiting for confirmation.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !busy, onClick = { run { source.retry(row.id) } }) { Text("Retry ${row.title}") }
                OutlinedButton(enabled = !busy, onClick = { exporting = row.id; picker.launch("cinemarchive-provider-change.json") }) { Text("Export saved request for ${row.title}") }
                if (row.review) OutlinedButton(enabled = !busy, onClick = { run {
                    val result = source.compare(row.id); if (source.isCurrent()) comparison = result
                } }) { Text("Compare ${row.title}") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    comparison?.takeIf { source.isCurrent() }?.let { value -> AlertDialog(
        onDismissRequest = { if (!busy) comparison = null }, title = { Text("Review ${value.title}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Saved import: ${value.saved}"); Text("Latest server: ${value.current}")
            Text("Remove only the rejected provider change. ${value.dependentChanges} later dependent edits will remain as drafts requiring review. Export your library first to retain all visible changes. You can import again after reviewing the latest state.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = { run {
            source.discard(value); if (source.isCurrent()) comparison = null
        } }) { Text("Remove rejected change") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { comparison = null }) { Text("Keep saved change") } }) }
}
