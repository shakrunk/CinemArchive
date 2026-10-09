package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.model.TitleDetail
import work.kumarfamilynet.cinemarchive.data.*

/** Uses actual episode history, matching web counts; only the series action excludes Specials. */
@Composable
fun PrePlatformWatchControl(
    detail: TitleDetail,
    selectedSeasonNumber: Int,
    ownerId: String,
    pending: List<EpisodeBulkPending>,
    prepare: suspend (Int?) -> EpisodeBulkOpening,
    save: suspend (EpisodeBulkOpening) -> Unit,
    retry: suspend () -> Unit,
    compare: suspend (String) -> EpisodeBulkComparison,
    applyReviewed: suspend (EpisodeBulkComparison) -> Unit,
    discard: suspend (String) -> Unit,
) {
    val coroutine = rememberCoroutineScope()
    var openingJson by rememberSaveable(ownerId, detail.id, selectedSeasonNumber) { mutableStateOf<String?>(null) }
    var comparing by remember(ownerId, detail.id) { mutableStateOf<EpisodeBulkComparison?>(null) }
    var discardId by rememberSaveable(ownerId, detail.id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable(ownerId, detail.id) { mutableStateOf<String?>(null) }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        coroutine.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not save. Retry the same request." }
            finally { busy = false }
        }
    }
    val mainCount = detail.seasons.filter { it.seasonNumber != 0 }.sumOf { s -> s.episodes.count { it.watchEvents.isEmpty() } }
    val season = detail.seasons.find { it.seasonNumber == selectedSeasonNumber }
    val seasonCount = season?.episodes?.count { it.watchEvents.isEmpty() } ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (mainCount > 0) TextButton(enabled = !busy && pending.isEmpty(), onClick = { run { openingJson = prepare(null).json } }) {
            Text("Watched entire series before joining")
        }
        if (seasonCount > 0) TextButton(enabled = !busy && pending.isEmpty(), onClick = { run { openingJson = prepare(selectedSeasonNumber).json } }) {
            Text(if (selectedSeasonNumber == 0) "Watched specials before joining" else "Watched season $selectedSeasonNumber before joining")
        }
        pending.forEach { change ->
            Text("${change.count} pre-platform watches saved on this device. ${if (change.needsReview) "Review required." else "Waiting for sync."}")
            change.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Column {
                if (change.needsReview) {
                    TextButton(enabled = !busy, onClick = { run { comparing = compare(change.id) } }) { Text("Compare saved watches") }
                    TextButton(enabled = !busy, onClick = { discardId = change.id }) { Text("Discard request") }
                } else TextButton(enabled = !busy, onClick = { run { retry() } }) { Text("Retry sync") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    openingJson?.let { raw ->
        val opening = EpisodeBulkOpening(raw)
        AlertDialog(
            onDismissRequest = { if (!busy) openingJson = null },
            title = { Text("Watched before joining") },
            text = { Column {
                Text("Mark ${opening.count} episodes as watched (no date)?")
                if (opening.coarseCount > 0) Text("Also completes progress for ${opening.coarseCount} seasons without episode details.")
                if (opening.marksSeriesWatched) Text("Sets the series status to Watched.")
                Text("Existing watches, ratings, and reviews are preserved.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = { run { save(opening); openingJson = null } }) { Text(if (error == null) "Confirm" else "Retry") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { openingJson = null }) { Text("Cancel") } },
        )
    }
    comparing?.let { comparison ->
        AlertDialog(onDismissRequest = { if (!busy) comparing = null }, title = { Text("Compare saved watches") }, text = { Column {
            Text("Current series status: ${comparison.snapshot.title?.optString("status") ?: "removed"}.")
            Text("Applying this saved request will add ${comparison.replacement.count} remaining undated watches. Other watches are preserved.")
            if (comparison.replacement.coarseCount > 0) Text("Completes ${comparison.replacement.coarseCount} seasons without episode details.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !busy, onClick = { run { applyReviewed(comparison); comparing = null } }) { Text("Apply saved request") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { comparing = null }) { Text("Cancel") } })
    }
    discardId?.let { id ->
        AlertDialog(onDismissRequest = { if (!busy) discardId = null }, title = { Text("Discard saved watches?") },
            text = { Column { Text("Remove only this unaccepted request and its pending undated watches. Other history stays."); error?.let { Text(it) } } },
            confirmButton = { TextButton(enabled = !busy, onClick = { run { discard(id); discardId = null } }) { Text("Discard") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { discardId = null }) { Text("Cancel") } })
    }
}
