package work.kumarfamilynet.cinemarchive.feature.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.viewmodel.compose.viewModel
import work.kumarfamilynet.cinemarchive.core.designsystem.ChoiceOption
import work.kumarfamilynet.cinemarchive.core.designsystem.ContentReadingMaxWidth
import work.kumarfamilynet.cinemarchive.core.designsystem.ExpressivePullToRefresh
import work.kumarfamilynet.cinemarchive.core.designsystem.PosterSurface
import work.kumarfamilynet.cinemarchive.core.designsystem.posterGridCornerRadius
import work.kumarfamilynet.cinemarchive.core.designsystem.posterMinTileWidth
import work.kumarfamilynet.cinemarchive.core.designsystem.ProfileAvatarButton
import work.kumarfamilynet.cinemarchive.core.designsystem.SegmentedGroup
import work.kumarfamilynet.cinemarchive.core.designsystem.pinchToResizeGrid
import work.kumarfamilynet.cinemarchive.core.designsystem.rememberCollapseOnScroll
import work.kumarfamilynet.cinemarchive.core.designsystem.tintForKey
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.TrendingTitle
import work.kumarfamilynet.cinemarchive.data.DiscoverRepository
import work.kumarfamilynet.cinemarchive.data.LibraryRepository

private class DiscoverViewModelFactory(
    private val repository: DiscoverRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = DiscoverViewModel(repository) as T
}

