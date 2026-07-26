package work.kumarfamilynet.cinemarchive.feature.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import work.kumarfamilynet.cinemarchive.core.model.LedgerCategoryCount

/**
 * The Ensemble as a poster billing block.
 *
 * This is the one panel in the set that deliberately isn't a chart. A cast tally is a ranked
 * list of *names*, and the interesting content is the names themselves — a bar next to each one
 * encodes a count the number already states, and adds nothing you'd read. What a billing block
 * does instead is put the ranking into the typography: top billing is set large and alone,
 * the supporting cast follows in smaller centred caps. That's a hierarchy you read the way you
 * read a poster, which is exactly the object this data describes.
 *
 * Counts stay explicit rather than being implied by size, so the ordering never has to be
 * inferred from type weight. Entries arrive ranked and capped to the widget's effective `topN`
 * (default 5) — only cast billed below order 5 on a title count as "leading" in the first place
 * (ledger.md §2).
 */
@Composable
internal fun ColumnScope.EnsemblePanel(
    title: String,
    entries: List<LedgerCategoryCount>,
    disclosure: PanelDisclosure,
) {
    PanelHeading(title, "The faces that turn up most across your library")
    if (entries.isEmpty()) {
        PanelEmpty("No cast data logged yet.")
        return
    }

    val lead = entries.first()
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Overline("top billing")
        Text(
            lead.label.uppercase(),
            style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 1.5.sp),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 6.dp)
                .clearAndSetSemantics {
                    contentDescription = "Top billing: ${lead.label}, in ${lead.count} titles"
                },
        )
        Text(
            if (lead.count == 1) "in 1 title" else "in ${lead.count} titles",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    val supporting = entries.drop(1)
    if (supporting.isEmpty()) return

    // Top billing is the summary; the supporting cast is the billing block's small print, and
    // reads as such whether it's showing or not.
    PanelDetail(disclosure, "Show the supporting ${supporting.size}", spacing = 9.dp) {
        Box(
            modifier = Modifier
                .padding(top = 16.dp, bottom = 12.dp)
                .width(46.dp)
                .height(1.dp)
                .align(Alignment.CenterHorizontally)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        supporting.forEach { entry ->
            Text(
                "${entry.label.uppercase()}   ${entry.count}",
                style = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 0.9.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics {
                        contentDescription = "${entry.label}, in ${entry.count} titles"
                    },
            )
        }
    }
}
