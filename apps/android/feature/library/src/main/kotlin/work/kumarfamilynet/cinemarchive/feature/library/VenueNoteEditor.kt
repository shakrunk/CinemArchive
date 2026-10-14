package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.data.MoviegoingPreferencesRepository

/** An opening snapshot remains fixed across recomposition, rotation and save retries. */
@Composable
fun VenueNoteEditor(repository: MoviegoingPreferencesRepository, venue: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var opening by rememberSaveable(repository, venue) { mutableStateOf<String?>(null) }
    var text by rememberSaveable(repository, venue) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(repository, venue, reload) {
        if (opening == null) try {
            val captured = repository.captureVenue(venue)
            text = JSONObject(captured).getString("notes")
            opening = captured; error = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not open this venue note." }
    }
    fun save(value: String?) {
        val captured = opening ?: return
        busy = true; error = null
        scope.launch {
            try { repository.saveVenue(captured, value); onDismiss() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "The note is still here. Try again." }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(venue) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Parking and transit notes are shared with your signed-in account. An empty note stays saved; Remove deletes it.")
            OutlinedTextField(text, { text = it }, label = { Text("Venue note") }, enabled = opening != null && !busy,
                modifier = Modifier.fillMaxWidth(), minLines = 3)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (opening == null) TextButton(onClick = { reload++ }) { Text("Retry opening") }
            if (opening != null) TextButton(onClick = { save(null) }, enabled = !busy) { Text("Remove saved note") }
        } },
        confirmButton = { TextButton(onClick = { save(text) }, enabled = opening != null && !busy) { Text(if (busy) "Saving…" else "Save note") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}
