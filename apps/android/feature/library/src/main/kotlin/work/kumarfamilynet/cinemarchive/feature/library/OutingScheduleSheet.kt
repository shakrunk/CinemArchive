package work.kumarfamilynet.cinemarchive.feature.library

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.ChoiceOption
import work.kumarfamilynet.cinemarchive.core.designsystem.SegmentedGroup
import work.kumarfamilynet.cinemarchive.core.designsystem.decodeTicketBarcode
import work.kumarfamilynet.cinemarchive.core.model.CinemaFormat
import work.kumarfamilynet.cinemarchive.core.model.CinemaOuting
import work.kumarfamilynet.cinemarchive.core.model.SeatAssignment
import work.kumarfamilynet.cinemarchive.core.model.TicketBarcodeFormat

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
 * "I've got tickets" — the scheduling/editing form (web plan §4.1). Venue and companions
 * autocomplete from the user's own outing history (issues #197/#198); no friend-aware
 * suggestions yet (no friend graph — plain comma-separated names for companions). [initial]
 * non-null means editing an existing outing (pre-fills every field); null means a fresh "I've
 * got tickets" schedule. [venueNotes] backs the per-venue parking/transit notes pre-fill
 * (issue #214): picking a venue from the autocomplete list loads that venue's saved notes;
 * [onSaveVenueNotes] is fired on Save so edits to those notes are remembered for next time.
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
        seating: SeatAssignment,
        bookingRef: String?,
        notes: String?,
    ) -> Unit,
    venueSuggestions: List<String> = emptyList(),
    companionSuggestions: List<String> = emptyList(),
    venueNotes: Map<String, String> = emptyMap(),
    onSaveVenueNotes: (venue: String, notes: String) -> Unit = { _, _ -> },
    /** Picks, decodes (GitHub #219 — see [decodeTicketBarcode]), and persists a ticket photo
     *  for [initial]'s outing. Only offered once an outing already exists ([initial] != null),
     *  since a capture needs a real outing id to attach itself to. */
    onCaptureTicket: (outingId: String, imagePath: String, barcodePayload: String?, barcodeFormat: TicketBarcodeFormat?) -> Unit = { _, _, _, _ -> },
    onClearTicketCapture: (outingId: String) -> Unit = {},
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
    var auditorium by rememberSaveable { mutableStateOf(initial?.auditorium ?: "") }
    var seatRow by rememberSaveable { mutableStateOf(initial?.seatRow ?: "") }
    var seats by rememberSaveable { mutableStateOf(initial?.seats?.joinToString(", ") ?: "") }
    var bookingRef by rememberSaveable { mutableStateOf(initial?.bookingRef ?: "") }
    var notes by rememberSaveable { mutableStateOf(initial?.notes ?: "") }
    var parkingNotes by rememberSaveable { mutableStateOf(initial?.venue?.let { venueNotes[it] } ?: "") }

    var ticketImagePath by rememberSaveable { mutableStateOf(initial?.ticketImagePath) }
    var ticketBarcodeFormat by rememberSaveable { mutableStateOf(initial?.ticketBarcodeFormat?.name) }
    var isCapturingTicket by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val ticketPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val outingId = initial?.id
        if (uri == null || outingId == null) return@rememberLauncherForActivityResult
        isCapturingTicket = true
        coroutineScope.launch(Dispatchers.IO) {
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes != null) {
                val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                val decoded = bitmap?.let(::decodeTicketBarcode)
                val extension = context.contentResolver.getType(uri)
                    ?.let(android.webkit.MimeTypeMap.getSingleton()::getExtensionFromMimeType)
                    ?: "jpg"
                val ticketsDir = File(context.filesDir, "tickets").apply { mkdirs() }
                val file = File(ticketsDir, "$outingId.$extension")
                file.writeBytes(bytes)
                ticketImagePath = file.absolutePath
                ticketBarcodeFormat = decoded?.second?.name
                onCaptureTicket(outingId, file.absolutePath, decoded?.first, decoded?.second)
            }
            isCapturingTicket = false
        }
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var venueMenuExpanded by remember { mutableStateOf(false) }
    var companionMenuExpanded by remember { mutableStateOf(false) }

    val venueMatches = remember(venue, venueSuggestions) {
        if (venue.isBlank()) venueSuggestions else venueSuggestions.filter { it.contains(venue, ignoreCase = true) && !it.equals(venue, ignoreCase = true) }
    }
    val typedCompanions = remember(companionsText) { companionsText.split(",").map(String::trim) }
    val companionPrefix = typedCompanions.lastOrNull().orEmpty()
    val companionMatches = remember(companionsText, companionSuggestions) {
        val already = typedCompanions.dropLast(1).map { it.lowercase() }.toSet()
        companionSuggestions.filter {
            it.lowercase() !in already &&
                (companionPrefix.isBlank() || it.contains(companionPrefix, ignoreCase = true)) &&
                !it.equals(companionPrefix, ignoreCase = true)
        }
    }

    val parsedDate = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
    val parsedTime = runCatching { LocalTime.parse(time) }.getOrDefault(LocalTime.of(19, 0))
    val showtimeInstant = parsedDate.atTime(parsedTime).atZone(zone).toInstant()
    val previewsMinutes = previews.toIntOrNull() ?: 20
    val runtimeMinutes = runtime.toIntOrNull()?.coerceAtLeast(1) ?: 120
    val endsAt = showtimeInstant.plusSeconds((previewsMinutes + runtimeMinutes) * 60L)
    val endsAtLabel = endsAt.atZone(zone).toLocalTime()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp, 0.dp, 20.dp, 28.dp)) {
            Text(
                if (initial == null) "I've got tickets" else "Edit tickets",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                PickerField(label = "Date", value = parsedDate.toString(), onClick = { showDatePicker = true }, modifier = Modifier.weight(1f))
                PickerField(label = "Showtime", value = parsedTime.toString().take(5), onClick = { showTimePicker = true }, modifier = Modifier.weight(1f))
            }

            ExposedDropdownMenuBox(
                expanded = venueMenuExpanded && venueMatches.isNotEmpty(),
                onExpandedChange = { venueMenuExpanded = it },
                modifier = Modifier.padding(bottom = 14.dp),
            ) {
                OutlinedTextField(
                    value = venue,
                    onValueChange = { venue = it; venueMenuExpanded = true },
                    label = { Text("Theater") },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, true),
                )
                ExposedDropdownMenu(expanded = venueMenuExpanded && venueMatches.isNotEmpty(), onDismissRequest = { venueMenuExpanded = false }) {
                    venueMatches.forEach { suggestion ->
                        DropdownMenuItem(
                            text = { Text(suggestion) },
                            onClick = {
                                venue = suggestion
                                parkingNotes = venueNotes[suggestion] ?: parkingNotes
                                venueMenuExpanded = false
                            },
                        )
                    }
                }
            }

            ExposedDropdownMenuBox(
                expanded = companionMenuExpanded && companionMatches.isNotEmpty(),
                onExpandedChange = { companionMenuExpanded = it },
                modifier = Modifier.padding(bottom = 14.dp),
            ) {
                OutlinedTextField(
                    value = companionsText,
                    onValueChange = { companionsText = it; companionMenuExpanded = true },
                    label = { Text("Companions (comma separated)") },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, true),
                )
                ExposedDropdownMenu(expanded = companionMenuExpanded && companionMatches.isNotEmpty(), onDismissRequest = { companionMenuExpanded = false }) {
                    companionMatches.forEach { suggestion ->
                        DropdownMenuItem(
                            text = { Text(suggestion) },
                            onClick = {
                                val prefix = companionsText.substringBeforeLast(",", "").let { if (it.isBlank()) "" else "$it, " }
                                companionsText = "$prefix$suggestion, "
                                companionMenuExpanded = false
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = parkingNotes,
                onValueChange = { parkingNotes = it },
                label = { Text("Parking / transit notes") },
                supportingText = { Text("Remembered for this venue") },
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

            OutlinedTextField(
                value = ticketPrice,
                onValueChange = { ticketPrice = it },
                label = { Text("Ticket price") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            )

            // Auditorium first — the order you need them in at the theater, and the order
            // TicketScreen's in-theater view renders them.
            Text(
                "WHERE YOU'RE SITTING",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                OutlinedTextField(
                    value = auditorium,
                    onValueChange = { auditorium = it },
                    label = { Text("Auditorium") },
                    singleLine = true,
                    modifier = Modifier.weight(1.1f),
                )
                OutlinedTextField(
                    value = seatRow,
                    onValueChange = { seatRow = it },
                    label = { Text("Row") },
                    singleLine = true,
                    modifier = Modifier.weight(0.8f),
                )
                OutlinedTextField(
                    value = seats,
                    onValueChange = { seats = it },
                    label = { Text("Seats") },
                    singleLine = true,
                    modifier = Modifier.weight(1.1f),
                )
            }

            // Pre-#221 outings only have the free-text string; the form can't safely split
            // it, so it's shown as a hint rather than silently dropped.
            initial?.seat?.takeIf { it.isNotBlank() }?.let { legacySeat ->
                Text(
                    "Previously saved as “$legacySeat” — fill in the fields above to replace it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }

            OutlinedTextField(
                value = bookingRef,
                onValueChange = { bookingRef = it },
                label = { Text("Booking ref") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            )

            // Capturing the real ticket needs a row id to attach the photo to (issue #219), so
            // this is edit-only — a fresh "I've got tickets" flow has nothing to capture yet.
            if (initial != null) {
                Text(
                    "TICKET PHOTO",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                ) {
                    ticketImagePath?.let { path ->
                        AsyncImage(
                            model = File(path),
                            contentDescription = "Captured ticket photo",
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        TextButton(onClick = { ticketPickerLauncher.launch("image/*") }, enabled = !isCapturingTicket) {
                            Icon(Icons.Filled.ConfirmationNumber, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                if (ticketImagePath != null) "Replace ticket photo" else "Add ticket photo",
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        when {
                            isCapturingTicket -> CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            ticketBarcodeFormat != null -> Text(
                                "Scanned as $ticketBarcodeFormat",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ticketImagePath != null -> Text(
                                "No scannable code found — photo saved anyway",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (ticketImagePath != null) {
                        TextButton(onClick = {
                            ticketImagePath = null
                            ticketBarcodeFormat = null
                            onClearTicketCapture(initial.id)
                        }) { Text("Remove") }
                    }
                }
            }

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
                        SeatAssignment(
                            auditorium = auditorium.ifBlank { null },
                            seatRow = seatRow.ifBlank { null },
                            seats = SeatAssignment.parseSeats(seats),
                        ),
                        bookingRef.ifBlank { null },
                        notes.ifBlank { null },
                    )
                    if (venue.isNotBlank()) onSaveVenueNotes(venue, parkingNotes)
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
