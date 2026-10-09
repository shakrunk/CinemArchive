package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import work.kumarfamilynet.cinemarchive.data.OutingRecoverySource

/** Current-account pending intents, distinct from the unknown-owner pre-isolation archive. */
@Composable
fun OutingRecoverySection(source: OutingRecoverySource, onExport: ((String) -> Unit)? = null) =
    OutingRecoverySection(source, RecoverySubject.OUTING, onExport)

@Composable
fun OutingRecoverySection(source: OutingRecoverySource, subject: RecoverySubject, onExport: ((String) -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val controller = remember(source, subject) { OutingRecoveryController(source, scope, subject) }
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    var preparedExport by remember(source) { mutableStateOf<String?>(null) }
    var exportMessage by remember(source) { mutableStateOf<String?>(null) }
    var confirmation by remember(source) { mutableStateOf<String?>(null) }
    val deleting = subject == RecoverySubject.VIEWING && "delete" in state.selected
    val lifecycle = subject == RecoverySubject.LIFECYCLE
    val lifecycleAction = state.review?.fields?.firstOrNull { it.key == "lifecycleAction" }?.label ?: "Apply outing action"
    val document = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val original = preparedExport
        preparedExport = null
        if (uri != null && original != null && source.isActive()) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(source.isActive()) { "Account changed." }
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(original) }
                        ?: error("Could not open the selected file.")
                }
                if (source.isActive()) exportMessage = "Original saved change exported."
            } catch (error: Exception) {
                if (source.isActive()) exportMessage = error.message ?: "Could not export the original."
            }
        }
    }
    fun export(id: String) = controller.export(id) { original ->
        if (onExport != null) onExport(original)
        else { preparedExport = original; document.launch("cinemarchive-" + subject.label.replace(' ', '-') + "-change.json") }
    }
    DisposableEffect(controller) { onDispose { preparedExport = null; controller.close() } }
    LaunchedEffect(source) { source.changes.collect { controller.refresh() } }
    if (!source.isActive()) return

    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(if (lifecycle) "Saved outing completions" else "Saved " + subject.label + " changes", style = MaterialTheme.typography.titleMedium)
        Text("Review changes that could not be safely synced. Originals remain available to export.",
            style = MaterialTheme.typography.bodySmall)
        state.error?.takeIf { state.focusedId == null }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.message?.takeIf { state.focusedId == null }?.let { Text(it) }
        exportMessage?.let { Text(it) }
        if (state.cards.isEmpty()) Text(if (state.busy) "Loading…" else if (lifecycle) "No saved outing completions." else "No saved " + subject.label + " changes.", style = MaterialTheme.typography.bodySmall)
        state.cards.forEach { card ->
            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(card.title, style = MaterialTheme.typography.titleSmall)
                Text(card.error ?: if (card.resolved) "Resolved · original retained" else "Needs review", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!card.resolved && card.error == null) OutlinedButton(onClick = { controller.open(card.id) }, enabled = !state.busy) {
                        Text("Review saved change")
                    }
                    TextButton(onClick = { export(card.id) }, enabled = !state.busy) {
                        Text(if (card.error == null) "Export original" else "Export preserved raw data")
                    }
                }
            }
        }
        TextButton(onClick = controller::refresh, enabled = !state.busy) { Text("Refresh saved changes") }
    }

    if (state.focusedId != null) {
        val review = state.review
        AlertDialog(
            onDismissRequest = controller::dismiss,
            title = { Text(review?.title ?: "Saved " + subject.label + " change") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    if (state.busy) Text("Working…")
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    state.message?.let { Text(it) }
                    review?.message?.let { Text(it) }
                    if (review?.pendingAttempt == true) Text(
                        "A previous attempt could not be confirmed. Retry that same attempt before changing or discarding it."
                    )
                    review?.fields?.forEach { field ->
                        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            Row {
                                Checkbox(
                                    checked = field.key in state.selected,
                                    onCheckedChange = { controller.select(field.key, it) },
                                    enabled = field.selectable && (!lifecycle || field.key == "lifecycleAction") && !state.busy && !review.pendingAttempt && !review.resolved,
                                    modifier = Modifier.semantics { contentDescription = "Reapply " + field.label },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(field.label, style = MaterialTheme.typography.titleSmall)
                                    Text("Saved: " + field.saved)
                                    Text("Current: " + field.current)
                                }
                            }
                        }
                    }
                    if ("companions" in state.selected) Text(
                        "Replacing companions uses the saved list. Current friend links not present in that list will be removed.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { export(state.focusedId!!) }, enabled = !state.busy) { Text("Export original data") }
                    if (review?.resolved != true) {
                        OutlinedButton(onClick = { confirmation = "discard" },
                            enabled = !state.busy && review?.pendingAttempt != true) { Text("Discard saved change") }
                    }
                    TextButton(onClick = { controller.open(state.focusedId!!) }, enabled = !state.busy) { Text("Reload current " + subject.item) }
                }
            },
            confirmButton = {
                if (review != null && !review.resolved) TextButton(
                    onClick = { if (review.pendingAttempt) controller.apply() else confirmation = "apply" },
                    enabled = !state.busy && (review.pendingAttempt || (review.remoteExists && state.selected.isNotEmpty())),
                ) { Text(if (review.pendingAttempt) "Confirm previous attempt" else if (lifecycle) lifecycleAction else if (deleting) "Delete this viewing" else if (subject == RecoverySubject.LIST) "Queue saved membership" else "Apply selected fields") }
            },
            dismissButton = { TextButton(onClick = controller::dismiss) { Text("Close") } },
        )
    }
    confirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(if (action == "apply") { if (subject == RecoverySubject.LIST) "Queue this saved membership?" else if (lifecycle) "$lifecycleAction?" else if (deleting) "Delete this exact viewing?" else "Apply only selected fields?" } else "Discard this saved change?") },
            text = { Text(when {
                action == "apply" && subject == RecoverySubject.LIST -> "Queue the saved add or remove action for this list and title. It will sync in its original order; the original remains available to export."
                action == "apply" && lifecycle -> "Apply this whole action only against the current outing shown here. If the outing or its linked history changed, review the new state before trying again. The original saved action remains available to export."
                action == "apply" && deleting -> "Delete only this viewing if it has not changed. Other viewing history and the title's status and rating are preserved. The original saved change remains available to export."
                action == "apply" -> "Selected saved values will replace the current values only if the " + subject.item + " has not changed. The other saved values will be discarded. The original remains available to export."
                lifecycle -> "Discard this saved outing action? Its original remains available to export. Dependent saved edits are preserved for separate review; discarding does not authorize them to overwrite current history."
                subject == RecoverySubject.LIST -> "Remove only this queued membership change. Changes already delivered are not undone, and other pending changes are preserved. The original remains available to export."
                subject == RecoverySubject.OUTING -> "This removes only this saved change from the queue and keeps the current plan. Viewing history and other pending changes are preserved. The original remains available to export."
                else -> "This removes only this saved change from the queue and keeps the current viewing. Other viewing history and pending changes are preserved. The original remains available to export."
            }) },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                if (action == "apply") controller.apply() else controller.discard()
            }) { Text(if (action == "apply") { if (subject == RecoverySubject.LIST) "Queue membership change" else if (lifecycle) "Confirm outing action" else if (deleting) "Delete only this viewing" else "Apply selected and keep other current values" } else "Discard this change") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
        )
    }
}

