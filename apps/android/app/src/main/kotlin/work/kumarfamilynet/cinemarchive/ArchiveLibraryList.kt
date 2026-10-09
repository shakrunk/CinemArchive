package work.kumarfamilynet.cinemarchive

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import work.kumarfamilynet.cinemarchive.core.model.LibraryFilters
import work.kumarfamilynet.cinemarchive.core.model.filterLibrary
import work.kumarfamilynet.cinemarchive.core.model.groupLibrary
import work.kumarfamilynet.cinemarchive.core.model.libraryFilterChoices
import work.kumarfamilynet.cinemarchive.data.SharedLibraryTitle
import work.kumarfamilynet.cinemarchive.data.toLibraryFilterTitle
import work.kumarfamilynet.cinemarchive.feature.library.LibraryFilterSheet

/** Friend and anonymous viewers share read-only controls over their already-scoped graph. */
@Composable
internal fun ArchiveLibraryList(
    titles: List<SharedLibraryTitle>,
    filters: LibraryFilters,
    onFiltersChange: (LibraryFilters) -> Unit,
    emptyMessage: String,
    onSelect: (String) -> Unit,
) {
    val projected = remember(titles) { titles.map { it.toLibraryFilterTitle() } }
    val choices = remember(projected) { libraryFilterChoices(projected) }
    val shown = remember(projected, filters) { filterLibrary(projected, filters) }
    val groups = remember(shown, filters.grouping) { groupLibrary(shown, filters.grouping) }
    var showFilters by remember { mutableStateOf(false) }
    Column {
        OutlinedTextField(filters.search, { onFiltersChange(filters.copy(search = it)) }, label = { Text("Search library") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showFilters = true }) {
                Text(if (filters.activeFilterCount == 0) "Filter & sort" else "Filter & sort (${filters.activeFilterCount})")
            }
            if (filters != LibraryFilters()) TextButton(onClick = { onFiltersChange(LibraryFilters()) }) { Text("Reset filters") }
        }
        Text("${shown.size} of ${titles.size} titles", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (shown.isEmpty()) item { Text(if (titles.isEmpty()) emptyMessage else "No matching titles. Reset filters to see the shared archive.") }
            groups.forEach { group ->
                group.label?.let { label -> item(key = "group:${group.key}") { Text(label, style = MaterialTheme.typography.titleMedium) } }
                items(group.titles, key = { it.id }) { title ->
                    Surface(onClick = { onSelect(title.id) }, shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(title.posterUrl, contentDescription = null, modifier = Modifier.size(48.dp, 72.dp))
                            Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(title.name, style = MaterialTheme.typography.titleMedium)
                                Text(listOfNotNull(title.year?.toString(), title.type.name, title.rating?.let { "$it / 5" }).joinToString(" · "))
                                Text(title.status.name.lowercase(), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
    if (showFilters) LibraryFilterSheet(filters, choices, onFiltersChange, onDismiss = { showFilters = false })
}
