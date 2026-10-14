package work.kumarfamilynet.cinemarchive.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.data.MoviegoingComparison
import work.kumarfamilynet.cinemarchive.data.MoviegoingPreferencesRepository

@Composable
fun MoviegoingPreferencesPanel(repository: MoviegoingPreferencesRepository,
    noteEditor: @Composable (String, () -> Unit) -> Unit) {
    val notes by repository.notes.collectAsState(emptyList())
    val changes by repository.changes.collectAsState(emptyList())
    val originals by repository.preservedOriginals.collectAsState(emptyMap())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    var venue by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var comparison by remember { mutableStateOf<MoviegoingComparison?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun run(action: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "The saved change is retained. Try again." }
            finally { busy = false }
        }
    }
    TextButton(onClick = { open = true }) { Text("Venue notes and theater interest${if (changes.isNotEmpty()) " (${changes.size} saved changes)" else ""}") }
    editing?.let { key -> noteEditor(key) { editing = null } }
    if (open && editing == null) AlertDialog(onDismissRequest = { if (!busy) open = false },
        title = { Text("Moviegoing preferences") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Venue notes and theater interest follow your account. Existing device preferences stay on this device until you choose to sync them.")
            TextButton(onClick = { run { repository.syncDevicePreferences() } }, enabled = !busy) { Text("Sync device preferences to this account") }
            OutlinedTextField(venue, { venue = it }, label = { Text("Venue") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { editing = venue }, enabled = !busy && venue.trim(' ').isNotEmpty()) { Text("Add venue note") }
            notes.forEach { note ->
                TextButton(onClick = { editing = note.venue }, enabled = !busy) { Text(note.venue + if (note.deviceOnly) " · On this device" else "") }
                Text(note.notes.ifEmpty { "Empty saved note" })
            }
            if (changes.isNotEmpty()) {
                Text("Saved changes", style = MaterialTheme.typography.titleSmall)
                Text("An uncertain delivery must retry its original request. A rejected change can be compared before you replace or discard it.")
                TextButton(onClick = { run { repository.retry() } }, enabled = !busy) { Text("Retry delivery") }
                changes.forEach { change ->
                    Text(change.label); Text(change.saved)
                    change.error?.let { Text(it) }
                    TextButton(onClick = { run { comparison = repository.compare(change.id) } }, enabled = !busy) { Text("Compare saved change") }
                }
            }
            comparison?.let { compared ->
                Text(compared.label, style = MaterialTheme.typography.titleSmall)
                Text("Saved: ${compared.saved}"); Text("Current account: ${compared.current}")
                if (compared.canResolve) {
                    TextButton(onClick = { run { repository.resolve(compared, true); comparison = null } }, enabled = !busy) { Text("Apply saved value") }
                    TextButton(onClick = { run { repository.resolve(compared, false); comparison = null } }, enabled = !busy) { Text("Discard saved change") }
                } else Text("Use Retry delivery to confirm the original request before changing or discarding it.")
            }
            if (originals.isNotEmpty() || changes.isNotEmpty()) TextButton(onClick = { run {
                val data = repository.exportOriginals()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Preserved moviegoing changes", data))
            } }, enabled = !busy) { Text("Copy preserved original changes") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = { open = false }, enabled = !busy) { Text("Done") } })
}
