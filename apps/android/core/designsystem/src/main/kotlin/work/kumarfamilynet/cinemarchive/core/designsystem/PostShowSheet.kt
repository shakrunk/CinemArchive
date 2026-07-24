package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalMovies
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * "How was it?" — the post-show follow-up sheet (web plan §4.4), scoped to what Android v1 can
 * do without a live backend: rate, note, and the "Didn't make it" revert. `Recommend to
 * friends` is deferred (docs/superpowers/plans/2026-07-21-android-cinema-outings.md §8 — the
 * friends stack doesn't exist on Android yet). Stateless and callback-driven so it can be
 * triggered from either the title detail banner or an Up Next "Fresh from the lobby" card
 * without those feature modules depending on each other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostShowSheet(
    titleName: String,
    venue: String?,
    companions: List<String>,
    initialRating: Double,
    initialNotes: String,
    onRate: (Double) -> Unit,
    onSaveNotes: (String) -> Unit,
    onDidntMakeIt: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = androidx.compose.material3.rememberModalBottomSheetState(),
) {
    var notes by rememberSaveable(titleName) { mutableStateOf(initialNotes) }
    // Mirrors the rating locally rather than trusting [initialRating] to update in time: the
    // parent's data round-trips through Room/a Flow after each drag frame, which lags well
    // behind DraggableStarRating's own "gesture ended, snap to the prop" behavior — without
    // this, releasing the drag would visually snap the stars back to empty.
    var rating by rememberSaveable(titleName) { mutableStateOf(initialRating) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp, 0.dp, 20.dp, 28.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(Icons.Filled.LocalMovies, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Text(
                    "$titleName just let out",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            val subtitle = listOfNotNull(venue, companions.takeIf { it.isNotEmpty() }?.let { "with ${it.joinToString(" & ")}" })
                .joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
                )
            } else {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
            }

            Text("HOW WAS IT?", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            DraggableStarRating(
                rating = rating,
                onRatingChange = { rating = it; onRate(it) },
                modifier = Modifier.padding(bottom = 20.dp),
            )

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Quick note") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDidntMakeIt) { Text("Didn't make it") }
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    onSaveNotes(notes)
                    onDismiss()
                }) { Text("Done") }
            }
        }
    }
}
