package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.data.CompanionRecoveryRequired
import work.kumarfamilynet.cinemarchive.data.LibraryBackupSource
import work.kumarfamilynet.cinemarchive.data.PreparedLibraryExport

/** The document picker grants only the destination selected by this owner. No storage permission. */
@Composable
fun LibraryBackupSection(source: LibraryBackupSource): Unit = key(source) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prepared by remember { mutableStateOf<PreparedLibraryExport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var recovery by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = prepared
        prepared = null
        if (snapshot != null && source.isCurrent()) {
            if (uri == null) {
                busy = false; message = "Export cancelled. Your library has not changed."
            } else scope.launch {
                try {
                    source.writeExport(snapshot) {
                        context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the selected file.")
                    }
                    if (source.isCurrent()) message = "Exported ${snapshot.titleCount} titles and ${snapshot.outingCount} outings."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (source.isCurrent()) message = "Export did not finish: ${e.message ?: "Could not write the file."} The selected file may be incomplete; choose Export JSON to try again."
                } finally { busy = false }
            }
        }
    }
    fun prepare(syncFirst: Boolean) {
        if (busy || !source.isCurrent()) return
        busy = true; message = null; recovery = false
        scope.launch {
            try {
                if (syncFirst) source.syncBeforeExport()
                val snapshot = source.prepareExport()
                if (source.isCurrent()) { prepared = snapshot; picker.launch(snapshot.fileName) }
                else busy = false
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (source.isCurrent()) {
                    message = e.message ?: "Could not prepare the export."
                    recovery = e is CompanionRecoveryRequired
                }
                busy = false
            }
        }
    }
    if (source.isCurrent()) Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Library JSON backup", style = MaterialTheme.typography.titleMedium)
            Text("Export this account’s titles, episode history, viewings, and outings, including saved changes waiting to sync. Compatible with web JSON import. Ticket photos, lists, and account settings are not included.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = { prepare(false) }, enabled = !busy) { Text(if (busy) "Preparing or saving…" else "Export JSON") }
            if (recovery) OutlinedButton(onClick = { prepare(true) }, enabled = !busy) { Text("Sync and try export again") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
