package work.kumarfamilynet.cinemarchive.feature.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.ChoiceOption
import work.kumarfamilynet.cinemarchive.core.designsystem.PosterSurface
import work.kumarfamilynet.cinemarchive.core.designsystem.SegmentedGroup
import work.kumarfamilynet.cinemarchive.core.designsystem.rememberCollapseOnScroll
import work.kumarfamilynet.cinemarchive.core.designsystem.tintForKey
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.TrendingTitle
import work.kumarfamilynet.cinemarchive.data.DiscoverRepository

private enum class TypeFilter(val label: String) { ALL("All"), MOVIE("Movies"), TV("TV") }

data class DiscoverUiState(
    val titles: List<TrendingTitle> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

/** Loads this week's trending movies/TV from [DiscoverRepository]. [retry] (surfaced on
 *  load failure) and [refresh] (pull-to-refresh) both re-fetch — they only differ in which
 *  loading flag they flip, since [retry] fires from the full-screen error state (nothing to
 *  show underneath a pull indicator yet) while [refresh] fires over an already-visible list. */
class DiscoverViewModel(private val repository: DiscoverRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState

    init {
        fetch(showFullScreenLoading = true)
    }

    fun retry() = fetch(showFullScreenLoading = true)

    fun refresh() = fetch(showFullScreenLoading = false)

    private fun fetch(showFullScreenLoading: Boolean) {
        viewModelScope.launch {
            _uiState.update {
                if (showFullScreenLoading) it.copy(isLoading = true, error = null) else it.copy(isRefreshing = true, error = null)
            }
            runCatching { repository.fetchTrending() }
                .onSuccess { titles -> _uiState.update { it.copy(titles = titles, isLoading = false, isRefreshing = false) } }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = e.message ?: "Couldn't load trending titles") }
                }
        }
    }
}

private class DiscoverViewModelFactory(
    private val repository: DiscoverRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = DiscoverViewModel(repository) as T
}

@Composable
fun DiscoverRoute(repository: DiscoverRepository, onFabExpandedChange: (Boolean) -> Unit = {}) {
    val viewModel: DiscoverViewModel = viewModel(factory = DiscoverViewModelFactory(repository))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val addedIds by DiscoverSampleStore.addedIds.collectAsState()
    var search by rememberSaveable { mutableStateOf("") }
    var typeFilter by rememberSaveable { mutableStateOf(TypeFilter.ALL) }
    var preview by remember { mutableStateOf<TrendingTitle?>(null) }

    val filtered = uiState.titles.filter { title ->
        (typeFilter == TypeFilter.ALL || (typeFilter == TypeFilter.MOVIE) == (title.type == MediaType.MOVIE)) &&
            (search.isBlank() || title.title.contains(search, ignoreCase = true))
    }

    DiscoverScreen(
        search = search,
        onSearchChange = { search = it },
        typeFilter = typeFilter,
        onTypeFilterChange = { typeFilter = it },
        titles = filtered,
        isLoading = uiState.isLoading,
        isRefreshing = uiState.isRefreshing,
        error = uiState.error,
        onRetry = viewModel::retry,
        onRefresh = viewModel::refresh,
        addedIds = addedIds,
        onOpenPreview = { preview = it },
        onAdd = DiscoverSampleStore::add,
        onFabExpandedChange = onFabExpandedChange,
    )

    preview?.let { title ->
        TrendingTitleDialog(
            title = title,
            isAdded = title.tmdbId.toString() in addedIds,
            onAdd = { DiscoverSampleStore.add(title.tmdbId.toString()) },
            onDismiss = { preview = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverScreen(
    search: String,
    onSearchChange: (String) -> Unit,
    typeFilter: TypeFilter,
    onTypeFilterChange: (TypeFilter) -> Unit,
    titles: List<TrendingTitle>,
    isLoading: Boolean,
    isRefreshing: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    addedIds: Set<String>,
    onOpenPreview: (TrendingTitle) -> Unit,
    onAdd: (String) -> Unit,
    onFabExpandedChange: (Boolean) -> Unit = {},
) {
    val gridState = rememberLazyGridState()
    val collapsed = rememberCollapseOnScroll(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
    androidx.compose.runtime.LaunchedEffect(collapsed) { onFabExpandedChange(!collapsed) }

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

        AnimatedVisibility(
            visible = !collapsed,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
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
            }
        }

        when {
            isLoading -> Box(modifier = Modifier.fillMaxSize().padding(top = 48.dp), contentAlignment = Alignment.TopCenter) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            error != null -> Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Couldn't load trending titles",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
                Button(onClick = onRetry) { Text("Retry") }
            }
            else -> PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        "${titles.size} titles",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = gridState,
                        contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 100.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(titles, key = TrendingTitle::tmdbId) { title ->
                            DiscoverCard(
                                title = title,
                                isAdded = title.tmdbId.toString() in addedIds,
                                onOpen = { onOpenPreview(title) },
                                onAdd = { onAdd(title.tmdbId.toString()) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverCard(title: TrendingTitle, isAdded: Boolean, onOpen: () -> Unit, onAdd: () -> Unit) {
    PosterSurface(tint = tintForKey(title.tmdbId.toString()), imageUrl = title.posterUrl, onClick = onOpen) {
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
                "${title.year ?: "—"}" + if (title.type == MediaType.TV) " · TV" else "",
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
private fun TrendingTitleDialog(title: TrendingTitle, isAdded: Boolean, onAdd: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title.title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(
                    "${title.year ?: "—"}" + if (title.type == MediaType.TV) " · TV Series" else " · Movie",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    title.synopsis ?: "No synopsis available.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
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
