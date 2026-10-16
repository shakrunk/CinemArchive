package work.kumarfamilynet.cinemarchive.feature.discover

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** Keep frequent actions in reach; the sheet makes room for wrapping, readable choices. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DiscoverControls(
    search: String,
    onSearchChange: (String) -> Unit,
    mode: DiscoverMode,
    onModeChange: (DiscoverMode) -> Unit,
    typeFilter: TypeFilter,
    onTypeFilterChange: (TypeFilter) -> Unit,
    genreId: Int?,
    onGenreChange: (Int?) -> Unit,
    showTypeFilter: Boolean,
) {
    var choosingScope by remember { mutableStateOf(false) }
    var filtering by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val genreName = discoverGenres(typeFilter).firstOrNull { it.id == genreId }?.name.takeIf { search.isBlank() }
    val activeFilters = (if (typeFilter != TypeFilter.ALL) 1 else 0) + (if (genreName != null) 1 else 0)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = search, onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth().testTag("discover-search"),
            shape = MaterialTheme.shapes.extraLarge,
            singleLine = true,
            placeholder = { Text(when (mode) {
                DiscoverMode.TITLES -> "Search movies & TV"
                DiscoverMode.PEOPLE -> "Search people"
                DiscoverMode.STUDIOS -> "Search studios"
            }) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (search.isNotEmpty()) { {
                IconButton(onClick = { onSearchChange("") }) { Icon(Icons.Default.Close, "Clear search") }
            } } else null,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                InputChip(
                    selected = mode != DiscoverMode.TITLES,
                    onClick = { focus.clearFocus(); choosingScope = true },
                    label = { Text(mode.label) },
                    trailingIcon = { Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp)) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Search in ${mode.label}. Change search scope" },
                )
                DropdownMenu(expanded = choosingScope, onDismissRequest = { choosingScope = false }) {
                    DiscoverMode.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            trailingIcon = { if (mode == option) Icon(Icons.Default.Check, "Selected") },
                            onClick = { choosingScope = false; onModeChange(option) },
                        )
                    }
                }
            }
            if (showTypeFilter) InputChip(
                selected = activeFilters > 0,
                onClick = { focus.clearFocus(); filtering = true },
                label = { Text(if (activeFilters == 0) "Filters" else listOfNotNull(
                    typeFilter.label.takeIf { typeFilter != TypeFilter.ALL }, genreName,
                ).joinToString(" · ")) },
                leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(18.dp)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("discover-filters"),
            )
        }
    }
    if (filtering) {
        var draftType by rememberSaveable { mutableStateOf(typeFilter) }
        var draftGenre by rememberSaveable { mutableStateOf(genreId) }
        ModalBottomSheet(
            onDismissRequest = { filtering = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text("Discover filters", style = MaterialTheme.typography.headlineSmall)
                Text("Media type", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypeFilter.entries.forEach { option ->
                        FilterChip(selected = draftType == option,
                            onClick = { if (draftType != option) { draftType = option; draftGenre = null } },
                            label = { Text(if (option == TypeFilter.ALL) "Movies & TV" else option.label) },
                            modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                if (mode == DiscoverMode.TITLES && search.isBlank()) {
                    Text("Genre", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = draftGenre == null, onClick = { draftGenre = null },
                            label = { Text("All genres") }, modifier = Modifier.heightIn(min = 48.dp))
                        discoverGenres(draftType).forEach { genre ->
                            FilterChip(selected = draftGenre == genre.id, onClick = { draftGenre = genre.id },
                                label = { Text(genre.name) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                } else if (mode == DiscoverMode.TITLES) {
                    Text("Clear your search to browse by genre.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { draftType = TypeFilter.ALL; draftGenre = null },
                        enabled = draftType != TypeFilter.ALL || draftGenre != null,
                    ) { Text("Reset") }
                    Button(onClick = {
                        if (draftType != typeFilter) onTypeFilterChange(draftType)
                        // Changing media type clears the old genre in the view model.
                        val currentGenre = if (draftType != typeFilter) null else genreId
                        if (mode == DiscoverMode.TITLES && search.isBlank() && draftGenre != currentGenre) onGenreChange(draftGenre)
                        filtering = false
                    }, modifier = Modifier.weight(1f)) { Text("Show results") }
                }
            }
        }
    }
}
