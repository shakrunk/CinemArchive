package work.kumarfamilynet.cinemarchive.feature.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import work.kumarfamilynet.cinemarchive.core.model.LedgerCategoryCount
import work.kumarfamilynet.cinemarchive.core.model.RatingBaseline
import work.kumarfamilynet.cinemarchive.core.model.RatingObservation
import work.kumarfamilynet.cinemarchive.core.model.deriveRatingNormalization
import kotlin.math.abs

@Composable
internal fun ColumnScope.RatingsPanel(
    title: String,
    buckets: List<LedgerCategoryCount>,
    titles: List<RatingObservation>,
    scope: String,
    disclosure: PanelDisclosure,
    onTitleClick: (String) -> Unit,
) {
    var normalized by rememberSaveable { mutableStateOf(false) }
    PanelHeading(title, if (normalized) "Personal rating standards" else "How your ratings fall, five stars down to half a star")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !normalized, onClick = { normalized = false }, label = { Text("Distribution") })
        FilterChip(selected = normalized, onClick = { normalized = true }, label = { Text("Normalized") })
    }
    if (!normalized) {
        RatingDistributionContent(buckets)
        return
    }
    RatingStandardsContent(titles, scope, disclosure, onTitleClick)
}

@Composable
private fun ColumnScope.RatingStandardsContent(
    titles: List<RatingObservation>,
    scope: String,
    disclosure: PanelDisclosure,
    onTitleClick: (String) -> Unit,
) {
    var byMedia by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var explanation by rememberSaveable { mutableStateOf(false) }
    val stats = remember(titles, byMedia, scope) {
        deriveRatingNormalization(titles, if (byMedia) RatingBaseline.MEDIA else RatingBaseline.ALL, scope)
    }
    val matches = remember(stats, search) { stats.rows.filter { it.title.title.contains(search.trim(), ignoreCase = true) } }
    Text("Compare against", style = MaterialTheme.typography.labelMedium)
    // Stack choices to keep full labels and touch targets readable on narrow phones.
    Column {
        FilterChip(selected = !byMedia, onClick = { byMedia = false }, label = { Text("All rated titles") })
        FilterChip(selected = byMedia, onClick = { byMedia = true }, label = { Text("Same media type") })
    }
    stats.groups.filter { it.count > 0 }.forEach { group ->
        Text(
            "${group.label}: ${group.count} rated · mean %.2f · SD %.2f%s".format(
                group.mean, group.deviation, if (group.count < 5) " · small sample" else "",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    TextButton(onClick = { explanation = !explanation }) { Text(if (explanation) "Hide score explanation" else "How to read these scores") }
    if (explanation) {
        Text(
            "Z = (rating − baseline mean) / standard deviation. +1 is one standard deviation above this library’s usual rating; negative is below. " +
                "Rank is the percentage below this score, plus half of tied ratings; no bell curve is assumed. " +
                "Each title counts once, using its current 0–5 rating. Search and widget scope only filter results. " +
                "Baselines use all available titles in this library; shared libraries may be partial. " +
                "Small samples can shift sharply. A dash means fewer than two ratings or no variation. Original ratings stay unchanged.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (stats.rows.isEmpty()) {
        PanelEmpty("No rated titles in this scope yet.")
        return
    }
    OutlinedTextField(
        value = search, onValueChange = { search = it }, label = { Text("Find a rated title") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Text("Highest z-score first", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (matches.isEmpty()) Text("No matching rated titles.", style = MaterialTheme.typography.bodySmall)
    DisclosedList(matches.take(50), disclosure, "rated titles", previewCount = 3, spacing = 8.dp) { row ->
        val z = row.zScore?.let { if (abs(it) < 0.005) "0.00" else "%+.2f".format(it) } ?: "—"
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = "Open title") { onTitleClick(row.title.titleId) }
                .padding(vertical = 8.dp),
        ) {
            Text(row.title.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                "%.2f stars · Z %s · Rank %.1f%%".format(row.title.rating, z, row.percentile),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    if (matches.size > 50) Text("Showing 50 of ${matches.size}. Search to find any rated title.", style = MaterialTheme.typography.bodySmall)
}
