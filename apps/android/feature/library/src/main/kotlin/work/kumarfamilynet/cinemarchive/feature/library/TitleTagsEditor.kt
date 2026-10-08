package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun appendTitleTag(tags: List<String>, input: String): List<String> {
    val tag = input.trim().trimEnd(',')
    return if (tag.isBlank() || tags.contains(tag)) tags else tags + tag
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TitleTagsEditor(titleId: String, tags: List<String>, onSave: suspend (List<String>) -> Unit) {
    var input by rememberSaveable(titleId) { mutableStateOf("") }
    var error by remember(titleId) { mutableStateOf<String?>(null) }
    var saving by remember(titleId) { mutableStateOf(false) }
    var confirmClear by remember(titleId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun save(next: List<String>, clearInput: Boolean = false) {
        if (saving) return
        saving = true
        error = null
        scope.launch {
            try { onSave(next); if (clearInput) input = "" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Couldn't save tags. Your input is still here; try again." }
            finally { saving = false }
        }
    }
    fun commit(value: String = input) {
        val next = appendTitleTag(tags, value)
        if (next == tags) { input = ""; return }
        save(next, true)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tags", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { tag ->
                InputChip(selected = false, enabled = !saving, onClick = { save(tags.filterNot { it == tag }) },
                    label = { Text("Remove tag $tag") })
            }
        }
        OutlinedTextField(value = input, onValueChange = { value ->
            input = value
            if (value.endsWith(',')) commit(value)
        }, enabled = !saving, label = { Text("New tag name") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { commit() }))
        Row {
            TextButton(onClick = { commit() }, enabled = !saving && input.isNotBlank()) { Text("Add tag") }
            if (tags.isNotEmpty()) TextButton(onClick = { confirmClear = true }, enabled = !saving) { Text("Clear all tags") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear all tags?") },
        text = { Text("Remove every tag from this title?") },
        confirmButton = { TextButton(onClick = { confirmClear = false; save(emptyList()) }) { Text("Clear tags") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}
