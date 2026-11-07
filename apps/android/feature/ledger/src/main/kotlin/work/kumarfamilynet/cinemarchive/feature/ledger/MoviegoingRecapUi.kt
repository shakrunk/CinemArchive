package work.kumarfamilynet.cinemarchive.feature.ledger

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import work.kumarfamilynet.cinemarchive.core.model.MoviegoingRecap
import work.kumarfamilynet.cinemarchive.core.model.MoviegoingRecapRules
import work.kumarfamilynet.cinemarchive.core.model.MoviegoingRecapText
import work.kumarfamilynet.cinemarchive.core.model.MoviegoingTrip
import java.time.LocalDate

/**
 * Whether the yearly recap is offered. True only for the signed-in owner's own Ledger: the card
 * prints total spend and format, which come from the owner-private outing rows, so shared and
 * friend views leave this at its default (docs/android-contracts/moviegoing-recap.md).
 */
val LocalMoviegoingRecap = compositionLocalOf { false }

/** The panel's entry to the yearly recap; hidden for other viewers or when there are no trips. */
@Composable
internal fun MoviegoingRecapEntry(trips: List<MoviegoingTrip>) {
    if (!LocalMoviegoingRecap.current) return
    val today = remember { LocalDate.now() }
    val years = remember(trips) { MoviegoingRecapRules.years(trips) }
    val openYear = remember(trips, today) { MoviegoingRecapRules.defaultYear(trips, today) } ?: return
    var open by rememberSaveable { mutableStateOf(false) }

    TextButton(onClick = { open = true }, modifier = Modifier.padding(top = 6.dp)) { Text("Your year at the movies · $openYear") }
    if (open) {
        RecapDialog(trips = trips, years = years, initialYear = openYear, onDismiss = { open = false })
    }
}

@Composable
private fun RecapDialog(trips: List<MoviegoingTrip>, years: List<Int>, initialYear: Int, onDismiss: () -> Unit) {
    var year by rememberSaveable { mutableStateOf(initialYear) }
    val recap = remember(trips, year) { MoviegoingRecapRules.recap(trips, year) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your year at the movies") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (years.size > 1) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        years.forEach { option ->
                            FilterChip(selected = year == option, onClick = { year = option }, label = { Text(option.toString()) })
                        }
                    }
                }
                if (recap != null) RecapCard(recap)
            }
        },
        confirmButton = {
            TextButton(enabled = recap != null, onClick = { recap?.let { shareRecap(context, it) } }) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The on-screen card. [shareRecap] draws the same figures, from the same [MoviegoingRecapText],
 *  into an image. */
@Composable
private fun RecapCard(recap: MoviegoingRecap) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Overline("${recap.year} · at the movies")
        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoFigure(recap.tripCount.toString(), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                if (recap.tripCount == 1) "trip" else "trips",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        MoviegoingRecapText.fields(recap).forEach { field ->
            Column {
                Overline(field.label)
                Text(field.value, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
