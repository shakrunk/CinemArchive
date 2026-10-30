package work.kumarfamilynet.cinemarchive.feature.settings

import work.kumarfamilynet.cinemarchive.data.rethrowIfCancelled

import work.kumarfamilynet.cinemarchive.data.userMessage

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.model.OutingReminderRules
import work.kumarfamilynet.cinemarchive.data.MoviegoingComparison
import work.kumarfamilynet.cinemarchive.data.MoviegoingPreferencesRepository

@Composable
fun MoviegoingPreferencesPanel(repository: MoviegoingPreferencesRepository,
    reminderLeadMinutes: Int? = null, onReminderLeadChange: ((Int?) -> Unit)? = null,
    noteEditor: @Composable (String, () -> Unit) -> Unit) {
    val notes by repository.notes.collectAsState(emptyList())
    val changes by repository.changes.collectAsState(emptyList())
    val originals by repository.preservedOriginals.collectAsState(emptyMap())
    val deviceOnly by repository.deviceOnlyCount.collectAsState(0)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
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
            catch (e: Exception) { e.rethrowIfCancelled(); error = e.userMessage("The saved change is retained. Try again.") }
            finally { busy = false }
        }
    }
    editing?.let { key -> noteEditor(key) { editing = null } }
    // Rendered directly as the Moviegoing settings screen's content (no nested dialog).
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PreferenceCard("Venue notes") {
            Text("Private notes per theater — parking, best seats, snacks. They follow your account.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (notes.isEmpty()) Text("No venue notes yet.", style = MaterialTheme.typography.bodyMedium)
            notes.forEach { note ->
                Surface(onClick = { editing = note.venue }, enabled = !busy, shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(note.venue, style = MaterialTheme.typography.titleSmall)
                        Text(note.notes.ifEmpty { "Empty note" }, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (note.deviceOnly) Text("On this device only", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            OutlinedTextField(venue, { venue = it }, label = { Text("Add a venue") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = { editing = venue }, enabled = !busy && venue.trim(' ').isNotEmpty()) {
                        Icon(Icons.Filled.Add, contentDescription = "Add venue note")
                    }
                })
        }
        // Device-local (#213): the lead and what was already announced never leave this phone.
        if (onReminderLeadChange != null) PreferenceCard("Showtime reminders") {
            Text("A notification before a scheduled trip starts. This setting stays on this device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilterChip(selected = reminderLeadMinutes == null, onClick = { onReminderLeadChange(null) }, label = { Text("Off") })
                OutingReminderRules.LEAD_OPTIONS_MINUTES.forEach { minutes ->
                    FilterChip(selected = reminderLeadMinutes == minutes, onClick = { onReminderLeadChange(minutes) },
                        label = { Text(reminderLeadLabel(minutes)) })
                }
            }
            Text("Needs notifications allowed. Without exact alarms in system settings a reminder may not arrive.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (changes.isNotEmpty()) PreferenceCard("Unsynced changes") {
            Text("Some edits haven't reached your account yet. Retry, or compare an edit with your account before keeping or discarding it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            changes.forEach { change ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(change.label, style = MaterialTheme.typography.titleSmall)
                        Text(change.saved, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        change.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { run { comparison = repository.compare(change.id) } }, enabled = !busy) { Text("Compare saved change") }
                }
            }
            comparison?.let { compared ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(compared.label, style = MaterialTheme.typography.titleSmall)
                        Text("Saved: ${compared.saved}"); Text("Current account: ${compared.current}")
                        if (compared.canResolve) Row {
                            TextButton(onClick = { run { repository.resolve(compared, true); comparison = null } }, enabled = !busy) { Text("Apply saved value") }
                            TextButton(onClick = { run { repository.resolve(compared, false); comparison = null } }, enabled = !busy) { Text("Discard saved change") }
                        } else Text("Retry first to confirm whether the original edit arrived.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row {
                OutlinedButton(onClick = { run { repository.retry() } }, enabled = !busy) { Text("Retry delivery") }
            }
        }
        // Older notes and theater interest saved before account sync stay on this device until moved.
        if (deviceOnly > 0) TextButton(onClick = { run { repository.syncDevicePreferences() } }, enabled = !busy) {
            Text("Move $deviceOnly preference${if (deviceOnly == 1) "" else "s"} from this device to your account")
        }
        if (originals.isNotEmpty() || changes.isNotEmpty()) TextButton(onClick = { run {
            val data = repository.exportOriginals()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Preserved moviegoing changes", data))
        } }, enabled = !busy) { Text("Copy preserved original changes") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

internal fun reminderLeadLabel(minutes: Int): String =
    if (minutes % 60 == 0) "${minutes / 60} hour${if (minutes == 60) "" else "s"}" else "$minutes min"

@Composable
private fun PreferenceCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
