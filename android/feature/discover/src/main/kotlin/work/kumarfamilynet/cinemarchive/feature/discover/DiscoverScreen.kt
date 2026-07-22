package work.kumarfamilynet.cinemarchive.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import work.kumarfamilynet.cinemarchive.core.designsystem.ChoiceOption
import work.kumarfamilynet.cinemarchive.core.designsystem.PosterSurface
import work.kumarfamilynet.cinemarchive.core.designsystem.SegmentedGroup
import work.kumarfamilynet.cinemarchive.core.model.MediaType

private enum class TypeFilter(val label: String) { ALL("All"), MOVIE("Movies"), TV("TV") }

@Composable
fun DiscoverRoute() {
    val addedIds by DiscoverSampleStore.addedIds.collectAsState()
    var search by rememberSaveable { mutableStateOf("") }
    var typeFilter by rememberSaveable { mutableStateOf(TypeFilter.ALL) }
    var preview by remember { mutableStateOf<SampleTitle?>(null) }

    val filtered = SAMPLE_CATALOG.filter { title ->
        (typeFilter == TypeFilter.ALL || (typeFilter == TypeFilter.MOVIE) == (title.type == MediaType.MOVIE)) &&
            (search.isBlank() || title.title.contains(search, ignoreCase = true))
    }

    DiscoverScreen(
        search = search,
        onSearchChange = { search = it },
        typeFilter = typeFilter,
        onTypeFilterChange = { typeFilter = it },
        titles = filtered,
        addedIds = addedIds,
        onOpenPreview = { preview = it },
        onAdd = DiscoverSampleStore::add,
    )

    preview?.let { title ->
        SampleTitleDialog(
            title = title,
            isAdded = title.id in addedIds,
            onAdd = { DiscoverSampleStore.add(title.id) },
            onDismiss = { preview = null },
        )
    }
}

@Composable
private fun DiscoverScreen(
    search: String,
    onSearchChange: (String) -> Unit,
    typeFilter: TypeFilter,
    onTypeFilterChange: (TypeFilter) -> Unit,
    titles: List<SampleTitle>,
    addedIds: Set<String>,
    onOpenPreview: (SampleTitle) -> Unit,
    onAdd: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            "EXPLORE THE REEL",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            "Discover",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 16.dp),
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.text.BasicTextField(
                value = search,
                onValueChange = onSearchChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.padding(start = 10.dp).fillMaxWidth(),
                decorationBox = { inner ->
                    if (search.isEmpty()) {
                        Text(
                            "Search movies & TV…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                },
            )
        }

        SegmentedGroup(
            options = listOf(
                ChoiceOption(TypeFilter.ALL, "All"),
                ChoiceOption(TypeFilter.MOVIE, "Movies"),
                ChoiceOption(TypeFilter.TV, "TV"),
            ),
            selected = typeFilter,
            onSelect = onTypeFilterChange,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        Text(
            "${titles.size} titles",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 100.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(titles, key = SampleTitle::id) { title ->
                DiscoverCard(
                    title = title,
                    isAdded = title.id in addedIds,
                    onOpen = { onOpenPreview(title) },
                    onAdd = { onAdd(title.id) },
                )
            }
        }
    }
}

@Composable
private fun DiscoverCard(title: SampleTitle, isAdded: Boolean, onOpen: () -> Unit, onAdd: () -> Unit) {
    PosterSurface(tint = title.tint, onClick = onOpen) {
        if (isAdded) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Owned",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Column(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp)) {
            Text(
                title.title,
                style = MaterialTheme.typography.titleSmall,
                color = androidx.compose.ui.graphics.Color(0xFFF3EAD9),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${title.year}" + if (title.type == MediaType.TV) " · TV" else "",
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color(0xFFF3EAD9).copy(alpha = 0.65f),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (!isAdded) {
                Surface(
                    onClick = onAdd,
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "+ Add",
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SampleTitleDialog(title: SampleTitle, isAdded: Boolean, onAdd: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title.title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(
                    "${title.year}" + if (title.type == MediaType.TV) " · TV Series" else " · Movie",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(title.synopsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            if (isAdded) {
                TextButton(onClick = onDismiss, enabled = false) { Text("Added") }
            } else {
                Button(onClick = { onAdd(); onDismiss() }) { Text("Add to library") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
