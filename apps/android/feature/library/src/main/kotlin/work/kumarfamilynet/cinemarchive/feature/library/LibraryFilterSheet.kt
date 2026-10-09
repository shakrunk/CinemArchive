package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import work.kumarfamilynet.cinemarchive.core.model.LibraryFilterChoices
import work.kumarfamilynet.cinemarchive.core.model.LibraryFilters
import work.kumarfamilynet.cinemarchive.core.model.LibraryGrouping
import work.kumarfamilynet.cinemarchive.core.model.LibrarySortDirection
import work.kumarfamilynet.cinemarchive.core.model.LibrarySortOrder
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.LibraryPerson
import work.kumarfamilynet.cinemarchive.core.model.MediaType

internal val LibraryFiltersSaver = mapSaver(
    save = { filters: LibraryFilters -> mapOf(
        "search" to filters.search, "type" to filters.type?.name.orEmpty(),
        "statuses" to filters.statuses.map { it.name }, "genres" to filters.genres.toList(),
        "tags" to filters.tags.toList(), "networks" to filters.networks.toList(),
        "decades" to filters.decades.toList(), "languages" to filters.languages.toList(),
        "studio" to filters.studio.orEmpty(), "rating" to filters.minRating,
        "sort" to filters.sortOrder.name, "direction" to filters.sortDirection.name, "group" to filters.grouping.name,
        "personId" to (filters.person?.tmdbPersonId ?: 0), "personName" to filters.person?.name.orEmpty(),
    ) },
    restore = { values ->
        fun strings(key: String) = (values[key] as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        LibraryFilters(
            search = values["search"] as? String ?: "",
            type = MediaType.entries.find { it.name == values["type"] },
            statuses = LibraryStatus.entries.filter { it.name in strings("statuses") }.toSet(),
            genres = strings("genres"), tags = strings("tags"), networks = strings("networks"),
            decades = strings("decades"), languages = strings("languages"),
            studio = (values["studio"] as? String)?.takeIf { it.isNotEmpty() },
            minRating = values["rating"] as? Double ?: 0.0,
            sortOrder = LibrarySortOrder.entries.find { it.name == values["sort"] } ?: LibrarySortOrder.LAST_INTERACTION,
            sortDirection = LibrarySortDirection.entries.find { it.name == values["direction"] } ?: LibrarySortDirection.DESCENDING,
            grouping = LibraryGrouping.entries.find { it.name == values["group"] } ?: LibraryGrouping.NONE,
            person = (values["personId"] as? Int)?.takeIf { it > 0 }?.let { LibraryPerson(it, values["personName"] as? String ?: "") },
        )
    },
)

/** Validate the owner on restoration too: rememberSaveable inputs alone do not do this. */
fun accountLibraryFiltersSaver(accountKey: String): Saver<LibraryFilters, Any> = mapSaver(
    save = { filters -> mapOf("account" to accountKey, "filters" to with(LibraryFiltersSaver) { save(filters) }) },
    restore = { values ->
        if (values["account"] == accountKey) values["filters"]?.let(LibraryFiltersSaver::restore) ?: LibraryFilters()
        else LibraryFilters()
    },
)

@Composable
fun rememberAccountLibraryFilters(accountKey: String): MutableState<LibraryFilters> {
    val saver = remember(accountKey) { accountLibraryFiltersSaver(accountKey) }
    return rememberSaveable(accountKey, stateSaver = saver) { mutableStateOf(LibraryFilters()) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryFilterSheet(
    filters: LibraryFilters,
    choices: LibraryFilterChoices,
    onChange: (LibraryFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    var showPeople by remember { mutableStateOf(false) }
    if (showPeople) LibraryPersonPicker(choices.people, onSelect = {
        onChange(filters.copy(person = it)); showPeople = false
    }, onDismiss = { showPeople = false })
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Filter & sort", style = MaterialTheme.typography.titleMedium)
                Row {
                    TextButton(onClick = { onChange(LibraryFilters()) }, enabled = filters != LibraryFilters()) { Text("Reset") }
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Row {
                    TextButton(onClick = { showPeople = true }, enabled = choices.people.isNotEmpty()) {
                        Text(filters.person?.let { "Featuring ${it.name}" } ?: "Choose person")
                    }
                    if (filters.person != null) TextButton(onClick = { onChange(filters.copy(person = null)) }) { Text("Clear person") }
                }
                FilterChoices("Type", listOf("All", "Movies", "TV"), setOf(when (filters.type) {
                    null -> "All"; MediaType.MOVIE -> "Movies"; MediaType.TV -> "TV"
                })) { onChange(filters.copy(type = when (it) { "Movies" -> MediaType.MOVIE; "TV" -> MediaType.TV; else -> null })) }
                FilterChoices("Status", listOf("Watched", "Watching", "Watchlist", "Dropped"), filters.statuses.map { it.label() }.toSet()) {
                    val status = LibraryStatus.entries.first { status -> status.label() == it }
                    onChange(filters.copy(statuses = filters.statuses.toggle(status)))
                }
                val sorts = linkedMapOf("Smart" to LibrarySortOrder.LAST_INTERACTION, "Date added" to LibrarySortOrder.ADDED_AT,
                    "Title" to LibrarySortOrder.TITLE, "Year" to LibrarySortOrder.YEAR_NEWEST,
                    "Rating" to LibrarySortOrder.RATING_HIGHEST, "Director" to LibrarySortOrder.DIRECTOR)
                FilterChoices("Sort by", sorts.keys.toList(), setOf(sorts.entries.first { it.value == filters.sortOrder }.key)) {
                    onChange(filters.copy(sortOrder = sorts.getValue(it)))
                }
                FilterChoices("Direction", listOf("Ascending", "Descending"), setOf(if (filters.sortDirection == LibrarySortDirection.ASCENDING) "Ascending" else "Descending")) {
                    onChange(filters.copy(sortDirection = if (it == "Ascending") LibrarySortDirection.ASCENDING else LibrarySortDirection.DESCENDING))
                }
                FilterChoices("Group by", listOf("None", "Status", "Franchise"), setOf(filters.grouping.name.lowercase().replaceFirstChar(Char::uppercase))) {
                    onChange(filters.copy(grouping = LibraryGrouping.valueOf(it.uppercase())))
                }
                Text(if (filters.minRating == 0.0) "Minimum rating: Any" else "Minimum rating: ${filters.minRating} stars", style = MaterialTheme.typography.labelMedium)
                Slider(value = filters.minRating.toFloat(), onValueChange = { onChange(filters.copy(minRating = it.toDouble())) },
                    valueRange = 0f..5f, steps = 9, modifier = Modifier.semantics { contentDescription = "Minimum rating" })
                FilterChoices("Genres", choices.genres, filters.genres) { onChange(filters.copy(genres = filters.genres.toggle(it))) }
                FilterChoices("Decades", choices.decades, filters.decades) { onChange(filters.copy(decades = filters.decades.toggle(it))) }
                FilterChoices("Networks", choices.networks, filters.networks) { onChange(filters.copy(networks = filters.networks.toggle(it))) }
                FilterChoices("Languages", choices.languages, filters.languages) { onChange(filters.copy(languages = filters.languages.toggle(it))) }
                FilterChoices("Tags", choices.tags, filters.tags) { onChange(filters.copy(tags = filters.tags.toggle(it))) }
                FilterChoices("Studios", choices.studios, setOfNotNull(filters.studio)) { onChange(filters.copy(studio = if (filters.studio == it) null else it)) }
            }
        }
    }
}

@Composable
private fun FilterChoices(label: String, options: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
    if (options.isEmpty()) return
    Column(Modifier.padding(top = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option -> FilterChip(selected = option in selected, onClick = { onToggle(option) },
                label = { Text(option) }, modifier = Modifier.semantics { contentDescription = "$label: $option" }) }
        }
    }
}

private fun LibraryStatus.label() = name.lowercase().replaceFirstChar(Char::uppercase)
private fun <T> Set<T>.toggle(value: T) = if (value in this) this - value else this + value
