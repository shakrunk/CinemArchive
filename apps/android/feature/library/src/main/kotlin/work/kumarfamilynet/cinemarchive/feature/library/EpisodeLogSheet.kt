package work.kumarfamilynet.cinemarchive.feature.library

import work.kumarfamilynet.cinemarchive.data.rethrowIfCancelled

import work.kumarfamilynet.cinemarchive.data.userMessage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.DraggableStarRating
import work.kumarfamilynet.cinemarchive.core.model.EpisodeDetail
import work.kumarfamilynet.cinemarchive.core.model.EpisodeLogDraft
import work.kumarfamilynet.cinemarchive.core.model.EpisodeWatch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EpisodeHistoryPanel(
    episode: EpisodeDetail,
    onSave: suspend (String, EpisodeLogDraft) -> Unit,
    onDelete: suspend (String, String) -> Unit,
    isSpiderNoir: Boolean = false,
    extraActions: @Composable () -> Unit = {},
) {
    var showLog by rememberSaveable(episode.id) { mutableStateOf(false) }
    var showHistory by rememberSaveable(episode.id) { mutableStateOf(false) }
    var deleteWatch by remember(episode.id) { mutableStateOf<EpisodeWatch?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val hasHistory = episode.watchEvents.isNotEmpty() || episode.ratings.isNotEmpty() || episode.reviews.isNotEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // One compact action row per episode; the full history only renders on request so the
        // season list doesn't grow with every logged watch.
        FlowRow(verticalArrangement = Arrangement.Center) {
            TextButton(onClick = { showLog = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(if (episode.watchCount > 0) "Rate or rewatch" else "Log watch")
            }
            if (hasHistory) TextButton(onClick = { showHistory = !showHistory }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(if (showHistory) "Hide history" else "Show history")
            }
            extraActions()
        }
        if (showHistory && hasHistory) Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                episode.averageRating?.let { Text("Average rating ${"%.1f".format(it)} / 5", style = MaterialTheme.typography.labelLarge) }
                if (episode.watchEvents.isNotEmpty()) Text("Watches", style = MaterialTheme.typography.titleSmall)
                episode.watchEvents.forEach { watch ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(watch.watchedAt?.let(::formatLogDate) ?: "Before CinemArchive", style = MaterialTheme.typography.bodyMedium)
                            EpisodeColorModeBadge(watch.colorMode)
                            watch.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        IconButton(onClick = { deleteWatch = watch; error = null }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete watch", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (episode.ratings.isNotEmpty()) Text("Ratings", style = MaterialTheme.typography.titleSmall)
                episode.ratings.forEach { Text("${it.rating} / 5 · ${formatLogDate(it.ratedAt)}", style = MaterialTheme.typography.bodyMedium) }
                if (episode.reviews.isNotEmpty()) Text("Reviews", style = MaterialTheme.typography.titleSmall)
                episode.reviews.forEach {
                    EpisodeColorModeBadge(it.colorMode)
                    Text(it.reviewText, style = MaterialTheme.typography.bodyMedium)
                    Text(formatLogDate(it.reviewedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (showLog) EpisodeLogSheet(episode, isSpiderNoir, onSave = { onSave(episode.id, it) }, onDismiss = { showLog = false })
    deleteWatch?.let { watch ->
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteWatch = null },
            title = { Text("Delete this watch event?") },
            text = { Text(error ?: "Remove this watch from ${watch.watchedAt?.let(::formatLogDate) ?: "before joining"}? Other watches, ratings, and reviews stay in your history.") },
            dismissButton = { TextButton(enabled = !deleting, onClick = { deleteWatch = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    deleting = true
                    scope.launch {
                        try { onDelete(episode.id, watch.id); deleteWatch = null }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { e.rethrowIfCancelled(); error = e.userMessage("Couldn't delete watch. Try again.") }
                        finally { deleting = false }
                    }
                }) { Text(if (deleting) "Deleting…" else "Delete watch", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeLogSheet(episode: EpisodeDetail, isSpiderNoir: Boolean, onSave: suspend (EpisodeLogDraft) -> Unit, onDismiss: () -> Unit) {
    val watchId = rememberSaveable { UUID.randomUUID().toString() }
    val ratingId = rememberSaveable { UUID.randomUUID().toString() }
    val reviewId = rememberSaveable { UUID.randomUUID().toString() }
    var recordedAt by rememberSaveable { mutableStateOf<String?>(null) }
    var includeWatch by rememberSaveable { mutableStateOf(true) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var prePlatform by rememberSaveable { mutableStateOf(false) }
    var watchNotes by rememberSaveable { mutableStateOf("") }
    var rating by rememberSaveable { mutableStateOf(0.0) }
    var review by rememberSaveable { mutableStateOf("") }
    var showDate by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showModeChoice by rememberSaveable { mutableStateOf(false) }
    var colorMode by rememberSaveable { mutableStateOf<String?>(null) }
    // Once submitted, every retry keeps both the selected mode and original values/identities.
    val editable = !saving && recordedAt == null
    val scope = rememberCoroutineScope()
    fun submit(mode: String?) {
        if (saving) return
        colorMode = mode
        saving = true
        error = null
        showModeChoice = false
        val timestamp = recordedAt ?: Instant.now().toString().also { recordedAt = it }
        scope.launch {
            try {
                onSave(EpisodeLogDraft(watchId, ratingId, reviewId, timestamp, includeWatch,
                    if (prePlatform) null else date, watchNotes.takeIf { includeWatch && it.isNotBlank() },
                    rating.takeIf { it > 0 }, review.trim().takeIf { it.isNotEmpty() }, colorMode))
                onDismiss()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { e.rethrowIfCancelled(); error = e.userMessage("Couldn't save episode log. Retry the same request.") }
            finally { saving = false }
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(episode.episodeName ?: "Episode ${episode.episodeNumber}", style = MaterialTheme.typography.headlineSmall)
            CheckboxRow("Log a watch event", includeWatch, editable) { includeWatch = it }
            if (includeWatch) {
                CheckboxRow("Watched before joining (no date)", prePlatform, editable) { prePlatform = it }
                if (!prePlatform) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Watched ${formatLogDate(date)}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(enabled = editable, onClick = { showDate = true }) { Text("Change date") }
                }
                OutlinedTextField(watchNotes, { watchNotes = it }, label = { Text("Watch notes (optional)") }, modifier = Modifier.fillMaxWidth(), enabled = editable)
            }
            Text("Rating (optional)", style = MaterialTheme.typography.labelLarge)
            if (editable) DraggableStarRating(rating, { rating = it }) else Text("$rating / 5")
            TextButton(enabled = editable && rating > 0, onClick = { rating = 0.0 }) { Text("Clear rating") }
            OutlinedTextField(review, { review = it }, label = { Text("Review (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2, enabled = editable)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") }
                Button(enabled = !saving && (includeWatch || rating > 0 || review.isNotBlank()), onClick = {
                    if (recordedAt != null) submit(colorMode)
                    else if (isSpiderNoir && (includeWatch || review.isNotBlank())) showModeChoice = true
                    else submit(null)
                }) { Text(if (saving) "Saving…" else if (recordedAt != null) "Retry" else "Save") }
            }
        }
    }
    if (showModeChoice) AlertDialog(
        onDismissRequest = { if (!saving) submit(null) },
        title = { Text("How did you experience this?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Spider-Man: Noir")
            Button(enabled = !saving, onClick = { submit("bw") }, modifier = Modifier.fillMaxWidth()) { Text("Authentic Black & White") }
            Button(enabled = !saving, onClick = { submit("color") }, modifier = Modifier.fillMaxWidth()) { Text("True-Hue Full Color") }
        } },
        confirmButton = { TextButton(enabled = !saving, onClick = { submit(null) }) { Text("Not now") } },
    )
    if (showDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { showDate = false }, confirmButton = {
            TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                showDate = false
            }) { Text("Choose date") }
        }, dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } }) { DatePicker(picker) }
    }
}

/** The whole row toggles the box, so the label is a real tap target and stays aligned. */
@Composable
private fun CheckboxRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

/** History dates arrive either as a plain YYYY-MM-DD or as a full instant. */
internal fun formatLogDate(value: String): String {
    val format = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    // An instant is shown in the device's zone so an evening entry doesn't land on the UTC day.
    return runCatching { Instant.parse(value).atZone(java.time.ZoneId.systemDefault()).format(format) }
        .recoverCatching { LocalDate.parse(value.take(10)).format(format) }
        .getOrDefault(value)
}

@Composable
private fun EpisodeColorModeBadge(mode: String?) {
    val label = when (mode) { "bw" -> "B&W"; "color" -> "Color"; else -> return }
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
}
