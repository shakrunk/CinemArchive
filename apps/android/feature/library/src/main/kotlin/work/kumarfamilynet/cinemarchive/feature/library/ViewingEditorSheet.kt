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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.DraggableStarRating
import work.kumarfamilynet.cinemarchive.core.model.ViewingDraft

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewingEditorSheet(
    initial: ViewingDraft,
    isNew: Boolean,
    onSave: suspend (ViewingDraft, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var date by rememberSaveable { mutableStateOf(initial.date?.take(10) ?: LocalDate.now().toString()) }
    var prePlatform by rememberSaveable { mutableStateOf(initial.date == null) }
    var rating by rememberSaveable { mutableStateOf(initial.rating ?: 0.0) }
    var notes by rememberSaveable { mutableStateOf(initial.notes.orEmpty()) }
    var venue by rememberSaveable { mutableStateOf(initial.venue.orEmpty()) }
    var companions by rememberSaveable { mutableStateOf(initial.companions.joinToString("\n")) }
    var showDatePicker by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (isNew) "Log a viewing" else "Edit viewing", style = MaterialTheme.typography.headlineSmall)
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
                            onSave(initial.copy(date = if (prePlatform) null else date, rating = rating.takeIf { it > 0 },
                                notes = notes.takeIf { it.isNotBlank() }, venue = venue.trim().takeIf { it.isNotEmpty() },
                                companions = if (companions == initial.companions.joinToString("\n")) initial.companions
                                else companions.lines().map { it.trim() }.filter { it.isNotEmpty() }), isNew)
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

/** Saveable wrapper also checks the owner/title after restoration, when remember inputs alone do not. */
internal data class SavedViewingEditor(val draft: ViewingDraft, val isNew: Boolean)
internal fun saveViewingEditor(owner: String?, titleId: String, draft: ViewingDraft, isNew: Boolean): String = org.json.JSONObject()
    .put("owner", owner ?: org.json.JSONObject.NULL).put("titleId", titleId).put("isNew", isNew)
    .put("id", draft.id).put("date", draft.date ?: org.json.JSONObject.NULL).put("rating", draft.rating ?: org.json.JSONObject.NULL)
    .put("notes", draft.notes ?: org.json.JSONObject.NULL).put("venue", draft.venue ?: org.json.JSONObject.NULL)
    .put("companions", org.json.JSONArray(draft.companions)).put("opening", draft.openingContext ?: org.json.JSONObject.NULL).toString()
internal fun restoreViewingEditor(raw: String?, owner: String?, titleId: String?): SavedViewingEditor? = runCatching {
    if (raw == null || titleId == null) return null
    val json = org.json.JSONObject(raw)
    fun text(key: String) = if (json.isNull(key)) null else json.getString(key)
    require(text("owner") == owner && json.getString("titleId") == titleId)
    SavedViewingEditor(ViewingDraft(json.getString("id"), text("date"), if (json.isNull("rating")) null else json.getDouble("rating"),
        text("notes"), text("venue"), json.getJSONArray("companions").let { names -> (0 until names.length()).map(names::getString) }, text("opening")),
        json.getBoolean("isNew"))
}.getOrNull()
