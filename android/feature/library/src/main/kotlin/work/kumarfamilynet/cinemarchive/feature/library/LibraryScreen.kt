package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import work.kumarfamilynet.cinemarchive.core.designsystem.ChoiceOption
import work.kumarfamilynet.cinemarchive.core.designsystem.ConnectedToggleGroup
import work.kumarfamilynet.cinemarchive.core.designsystem.PosterSurface
import work.kumarfamilynet.cinemarchive.core.designsystem.StatusBadge
import work.kumarfamilynet.cinemarchive.core.designsystem.tintForKey
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.LibraryTitle
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.data.LibraryRepository

data class LibraryUiState(val titles: List<LibraryTitle> = emptyList())

private enum class LibraryViewMode { GRID, LIST }

class LibraryViewModel(repository: LibraryRepository) : ViewModel() {
    val uiState = repository.observeLibrary()
        .map(::LibraryUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())
}

@Composable
fun LibraryRoute(
    repository: LibraryRepository,
    onOpenProfile: () -> Unit,
    onTitleClick: (String) -> Unit,
) {
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModelFactory(repository))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var search by rememberSaveable { mutableStateOf("") }
    var statusFilters by rememberSaveable { mutableStateOf(setOf<LibraryStatus>()) }
    var viewMode by rememberSaveable { mutableStateOf(LibraryViewMode.GRID) }

    val filtered = uiState.titles.filter { title ->
        (statusFilters.isEmpty() || title.status in statusFilters) &&
            (search.isBlank() || title.name.contains(search, ignoreCase = true))
    }

    LibraryScreen(
        titles = filtered,
        search = search,
        onSearchChange = { search = it },
        statusFilters = statusFilters,
        onToggleStatus = { s -> statusFilters = if (s in statusFilters) statusFilters - s else statusFilters + s },
        viewMode = viewMode,
        onToggleViewMode = { viewMode = if (viewMode == LibraryViewMode.GRID) LibraryViewMode.LIST else LibraryViewMode.GRID },
        onOpenProfile = onOpenProfile,
        onTitleClick = onTitleClick,
    )
}

@Composable
private fun LibraryScreen(
    titles: List<LibraryTitle>,
    search: String,
    onSearchChange: (String) -> Unit,
    statusFilters: Set<LibraryStatus>,
    onToggleStatus: (LibraryStatus) -> Unit,
    viewMode: LibraryViewMode,
    onToggleViewMode: () -> Unit,
    onOpenProfile: () -> Unit,
    onTitleClick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) {
            Text(
                "THE COLLECTION",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Surface(
                onClick = onOpenProfile,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("C", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Text(
            "Library",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 16.dp),
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.text.BasicTextField(
                value = search,
                onValueChange = onSearchChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.padding(start = 10.dp).weight(1f),
                decorationBox = { inner ->
                    if (search.isEmpty()) {
                        Text(
                            "Search your library…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                },
            )
            IconButton(onClick = onToggleViewMode, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (viewMode == LibraryViewMode.GRID) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList,
                    contentDescription = "Toggle view",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        ConnectedToggleGroup(
            options = listOf(
                ChoiceOption(LibraryStatus.WATCHED, "Watched"),
                ChoiceOption(LibraryStatus.WATCHING, "Watching"),
                ChoiceOption(LibraryStatus.WATCHLIST, "Watchlist"),
                ChoiceOption(LibraryStatus.DROPPED, "Dropped"),
            ),
            selected = statusFilters,
            onToggle = onToggleStatus,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )

        Text(
            "${titles.size} title${if (titles.size == 1) "" else "s"} on the bill",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )

        if (titles.isEmpty()) {
            EmptyLibrary(modifier = Modifier.fillMaxSize())
        } else if (viewMode == LibraryViewMode.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 100.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(titles, key = LibraryTitle::id) { title ->
                    LibraryGridCard(title, onClick = { onTitleClick(title.id) })
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 100.dp)) {
                items(titles, key = LibraryTitle::id) { title ->
                    LibraryListRow(title, onClick = { onTitleClick(title.id) })
                }
            }
        }
    }
}

@Composable
private fun LibraryGridCard(title: LibraryTitle, onClick: () -> Unit) {
    PosterSurface(tint = tintForKey(title.id), imageUrl = title.posterUrl, onClick = onClick) {
        Row(
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (title.type == MediaType.TV) "SERIES" else "FILM",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (title.hasScheduledOuting) {
                    Icon(
                        Icons.Filled.ConfirmationNumber,
                        contentDescription = "Tickets scheduled",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                StatusBadge(title.status)
            }
        }
        Column(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp)) {
            Text(
                title.name,
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFFF3EAD9),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(title.year?.toString(), title.director ?: title.network).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFF3EAD9).copy(alpha = 0.6f),
            )
            title.rating?.let { rating ->
                Text(
                    starGlyphs(rating),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun LibraryListRow(title: LibraryTitle, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PosterSurface(
            tint = tintForKey(title.id),
            imageUrl = title.posterUrl,
            modifier = Modifier.size(width = 44.dp, height = 60.dp),
            aspectRatio = 44f / 60f,
            cornerRadius = 10.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(title.year?.toString(), title.director ?: title.network).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (title.hasScheduledOuting) {
            Icon(
                Icons.Filled.ConfirmationNumber,
                contentDescription = "Tickets scheduled",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp).padding(end = 4.dp),
            )
        }
        StatusBadge(title.status)
    }
}

private fun starGlyphs(rating: Double): String {
    val n = rating.toInt().coerceIn(0, 5)
    return "★".repeat(n) + "☆".repeat(5 - n)
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Your library is ready", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Sign in and sync to bring your collection into the projection room.",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private class LibraryViewModelFactory(
    private val repository: LibraryRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(repository) as T
}
