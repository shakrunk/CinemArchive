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
fun LibraryRestoreSection(source: LibraryRestoreSource): Unit = key(source) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by remember(source) { source.savedImports() }.collectAsState(emptyList())
    var preview by remember { mutableStateOf<PreparedLibraryImport?>(null) }
    var removing by remember { mutableStateOf<SavedLibraryImport?>(null) }
    var exportId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun run(action: suspend () -> Unit) {
        if (busy || !source.isCurrent()) return
        busy = true
        scope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (source.isCurrent()) message = error.message ?: "The saved import is still available. Try again." }
            finally { busy = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = exportId; exportId = null
        if (uri != null && id != null) run {
            source.exportSaved(id) { context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the destination.") }
            if (source.isCurrent()) message = "Saved the original imported graph. Later edits can be exported with Export JSON above."
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run {
            val prepared = source.prepareImport { context.contentResolver.openInputStream(uri) ?: error("Could not open this file.") }
            if (source.isCurrent()) { preview = prepared; message = null }
        }
    }
    if (source.isCurrent()) Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Restore JSON", style = MaterialTheme.typography.titleMedium)
            Text("Preview a CinemArchive backup, then save new titles with their complete episode history, viewings and outings. Titles already in this account and all their imported outings are skipped. Ticket photos, lists and settings are not restored.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy) {
                Text(if (busy) "Working…" else "Choose JSON file")
            }
            preview?.let { prepared ->
                Text("${prepared.titleCount} new titles and ${prepared.outingCount} outings ready. ${prepared.skippedTitles} existing or duplicate titles and ${prepared.skippedOutings} associated outings skipped.")
                prepared.issues.take(50).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (prepared.issues.size > 50) Text("${prepared.issues.size - 50} more notices. Only the validated titles above will be saved; the original file is unchanged.")
                Text("Each title and its history are saved together. If storage fills, earlier titles remain saved and the report identifies how many are left.")
                Button(enabled = !busy && prepared.titleCount > 0, onClick = { run {
                    val result = source.admit(prepared)
                    if (source.isCurrent()) {
                        message = "Saved ${result.admitted} titles on this device; ${result.skipped} skipped. " +
                            if (result.error == null) "They will sync when connected." else "${result.remaining} not saved: ${result.error}. Choose the file again to retry; saved titles will be skipped."
                        preview = null
                        scope.launch { runCatching { source.synchronize() } }
                    }
                } }) { Text("Save new titles") }
                OutlinedButton(enabled = !busy, onClick = { preview = null }) { Text("Cancel preview") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (saved.isNotEmpty()) {
                Text("Saved imports", style = MaterialTheme.typography.titleSmall)
                Text("New titles from JSON and connected providers stay here until their complete import is confirmed.", style = MaterialTheme.typography.bodySmall)
            }
            saved.forEach { item ->
                Text(item.title)
                Text(item.message ?: "Saved on this device; waiting for confirmation.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !busy, onClick = { run { source.retry(item.id) } }) { Text("Retry ${item.title}") }
                OutlinedButton(enabled = !busy, onClick = { exportId = item.id; export.launch("cinemarchive-saved-import.json") }) { Text("Export saved ${item.title}") }
                if (item.review) OutlinedButton(enabled = !busy, onClick = { removing = item }) { Text("Review removal of ${item.title}") }
            }
        }
    }
    removing?.takeIf { source.isCurrent() }?.let { item ->
        AlertDialog(onDismissRequest = { if (!busy) removing = null },
            title = { Text("Remove rejected import?") },
            text = { Text("Remove ${item.title} from this device and ${item.dependentChanges} later dependent changes that have never been sent. Its server transaction was rejected. Export the current library first to retain later edits; original provider data and any archive files are unchanged. Unconfirmed imports cannot be removed here.") },
            confirmButton = { TextButton(enabled = !busy, onClick = { run {
                source.discardRejected(item)
                if (source.isCurrent()) { removing = null; message = "Removed the rejected import and its reviewed dependent changes."; scope.launch { runCatching { source.synchronize() } } }
            } }) { Text("Remove reviewed changes") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { removing = null }) { Text("Keep saved changes") } })
    }
}
