package work.kumarfamilynet.cinemarchive.feature.settings

import work.kumarfamilynet.cinemarchive.data.rethrowIfCancelled

import work.kumarfamilynet.cinemarchive.data.userMessage

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
    val copy = recoveryCopy(subject, lifecycleAction, deleting)
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
            } catch (error: Exception) { error.rethrowIfCancelled();
                if (source.isActive()) exportMessage = error.userMessage("Could not export the original.")
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

    // Nothing to review: render nothing, so Data & recovery doesn't stack empty panels.
    val empty = state.cards.isEmpty() && state.error == null && state.message == null && exportMessage == null
    if (!empty) Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    ) { Column(Modifier.padding(16.dp)) {
        Text(if (lifecycle) "Saved outing completions" else "Saved " + subject.label + " changes", style = MaterialTheme.typography.titleMedium)
        Text("These changes couldn't sync automatically. Review each one; the original is always kept for export.",
            style = MaterialTheme.typography.bodySmall)
        state.error?.takeIf { state.focusedId == null }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.message?.takeIf { state.focusedId == null }?.let { Text(it) }
        exportMessage?.let { Text(it) }
        state.cards.forEach { card ->
            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(card.title, style = MaterialTheme.typography.titleSmall)
                Text(card.error ?: if (card.resolved) "Resolved" else "Needs review", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!card.resolved && card.error == null) OutlinedButton(onClick = { controller.open(card.id) }, enabled = !state.busy) {
                        Text("Review saved change")
                    }
                    TextButton(onClick = { export(card.id) }, enabled = !state.busy) {
                        Text(if (card.error == null) "Export original" else "Export raw data")
                    }
                }
            }
        }
        TextButton(onClick = controller::refresh, enabled = !state.busy) { Text("Refresh saved changes") }
    } }

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
                        "An earlier attempt may not have gone through. Confirm it first, then you can change or discard this."
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
                    TextButton(onClick = { controller.open(state.focusedId!!) }, enabled = !state.busy) { Text("Reload latest") }
                }
            },
            confirmButton = {
                if (review != null && !review.resolved) TextButton(
                    onClick = { if (review.pendingAttempt) controller.apply() else confirmation = "apply" },
                    enabled = !state.busy && (review.pendingAttempt || (review.remoteExists && state.selected.isNotEmpty())),
                ) { Text(if (review.pendingAttempt) "Confirm previous attempt" else copy.applyButton) }
            },
            dismissButton = { TextButton(onClick = controller::dismiss) { Text("Close") } },
        )
    }
    confirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(if (action == "apply") copy.applyTitle else "Discard this saved change?") },
            text = { Text(if (action == "apply") copy.applyBody else copy.discardBody) },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                if (action == "apply") controller.apply() else controller.discard()
            }) { Text(if (action == "apply") copy.applyConfirm else "Discard this change") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
        )
    }
}

/** User-facing wording for one recovery subject's review and confirmation dialogs. */
private data class RecoveryCopy(
    val applyButton: String,
    val applyTitle: String,
    val applyBody: String,
    val applyConfirm: String,
    val discardBody: String,
)

private fun recoveryCopy(subject: RecoverySubject, lifecycleAction: String, deleting: Boolean): RecoveryCopy = when {
    subject == RecoverySubject.LIFECYCLE -> RecoveryCopy(
        applyButton = lifecycleAction,
        applyTitle = "$lifecycleAction?",
        applyBody = "This applies the saved action to the outing as it is now. If the outing or its history changed since, " +
            "you'll be asked to review it again. The original is kept for export.",
        applyConfirm = "Confirm outing action",
        discardBody = "Discard this saved outing action? The original is kept for export. Later edits that depend on it " +
            "stay saved for their own review.",
    )
    subject == RecoverySubject.LIST -> RecoveryCopy(
        applyButton = "Reapply list change",
        applyTitle = "Reapply this list change?",
        applyBody = "The saved add or remove for this list and title will sync after your earlier changes. " +
            "The original is kept for export.",
        applyConfirm = "Reapply change",
        discardBody = "Remove only this saved list change. Changes that already synced stay, and your other unsynced " +
            "changes are kept. The original is kept for export.",
    )
    deleting -> RecoveryCopy(
        applyButton = "Delete this viewing",
        applyTitle = "Delete this viewing?",
        applyBody = "Deletes only this viewing, and only if it hasn't changed. Your other history and the title's " +
            "status and rating stay. The original is kept for export.",
        applyConfirm = "Delete only this viewing",
        discardBody = discardKeeping("viewing"),
    )
    else -> RecoveryCopy(
        applyButton = "Apply selected fields",
        applyTitle = "Apply only the selected fields?",
        applyBody = "The selected saved values replace the current ones, as long as the ${subject.item} hasn't changed. " +
            "Unselected saved values are dropped. The original is kept for export.",
        applyConfirm = "Apply selected",
        discardBody = discardKeeping(if (subject == RecoverySubject.OUTING) "plan" else "viewing"),
    )
}

private fun discardKeeping(item: String) = "This removes only this saved change and keeps the current $item. Your " +
    "history and other unsynced changes are kept. The original is kept for export."