@Composable
fun DiscoverRoute(
    repository: DiscoverRepository,
    libraryRepository: LibraryRepository,
    gridColumns: Int,
    onGridColumnsChange: (Int) -> Unit,
    onOpenProfile: () -> Unit = {},
    profileInitial: String = "C",
    onFabExpandedChange: (Boolean) -> Unit = {},
    onTitleClick: (String) -> Unit = {},
    onAddTitle: (TrendingTitle) -> Unit = {},
) {
    val viewModel: DiscoverViewModel = viewModel(factory = DiscoverViewModelFactory(repository))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The same media identity drives ownership badges, preview actions and opening detail.
    val idsByTmdbKey by libraryRepository.observeLibraryTitleIdsByTmdbKey()
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    var search by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(search) { viewModel.onQueryChange(search) }
    var typeFilter by rememberSaveable { mutableStateOf(TypeFilter.ALL) }
    var preview by remember { mutableStateOf<TrendingTitle?>(null) }

    val filtered = filterDiscoverTitles(uiState.titles, typeFilter)

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
        addedIds = idsByTmdbKey.keys,
        onOpenTitle = { title ->
            val realId = idsByTmdbKey[title.mediaIdentity]
            if (realId != null) onTitleClick(realId) else preview = title
        },
        onAdd = onAddTitle,
        gridColumns = gridColumns,
        onGridColumnsChange = onGridColumnsChange,
        onOpenProfile = onOpenProfile,
        profileInitial = profileInitial,
        onFabExpandedChange = onFabExpandedChange,
    )

    preview?.let { title ->
        TrendingTitlePreviewSheet(
            title = title,
            isAdded = title.mediaIdentity in idsByTmdbKey,
            onAdd = { preview = null; onAddTitle(title) },
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
    addedIds: Set<Pair<Int, MediaType>>,
    onOpenTitle: (TrendingTitle) -> Unit,
    onAdd: (TrendingTitle) -> Unit,
    gridColumns: Int,
    onGridColumnsChange: (Int) -> Unit,
    onOpenProfile: () -> Unit = {},
    profileInitial: String = "C",
    onFabExpandedChange: (Boolean) -> Unit = {},
) {
    val gridState = rememberLazyGridState()
    val collapsed = rememberCollapseOnScroll(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
    androidx.compose.runtime.LaunchedEffect(collapsed) { onFabExpandedChange(!collapsed) }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "EXPLORE THE REEL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("Discover", style = MaterialTheme.typography.headlineLarge)
            }
            ProfileAvatarButton(initial = profileInitial, onClick = onOpenProfile)
        }

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
                        .widthIn(max = ContentReadingMaxWidth)
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
                    if (search.isBlank()) "Couldn't load trending titles" else "Couldn't search titles",
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
            else -> ExpressivePullToRefresh(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        if (search.isNotBlank() && titles.isEmpty()) "No titles found for “${search.trim()}”" else "${titles.size} titles",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )

                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = posterMinTileWidth(gridColumns)),
                        state = gridState,
                        contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 100.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .pinchToResizeGrid(gridColumns, onGridColumnsChange),
                    ) {
                        items(titles, key = TrendingTitle::catalogKey) { title ->
                            DiscoverCard(
                                title = title,
                                isAdded = title.mediaIdentity in addedIds,
                                columns = gridColumns,
                                onOpen = { onOpenTitle(title) },
                                onAdd = { onAdd(title) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** [columns] is the current grid density (#126): a narrower card drops the metadata line, then
 *  the title, and finally trades the full-width Add button for a compact corner affordance. */
@Composable
private fun DiscoverCard(
    title: TrendingTitle,
    isAdded: Boolean,
    columns: Int,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
) {
    val showMeta = columns <= 2
    val showTitle = columns <= 3
    PosterSurface(
        tint = tintForKey(title.tmdbId.toString()),
        imageUrl = title.posterUrl,
        cornerRadius = posterGridCornerRadius(columns),
        onClick = onOpen,
    ) {
        // Both states share the same corner now — only the fill (solid vs. tonal) and icon
        // change. A solid-vs-solid circle that swapped corners by state (#??) read as two
        // different controls; pinning the spot and leaning on fill weight to carry the "is this
        // actionable or already-done" distinction reads correctly even at a glance, not just on
        // the transition between them.
        if (isAdded) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .border(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Owned",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        // At four across the card can't carry a label at all, so the add affordance becomes a
        // corner button on the artwork rather than a full-width bar under a missing title.
        if (!showTitle && !isAdded) {
            Surface(
                onClick = onAdd,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, contentDescription = "Add ${title.title}", modifier = Modifier.size(14.dp))
                }
            }
        }
        if (showTitle) {
            Column(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .padding(if (columns >= 3) 8.dp else 12.dp),
            ) {
                Text(
                    title.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = androidx.compose.ui.graphics.Color(0xFFF3EAD9),
                    maxLines = if (showMeta) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showMeta) {
                    Text(
                        "${title.year ?: "—"}" + if (title.type == MediaType.TV) " · TV" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = androidx.compose.ui.graphics.Color(0xFFF3EAD9).copy(alpha = 0.65f),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (!isAdded) {
                    Surface(
                        onClick = onAdd,
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.fillMaxWidth().padding(top = if (showMeta) 0.dp else 6.dp),
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
}

/**
 * Preview sheet for a trending result *not yet* in the real library (#119/KP-049) — styled
 * after the Library title-detail screen's header (poster tint, title, meta line, synopsis)
 * for visual parity, but it can't be the same component: there's no [TitleDetail] to show
 * (no status, rating, or viewing history) until the title is actually added. A result already
 * in the real library skips this sheet entirely and opens the real detail screen instead
 * (see [DiscoverRoute]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrendingTitlePreviewSheet(title: TrendingTitle, isAdded: Boolean, onAdd: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                PosterSurface(
                    tint = tintForKey(title.tmdbId.toString()),
                    imageUrl = title.posterUrl,
                    modifier = Modifier.size(width = 72.dp, height = 100.dp),
                    aspectRatio = 72f / 100f,
                    cornerRadius = 12.dp,
                )
                Column(modifier = Modifier.padding(start = 14.dp).align(Alignment.CenterVertically)) {
                    Text(title.title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${title.year ?: "—"}" + if (title.type == MediaType.TV) " · TV Series" else " · Movie",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Text("Synopsis", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            Text(
                title.synopsis ?: "No synopsis available.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 22.dp),
            )

            if (isAdded) {
                TextButton(onClick = onDismiss, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Added") }
            } else {
                Button(onClick = { onAdd(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Add to library") }
            }
        }
    }
}
