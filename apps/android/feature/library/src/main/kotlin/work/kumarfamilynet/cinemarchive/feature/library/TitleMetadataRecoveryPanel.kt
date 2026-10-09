package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.data.*

@Composable
fun TitleMetadataRecoveryPanel(source: TitleMetadataRecoverySource, titleId: String? = null) {
    val all by source.changes.collectAsStateWithLifecycle(emptyList())
    val changes = all.filter { titleId == null || it.titleId == titleId }
    var comparison by remember(source, titleId) { mutableStateOf<TitleMetadataComparison?>(null) }
    var confirmDiscard by remember(source, titleId) { mutableStateOf(false) }
    var busy by remember(source, titleId) { mutableStateOf(false) }
    var error by remember(source, titleId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Couldn't finish this saved change. Try again." }
            finally { busy = false }
        }
    }
    if (changes.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Saved title changes", style = MaterialTheme.typography.titleMedium)
            changes.forEach { change ->
                Text("${change.title} · ${change.count} saved change${if (change.count == 1) "" else "s"}")
                Text(if (change.needsReview) "Your edits are kept on this device. Sync older changes, then compare before applying."
                    else "Your edits are saved on this device and waiting for sync.", style = MaterialTheme.typography.bodyMedium)
                change.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row {
                    TextButton(onClick = { action { source.retrySync() } }, enabled = !busy) { Text("Retry sync") }
                    if (change.needsReview) TextButton(onClick = { action { comparison = source.compare(change.titleId) } }, enabled = !busy) { Text("Compare saved edits") }
                }
            }
            if (comparison == null) error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    comparison?.let { shown ->
        AlertDialog(onDismissRequest = { if (!busy) { comparison = null; error = null } },
            title = { Text("Review ${shown.title}") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Compare the current server values with your saved edits. Applying uses this displayed version; another change will require a new review.")
                    if (shown.current == null) Text("This title is no longer on the server. Applying these edits will not recreate it.")
                    MetadataValues("Current server values", shown.current, shown.fields)
                    MetadataValues("Your saved edits", shown.saved, shown.fields)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { confirmDiscard = true }, enabled = !busy) { Text("Discard saved edits") }
                }
            },
            confirmButton = { TextButton(onClick = { action { source.applySaved(shown); comparison = null } }, enabled = !busy && shown.current != null) { Text("Apply saved edits") } },
            dismissButton = { TextButton(onClick = { comparison = null; error = null }, enabled = !busy) { Text("Keep for later") } })
        if (confirmDiscard) AlertDialog(onDismissRequest = { if (!busy) confirmDiscard = false },
            title = { Text("Discard saved title edits?") }, text = { Text("Discard the ${shown.changeCount} displayed saved changes for ${shown.title}? This keeps the current server values.") },
            confirmButton = { TextButton(onClick = { action { source.discard(shown); confirmDiscard = false; comparison = null } }, enabled = !busy) { Text("Discard changes") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }, enabled = !busy) { Text("Cancel") } })
    }
}

@Composable
private fun MetadataValues(label: String, values: TitleMetadataValues?, fields: Set<String>) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    if (values != null) {
        if ("tags" in fields) Text("Tags: ${values.tags.joinToString(", ").ifEmpty { "None" }}")
        if ("status" in fields) Text("Status: ${values.status.replace('_', ' ')}")
        if ("rating" in fields) Text("Rating: ${values.rating?.let { "$it/5" } ?: "Unrated"}")
        if ("custom_watch_url" in fields) Text("Watch link: ${values.watchUrl ?: "None"}")
        if ("in_home_collection" in fields) Text("Home collection: ${if (values.homeCollection == true) "Yes" else "No"}")
        if ("physical_media" in fields) Text("Physical copies: ${values.physicalCopies.joinToString("; ").ifEmpty { "None" }}")
        values.catalog.filterKeys { it in fields }.forEach { (field, value) -> Text("${field.replace('_', ' ')}: $value") }
    } else Text("Unavailable")
}
