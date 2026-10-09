package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import work.kumarfamilynet.cinemarchive.core.model.TitleDetail
import work.kumarfamilynet.cinemarchive.core.model.catalogDetailRows

/** Stored catalog information remains available offline and exposes no editing controls. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CatalogDetailsSection(detail: TitleDetail, showTags: Boolean = true) {
    val rows = detail.catalogDetailRows()
    val tags = if (showTags) detail.tags.filter { it.isNotBlank() } else emptyList()
    if (rows.isEmpty() && tags.isEmpty()) return
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag("catalog-details")) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (rows.isNotEmpty()) {
                Text("Details", style = MaterialTheme.typography.titleMedium)
                rows.forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(row.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(0.35f))
                        Text(row.value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.65f))
                    }
                }
            }
            if (tags.isNotEmpty()) {
                Text("Tags", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { tag ->
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(tag, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
            }
        }
    }
}
