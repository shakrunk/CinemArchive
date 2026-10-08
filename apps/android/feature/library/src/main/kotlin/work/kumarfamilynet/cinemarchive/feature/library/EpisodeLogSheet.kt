package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.DraggableStarRating
import work.kumarfamilynet.cinemarchive.core.model.EpisodeDetail
import work.kumarfamilynet.cinemarchive.core.model.EpisodeLogDraft
import work.kumarfamilynet.cinemarchive.core.model.EpisodeWatch

@Composable
internal fun EpisodeHistoryPanel(
    episode: EpisodeDetail,
    onSave: suspend (String, EpisodeLogDraft) -> Unit,
    onDelete: suspend (String, String) -> Unit,
) {
    var showLog by rememberSaveable(episode.id) { mutableStateOf(false) }
    var deleteWatch by remember(episode.id) { mutableStateOf<EpisodeWatch?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        episode.averageRating?.let { Text("Average rating: ${"%.1f".format(it)} / 5", style = MaterialTheme.typography.bodySmall) }
        if (episode.watchEvents.isNotEmpty()) Text("Watch history", style = MaterialTheme.typography.titleSmall)
        episode.watchEvents.forEach { watch ->
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(watch.watchedAt?.take(10) ?: "Before CinemArchive", style = MaterialTheme.typography.bodySmall)
                    watch.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                TextButton(onClick = { deleteWatch = watch; error = null }) { Text("Delete watch") }
            }
        }
        if (episode.ratings.isNotEmpty()) Text("Rating history", style = MaterialTheme.typography.titleSmall)
        episode.ratings.forEach { Text("${it.rating} / 5 · ${it.ratedAt}", style = MaterialTheme.typography.bodySmall) }
        if (episode.reviews.isNotEmpty()) Text("Reviews", style = MaterialTheme.typography.titleSmall)
        episode.reviews.forEach {
            Text(it.reviewText, style = MaterialTheme.typography.bodySmall)
            Text(it.reviewedAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { showLog = true }) {
            Text(if (episode.watchCount > 0) "Add rating, review, or rewatch" else "Log watch, rating, or review")
        }
    }
    if (showLog) EpisodeLogSheet(episode, onSave = { onSave(episode.id, it) }, onDismiss = { showLog = false })
    deleteWatch?.let { watch ->
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteWatch = null },
            title = { Text("Delete this watch event?") },
            text = { Text(error ?: "Remove this watch from ${watch.watchedAt?.take(10) ?: "before joining"}? Other watches, ratings, and reviews stay in your history.") },
            dismissButton = { TextButton(enabled = !deleting, onClick = { deleteWatch = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    deleting = true
                    scope.launch {
                        try { onDelete(episode.id, watch.id); deleteWatch = null }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Couldn't delete watch. Try again." }
                        finally { deleting = false }
                    }
                }) { Text(if (deleting) "Deleting…" else "Delete watch", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeLogSheet(episode: EpisodeDetail, onSave: suspend (EpisodeLogDraft) -> Unit, onDismiss: () -> Unit) {
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
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(episode.episodeName ?: "Episode ${episode.episodeNumber}", style = MaterialTheme.typography.headlineSmall)
            Row {
                Checkbox(includeWatch, { includeWatch = it }, enabled = !saving)
                Text("Log a watch event", Modifier.padding(top = 12.dp))
            }
            if (includeWatch) {
                Row {
                    Checkbox(prePlatform, { prePlatform = it }, enabled = !saving)
                    Text("Watched before joining (no date)", Modifier.padding(top = 12.dp))
                }
                if (!prePlatform) TextButton(enabled = !saving, onClick = { showDate = true }) { Text("Watched on $date · Change date") }
                OutlinedTextField(watchNotes, { watchNotes = it }, label = { Text("Watch notes (optional)") }, modifier = Modifier.fillMaxWidth(), enabled = !saving)
            }
            Text("Rating (optional, logged independently)", style = MaterialTheme.typography.labelLarge)
            if (!saving) DraggableStarRating(rating, { rating = it }) else Text("$rating / 5")
            TextButton(enabled = !saving && rating > 0, onClick = { rating = 0.0 }) { Text("Clear rating") }
            OutlinedTextField(review, { review = it }, label = { Text("Review (optional, logged independently)") }, modifier = Modifier.fillMaxWidth(), minLines = 2, enabled = !saving)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") }
                Button(enabled = !saving && (includeWatch || rating > 0 || review.isNotBlank()), onClick = {
                    saving = true
                    error = null
                    val timestamp = recordedAt ?: Instant.now().toString().also { recordedAt = it }
                    scope.launch {
                        try {
                            onSave(EpisodeLogDraft(watchId, ratingId, reviewId, timestamp, includeWatch,
                                if (prePlatform) null else date, watchNotes.takeIf { includeWatch && it.isNotBlank() },
                                rating.takeIf { it > 0 }, review.trim().takeIf { it.isNotEmpty() }))
                            onDismiss()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Couldn't save episode log. Try again." }
                        finally { saving = false }
                    }
                }) { Text(if (saving) "Saving…" else "Save") }
            }
        }
    }
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
