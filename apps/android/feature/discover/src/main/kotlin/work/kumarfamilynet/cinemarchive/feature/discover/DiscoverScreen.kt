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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
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
import work.kumarfamilynet.cinemarchive.data.CatalogLookup

private class DiscoverViewModelFactory(
    private val repository: DiscoverRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = DiscoverViewModel(repository) as T
}

private class DiscoverShelvesViewModelFactory(
    private val repository: DiscoverRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = DiscoverShelvesViewModel(
        libraryRepository.observeDiscoverLibrary(), repository::fetchRecommendations, repository::fetchPersonTitles,
    ) as T
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
    val shelvesViewModel: DiscoverShelvesViewModel = viewModel(factory = DiscoverShelvesViewModelFactory(repository, libraryRepository))
    val shelves by shelvesViewModel.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(uiState.mode, uiState.query, uiState.genreId) {
        shelvesViewModel.setVisible(uiState.mode == DiscoverMode.TITLES && uiState.query.isBlank() && uiState.genreId == null)
    }

    // The same media identity drives ownership badges, preview actions and opening detail.
    val idsByTmdbKey by libraryRepository.observeLibraryTitleIdsByTmdbKey()
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    var preview by remember { mutableStateOf<TrendingTitle?>(null) }

    DiscoverScreen(
        search = uiState.query,
        onSearchChange = viewModel::onQueryChange,
        typeFilter = uiState.typeFilter,
        onTypeFilterChange = viewModel::onTypeChange,
        titles = uiState.titles,
        isLoading = uiState.isLoading,
        isRefreshing = uiState.isRefreshing,
        error = uiState.error,
        onRetry = viewModel::retry,
        onRefresh = { viewModel.refresh(); shelvesViewModel.retryRecommendations(); shelvesViewModel.retryStarring() },
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
        genreId = uiState.genreId,
        onGenreChange = viewModel::onGenreChange,
        hasMore = uiState.hasMore,
        isLoadingMore = uiState.isLoadingMore,
        moreError = uiState.moreError,
        onLoadMore = viewModel::loadMore,
        mode = uiState.mode,
        onModeChange = viewModel::onModeChange,
        lookups = uiState.lookups,
        selectedLookup = uiState.selectedLookup,
        onLookupSelect = viewModel::onLookupSelect,
        onLookupBack = viewModel::clearLookup,
        shelves = shelves,
        onRecommendationTitle = shelvesViewModel::selectTitle,
        onStarringPerson = shelvesViewModel::selectPerson,
        onRetryRecommendations = shelvesViewModel::retryRecommendations,
        onRetryStarring = shelvesViewModel::retryStarring,
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
fun DiscoverScreen(
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
    genreId: Int? = null,
    onGenreChange: (Int?) -> Unit = {},
    hasMore: Boolean = false,
    isLoadingMore: Boolean = false,
    moreError: String? = null,
    onLoadMore: () -> Unit = {},
    mode: DiscoverMode = DiscoverMode.TITLES,
    onModeChange: (DiscoverMode) -> Unit = {},
    lookups: List<CatalogLookup> = emptyList(),
    selectedLookup: CatalogLookup? = null,
    onLookupSelect: (CatalogLookup) -> Unit = {},
    onLookupBack: () -> Unit = {},
    shelves: DiscoverShelvesState = DiscoverShelvesState(),
    onRecommendationTitle: (String) -> Unit = {},
    onStarringPerson: (Int) -> Unit = {},
    onRetryRecommendations: () -> Unit = {},
    onRetryStarring: () -> Unit = {},
) {
    val showShelves = mode == DiscoverMode.TITLES && search.isBlank() && genreId == null && shelves.library.titles.isNotEmpty()
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
                SegmentedGroup(
                    options = DiscoverMode.entries.map { ChoiceOption(it, it.label) },
                    selected = mode, onSelect = onModeChange,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
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
                                    when (mode) {
                                        DiscoverMode.TITLES -> "Search movies & TV…"
                                        DiscoverMode.PEOPLE -> "Search actors, directors & crew…"
                                        DiscoverMode.STUDIOS -> "Search studios & companies…"
                                    },
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
                if (mode == DiscoverMode.TITLES) Box(Modifier.padding(horizontal = 20.dp)) {
                    var genresExpanded by remember { mutableStateOf(false) }
                    val genres = discoverGenres(typeFilter)
                    TextButton(onClick = { genresExpanded = true }) {
                        Text("Genre: ${genres.firstOrNull { it.id == genreId }?.name ?: "All genres"}")
                    }
                    DropdownMenu(expanded = genresExpanded, onDismissRequest = { genresExpanded = false }) {
                        DropdownMenuItem(text = { Text("All genres") }, onClick = { genresExpanded = false; onGenreChange(null) })
                        genres.forEach { genre ->
                            DropdownMenuItem(text = { Text(genre.name) }, onClick = { genresExpanded = false; onGenreChange(genre.id) })
                        }
                    }
                }
                if (selectedLookup != null) TextButton(onClick = onLookupBack, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text("Back to ${mode.label.lowercase()}")
                }
            }
        }

        when {
            isLoading && !showShelves -> Box(modifier = Modifier.fillMaxSize().padding(top = 48.dp), contentAlignment = Alignment.TopCenter) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            error != null && !showShelves -> Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (mode != DiscoverMode.TITLES) "Couldn't load ${mode.label.lowercase()}" else if (search.isNotBlank()) "Couldn't search titles" else if (genreId != null) "Couldn't load this genre" else "Couldn't load trending titles",
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
            mode != DiscoverMode.TITLES && selectedLookup == null -> LazyColumn(
                modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (lookups.isEmpty()) item {
                    Text(if (search.isBlank()) "Search for ${if (mode == DiscoverMode.PEOPLE) "a person" else "a studio"}"
                        else "No ${mode.label.lowercase()} found for “${search.trim()}”")
                }
                items(lookups, key = { it.id }) { lookup ->
                    Surface(onClick = { onLookupSelect(lookup) }, shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            PosterSurface(tint = tintForKey(lookup.id.toString()), imageUrl = lookup.imageUrl,
                                modifier = Modifier.size(48.dp, 64.dp), aspectRatio = 0.75f, cornerRadius = 8.dp)
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(lookup.name, style = MaterialTheme.typography.titleMedium)
                                if (lookup.description.isNotBlank()) Text(lookup.description, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            else -> ExpressivePullToRefresh(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (search.isNotBlank() && titles.isEmpty()) "No titles found for “${search.trim()}”" else "${titles.size} titles",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                        )
                        if (search.isBlank() && hasMore) TextButton(onClick = onLoadMore, enabled = !isLoadingMore && !isRefreshing) {
                            Text(if (isLoadingMore) "Loading more…" else if (moreError != null) "Retry more" else "View more")
                        }
                    }
                    if (moreError != null) Text(moreError, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))

                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = posterMinTileWidth(gridColumns)),
                        state = gridState,
                        contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 100.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("discover-grid")
                            .pinchToResizeGrid(gridColumns, onGridColumnsChange),
                    ) {
                        if (showShelves && isLoading) item(key = "trending-loading", span = { GridItemSpan(maxLineSpan) }) {
                            Text("Loading trending titles…")
                        }
                        if (showShelves && error != null) item(key = "trending-error", span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Text("Couldn't load trending titles", color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = onRetry) { Text("Retry trending") }
                            }
                        }
                        items(titles, key = TrendingTitle::catalogKey) { title ->
                            DiscoverCard(
                                title = title,
                                isAdded = title.mediaIdentity in addedIds,
                                columns = gridColumns,
                                onOpen = { onOpenTitle(title) },
                                onAdd = { onAdd(title) },
                            )
                        }
                        if (showShelves) {
                            item(key = "recommendations", span = { GridItemSpan(maxLineSpan) }) {
                                DiscoverShelf("Because You Watched", "recommendations",
                                    shelves.library.titles.map { it.id to it.name }, shelves.selectedTitleId,
                                    "Choose a title to base recommendations on", "Search titles",
                                    shelves.recommendations, shelves.visible(shelves.recommendations, typeFilter).filterNot { it.mediaIdentity in addedIds },
                                    "No recommendations found for this title — try picking another.",
                                    onRecommendationTitle, onRetryRecommendations, onOpenTitle, onAdd)
                            }
                            if (shelves.library.cast.isNotEmpty()) item(key = "starring", span = { GridItemSpan(maxLineSpan) }) {
                                DiscoverShelf("More Starring", "starring",
                                    shelves.library.cast.map { it.tmdbPersonId.toString() to it.name }, shelves.selectedPersonId?.toString(),
                                    "Choose an actor to see more of their titles", "Search actors",
                                    shelves.starring, shelves.visible(shelves.starring, typeFilter).filterNot { it.mediaIdentity in addedIds },
                                    "Nothing else to show for this cast member. Try another actor or type.",
                                    { it.toIntOrNull()?.let(onStarringPerson) }, onRetryStarring, onOpenTitle, onAdd)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverShelf(
    heading: String,
    tag: String,
    options: List<Pair<String, String>>,
    selectedId: String?,
    pickerLabel: String,
    searchLabel: String,
    state: DiscoverShelfResult,
    titles: List<TrendingTitle>,
    emptyMessage: String,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
    onOpen: (TrendingTitle) -> Unit,
    onAdd: (TrendingTitle) -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().testTag("discover-shelf-$tag"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(heading, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
        OutlinedButton(onClick = { query = ""; choosing = true }, modifier = Modifier.semantics { contentDescription = pickerLabel }) {
            Text(options.firstOrNull { it.first == selectedId }?.second ?: "Select…", maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        when {
            state.loading -> Text("Loading ${if (tag == "recommendations") "recommendations" else "filmography"}…")
            state.error != null -> Column {
                Text(state.error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text(if (tag == "recommendations") "Retry recommendations" else "Retry filmography") }
            }
            titles.isEmpty() -> Text(emptyMessage, style = MaterialTheme.typography.bodyMedium)
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(titles, key = TrendingTitle::catalogKey) { title ->
                    Box(Modifier.width(160.dp).testTag("$tag-${title.catalogKey}")) {
                        DiscoverCard(title, isAdded = false, columns = 2, onOpen = { onOpen(title) }, onAdd = { onAdd(title) })
                    }
                }
            }
        }
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text(pickerLabel) },
        text = {
            Column {
                if (options.size > 8) OutlinedTextField(query, { query = it }, label = { Text(searchLabel) }, singleLine = true)
                val matching = options.filter { options.size <= 8 || it.second.contains(query.trim(), ignoreCase = true) }
                if (matching.isEmpty()) Text("No matches")
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(matching, key = { it.first }) { option ->
                        TextButton(onClick = { onSelect(option.first); choosing = false },
                            modifier = Modifier.fillMaxWidth().testTag("$tag-choice-${option.first}")) { Text(option.second) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } })
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
