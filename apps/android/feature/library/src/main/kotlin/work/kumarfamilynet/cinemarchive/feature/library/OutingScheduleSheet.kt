package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import work.kumarfamilynet.cinemarchive.core.designsystem.ChoiceOption
import work.kumarfamilynet.cinemarchive.core.designsystem.SegmentedGroup
import work.kumarfamilynet.cinemarchive.core.model.CinemaFormat
import work.kumarfamilynet.cinemarchive.core.model.CinemaOuting

/** Display label for [CinemaFormat] — the fixed UI list from the web plan §4.1, kept as a UI
 *  concern here rather than on the enum itself (the enum stays a plain data value). */
fun CinemaFormat.displayLabel(): String = when (this) {
    CinemaFormat.STANDARD -> "Standard"
    CinemaFormat.IMAX -> "IMAX"
    CinemaFormat.THREE_D -> "3D"
    CinemaFormat.DOLBY -> "Dolby"
    CinemaFormat.SEVENTY_MM -> "70mm"
    CinemaFormat.DRIVE_IN -> "Drive-in"
    CinemaFormat.OTHER -> "Other"
}

/**
 * "I've got tickets" — the scheduling/editing form (web plan §4.1), scoped to what Android v1
 * needs: no friend-aware companion autocomplete (no friend graph yet — plain comma-separated
 * names), no venue autocomplete (deferred polish, not the headline flow). [initial] non-null
 * means editing an existing outing (pre-fills every field); null means a fresh "I've got
 * tickets" schedule.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutingScheduleSheet(
    defaultRuntimeMinutes: Int?,
    initial: CinemaOuting?,
    onDismiss: () -> Unit,
    onSave: (
        showtime: Instant,
        previewsMinutes: Int,
        runtimeMinutes: Int,
        venue: String?,
        companions: List<String>,
        format: CinemaFormat?,
        ticketPrice: Double?,
        seat: String?,
        bookingRef: String?,
        notes: String?,
    ) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val initialInstant = initial?.showtime?.let { Instant.parse(it) } ?: Instant.now().plusSeconds(3600)
    var date by rememberSaveable { mutableStateOf(initialInstant.atZone(zone).toLocalDate().toString()) }
    var time by rememberSaveable { mutableStateOf(initialInstant.atZone(zone).toLocalTime().withSecond(0).withNano(0).toString()) }
    var venue by rememberSaveable { mutableStateOf(initial?.venue ?: "") }
    var companionsText by rememberSaveable { mutableStateOf(initial?.companions?.joinToString(", ") ?: "") }
    var format by rememberSaveable { mutableStateOf(initial?.format ?: CinemaFormat.STANDARD) }
    var previews by rememberSaveable { mutableStateOf((initial?.previewsMinutes ?: 20).toString()) }
    var runtime by rememberSaveable { mutableStateOf((initial?.runtimeMinutes ?: defaultRuntimeMinutes ?: 120).toString()) }
    var ticketPrice by rememberSaveable { mutableStateOf(initial?.ticketPrice?.toString() ?: "") }
    var seat by rememberSaveable { mutableStateOf(initial?.seat ?: "") }
    var bookingRef by rememberSaveable { mutableStateOf(initial?.bookingRef ?: "") }
    var notes by rememberSaveable { mutableStateOf(initial?.notes ?: "") }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val parsedDate = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
    val parsedTime = runCatching { LocalTime.parse(time) }.getOrDefault(LocalTime.of(19, 0))
    val showtimeInstant = parsedDate.atTime(parsedTime).atZone(zone).toInstant()
    val previewsMinutes = previews.toIntOrNull() ?: 20
    val runtimeMinutes = runtime.toIntOrNull()?.coerceAtLeast(1) ?: 120
    val endsAt = showtimeInstant.plusSeconds((previewsMinutes + runtimeMinutes) * 60L)
    val endsAtLabel = endsAt.atZone(zone).toLocalTime()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp, 0.dp, 20.dp, 28.dp)) {
            Text(
                if (initial == null) "I've got tickets" else "Edit tickets",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                PickerField(label = "Date", value = parsedDate.toString(), onClick = { showDatePicker = true }, modifier = Modifier.weight(1f))
                PickerField(label = "Showtime", value = parsedTime.toString().take(5), onClick = { showTimePicker = true }, modifier = Modifier.weight(1f))
            }

            OutlinedTextField(
                value = venue,
                onValueChange = { venue = it },
                label = { Text("Theater") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            )

            OutlinedTextField(
                value = companionsText,
                onValueChange = { companionsText = it },
                label = { Text("Companions (comma separated)") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            )

            Text("FORMAT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            SegmentedGroup(
                options = listOf(CinemaFormat.STANDARD, CinemaFormat.IMAX, CinemaFormat.THREE_D, CinemaFormat.DOLBY)
                    .map { ChoiceOption(it, it.displayLabel()) },
                selected = format,
                onSelect = { format = it },
                modifier = Modifier.padding(bottom = 14.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                OutlinedTextField(
                    value = previews,
                    onValueChange = { previews = it.filter(Char::isDigit) },
                    label = { Text("Previews (min)") },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = runtime,
                    onValueChange = { runtime = it.filter(Char::isDigit) },
                    label = { Text("Runtime (min)") },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                OutlinedTextField(
                    value = ticketPrice,
                    onValueChange = { ticketPrice = it },
                    label = { Text("Ticket price") },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = seat,
                    onValueChange = { seat = it },
                    label = { Text("Seat") },
                    modifier = Modifier.weight(1f),
                )
            }

            OutlinedTextField(
                value = bookingRef,
                onValueChange = { bookingRef = it },
                label = { Text("Booking ref") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            )

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )

            Text(
                "Lets out ≈ ${"%02d:%02d".format(endsAtLabel.hour, endsAtLabel.minute)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 20.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    onSave(
                        showtimeInstant,
                        previewsMinutes,
                        runtimeMinutes,
                        venue.ifBlank { null },
                        companionsText.split(",").map(String::trim).filter(String::isNotBlank),
                        format,
                        ticketPrice.toDoubleOrNull(),
                        seat.ifBlank { null },
                        bookingRef.ifBlank { null },
                        notes.ifBlank { null },
                    )
                    onDismiss()
                }) { Text(if (showtimeInstant.isBefore(Instant.now())) "Log this outing" else "Save tickets") }
            }
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = parsedDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        date = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) {
            androidx.compose.material3.DatePicker(state = state)
        }
    }

    if (showTimePicker) {
        val state = rememberTimePickerState(initialHour = parsedTime.hour, initialMinute = parsedTime.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    time = LocalTime.of(state.hour, state.minute).toString()
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) },
        )
    }
}

/** A tappable field styled like an outlined text field — used instead of a real (readOnly)
 *  `OutlinedTextField` for the date/time pickers, since a readOnly text field still competes
 *  for the tap with its own cursor/focus handling. */
@Composable
private fun PickerField(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.OutlinedCard(onClick = onClick, modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
