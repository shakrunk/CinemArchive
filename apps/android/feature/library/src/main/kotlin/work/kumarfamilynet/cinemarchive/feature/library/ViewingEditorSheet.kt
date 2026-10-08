package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import work.kumarfamilynet.cinemarchive.core.model.Viewing
import work.kumarfamilynet.cinemarchive.core.model.ViewingDraft

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewingEditorSheet(
    initial: Viewing?,
    onSave: suspend (ViewingDraft, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val id = rememberSaveable { initial?.id ?: UUID.randomUUID().toString() }
    var date by rememberSaveable { mutableStateOf(initial?.date?.take(10) ?: LocalDate.now().toString()) }
    var prePlatform by rememberSaveable { mutableStateOf(initial != null && initial.date == null) }
    var rating by rememberSaveable { mutableStateOf(initial?.rating ?: 0.0) }
    var notes by rememberSaveable { mutableStateOf(initial?.notes.orEmpty()) }
    var venue by rememberSaveable { mutableStateOf(initial?.venue.orEmpty()) }
    var companions by rememberSaveable { mutableStateOf(initial?.companions?.joinToString("\n").orEmpty()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (initial == null) "Log a viewing" else "Edit viewing", style = MaterialTheme.typography.headlineSmall)
            Row {
                Checkbox(checked = prePlatform, onCheckedChange = { prePlatform = it }, enabled = !saving)
                Text("Watched before joining", modifier = Modifier.padding(top = 12.dp))
            }
            if (!prePlatform) TextButton(onClick = { showDatePicker = true }, enabled = !saving) { Text("Watched on $date · Change date") }
            Text("Rating", style = MaterialTheme.typography.labelLarge)
            if (!saving) DraggableStarRating(rating, onRatingChange = { rating = it })
            else Text(if (rating == 0.0) "Unrated" else "$rating / 5")
            TextButton(onClick = { rating = 0.0 }, enabled = !saving && rating > 0) { Text("Clear rating") }
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), enabled = !saving, minLines = 2)
            OutlinedTextField(venue, { venue = it }, label = { Text("Venue") }, modifier = Modifier.fillMaxWidth(), enabled = !saving, singleLine = true)
            OutlinedTextField(companions, { companions = it }, label = { Text("Companions") }, supportingText = { Text("One name per line") }, modifier = Modifier.fillMaxWidth(), enabled = !saving)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
                Button(enabled = !saving, onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            onSave(ViewingDraft(id, if (prePlatform) null else date, rating.takeIf { it > 0 },
                                notes.takeIf { it.isNotBlank() }, venue.trim().takeIf { it.isNotEmpty() },
                                companions.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()), initial == null)
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "Couldn't save viewing. Try again."
                        } finally {
                            saving = false
                        }
                    }
                }) { Text(if (saving) "Saving…" else "Save viewing") }
            }
        }
    }
    if (showDatePicker) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { showDatePicker = false }, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                showDatePicker = false
            }, enabled = picker.selectedDateMillis != null) { Text("Choose date") }
        }, dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }) { DatePicker(picker) }
    }
}
