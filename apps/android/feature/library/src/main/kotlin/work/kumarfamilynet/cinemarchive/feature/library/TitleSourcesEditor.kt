package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.data.*

/** Only owner routes supply this editor; shared detail remains read only. */
@Composable
fun TitleSourcesEditor(titleId: String, prepare: suspend () -> String,
    save: suspend (String, TitleSourcesValues) -> Unit) {
    var open by rememberSaveable(titleId) { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Edit watch link and collection") }
    if (open) TitleSourcesDialog(titleId, prepare, save) { open = false }
}

@Composable
private fun TitleSourcesDialog(titleId: String, prepare: suspend () -> String,
    save: suspend (String, TitleSourcesValues) -> Unit, dismiss: () -> Unit) {
    var opening by rememberSaveable(titleId) { mutableStateOf<String?>(null) }
    var watchUrl by rememberSaveable(titleId) { mutableStateOf("") }
    var home by rememberSaveable(titleId) { mutableStateOf(false) }
    var copies by rememberSaveable(titleId) { mutableStateOf("[]") }
    var format by rememberSaveable(titleId) { mutableStateOf("Blu-ray") }
    var edition by rememberSaveable(titleId) { mutableStateOf("") }
    var adding by rememberSaveable(titleId) { mutableStateOf(false) }
    var formatsOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(titleId, reload) {
        if (opening == null) try {
            val captured = prepare()
            val values = titleSourcesValues(captured)
            watchUrl = values.watchUrl; home = values.homeCollection; copies = values.copiesJson
            opening = captured; error = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not open these saved values." }
    }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() },
        title = { Text("Watch link and collection") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (opening == null && error == null) CircularProgressIndicator()
            if (opening != null) {
                OutlinedTextField(watchUrl, { watchUrl = it }, enabled = !busy, label = { Text("Watch link for friends") },
                    modifier = Modifier.fillMaxWidth())
                Text("Leave the link empty to remove it.", style = MaterialTheme.typography.bodySmall)
                Row {
                    Checkbox(home, { home = it }, enabled = !busy)
                    Text("In my home collection", Modifier.padding(top = 12.dp))
                }
                Text("Physical copies", style = MaterialTheme.typography.titleSmall)
                val visibleCopies = remember(copies) { TitleSourcesValues("", false, copies).copies }
                visibleCopies.forEach { copy ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(listOfNotNull(copy.format, copy.edition).joinToString(" · "))
                        copy.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { copies = removePhysicalCopy(copies, copy.id) }, enabled = !busy) {
                            Text("Remove ${copy.format}${copy.edition?.let { " ($it)" } ?: ""}")
                        }
                    }
                }
                if (adding) {
                    Box {
                        TextButton(onClick = { formatsOpen = true }, enabled = !busy) { Text("Format: $format") }
                        DropdownMenu(formatsOpen, onDismissRequest = { formatsOpen = false }) {
                            physicalCopyFormats.forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = { format = option; formatsOpen = false })
                            }
                        }
                    }
                    OutlinedTextField(edition, { edition = it }, label = { Text("Edition or packaging") },
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(onClick = {
                            copies = addPhysicalCopy(copies, format, edition); edition = ""; adding = false
                        }, enabled = !busy) { Text("Add physical copy") }
                        TextButton(onClick = { adding = false }, enabled = !busy) { Text("Cancel copy") }
                    }
                } else TextButton(onClick = { adding = true }, enabled = !busy) { Text("Catalog a copy") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (opening == null) TextButton(onClick = { reload++ }) { Text("Retry opening") }
        } },
        confirmButton = { TextButton(onClick = {
            val captured = opening ?: return@TextButton
            if (!busy) {
                busy = true; error = null
                scope.launch {
                    try { save(captured, TitleSourcesValues(watchUrl, home, copies)); dismiss() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "Could not save. Your draft is still here." }
                    finally { busy = false }
                }
            }
        }, enabled = opening != null && !busy && !adding) { Text(if (busy) "Saving…" else "Save collection") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Cancel") } })
}
