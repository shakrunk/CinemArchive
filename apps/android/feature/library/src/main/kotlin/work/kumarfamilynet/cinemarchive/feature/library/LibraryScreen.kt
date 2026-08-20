package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import work.kumarfamilynet.cinemarchive.core.designsystem.ChoiceOption
import work.kumarfamilynet.cinemarchive.core.designsystem.ConnectedToggleGroup
import work.kumarfamilynet.cinemarchive.core.designsystem.ExpressivePullToRefresh
import work.kumarfamilynet.cinemarchive.core.designsystem.GroupedSeamGap
import work.kumarfamilynet.cinemarchive.core.designsystem.PosterSurface
import work.kumarfamilynet.cinemarchive.core.designsystem.ProfileAvatarButton
import work.kumarfamilynet.cinemarchive.core.designsystem.SegmentedGroup
import work.kumarfamilynet.cinemarchive.core.designsystem.StatusBadge
import work.kumarfamilynet.cinemarchive.core.designsystem.groupedItemShape
import work.kumarfamilynet.cinemarchive.core.designsystem.ContentReadingMaxWidth
import work.kumarfamilynet.cinemarchive.core.designsystem.pinchToResizeGrid
import work.kumarfamilynet.cinemarchive.core.designsystem.posterGridCornerRadius
import work.kumarfamilynet.cinemarchive.core.designsystem.posterMinTileWidth
import work.kumarfamilynet.cinemarchive.core.designsystem.rememberCollapseOnScroll
import work.kumarfamilynet.cinemarchive.core.designsystem.tintForKey
import work.kumarfamilynet.cinemarchive.core.model.LibraryGrouping
import work.kumarfamilynet.cinemarchive.core.model.LibrarySortOrder
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.LibraryTitle
import work.kumarfamilynet.cinemarchive.core.model.LibraryViewMode
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.data.LibraryRepository
import work.kumarfamilynet.cinemarchive.data.LibrarySyncRepository

data class LibraryUiState(val titles: List<LibraryTitle> = emptyList())

class LibraryViewModel(
    repository: LibraryRepository,
    private val librarySyncRepository: LibrarySyncRepository,
) : ViewModel() {
    val uiState = repository.observeLibrary()
        .map(::LibraryUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    /** Pull-to-refresh: [LibraryUiState.titles] already updates live off Room, so this only
     *  needs to pull remote changes down — the observing flow picks them up on its own once
     *  they land locally. */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                librarySyncRepository.syncNow()
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}

@Composable
fun LibraryRoute(
    repository: LibraryRepository,
    librarySyncRepository: LibrarySyncRepository,
    viewMode: LibraryViewMode,
    onToggleViewMode: () -> Unit,
    gridColumns: Int,
    onGridColumnsChange: (Int) -> Unit,
    onOpenProfile: () -> Unit,
    profileInitial: String = "C",
    onTitleClick: (String) -> Unit,
    onFabExpandedChange: (Boolean) -> Unit = {},
) {
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModelFactory(repository, librarySyncRepository))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    var search by rememberSaveable { mutableStateOf("") }
    var statusFilters by rememberSaveable { mutableStateOf(setOf<LibraryStatus>()) }
    var genreFilters by rememberSaveable { mutableStateOf(setOf<String>()) }
    var minRating by rememberSaveable { mutableStateOf(0) }
    var sortOrder by rememberSaveable { mutableStateOf(LibrarySortOrder.LAST_INTERACTION) }
    var grouping by rememberSaveable { mutableStateOf(LibraryGrouping.NONE) }

    val availableGenres = remember(uiState.titles) {
        uiState.titles.flatMap { it.genres }.distinct().sorted()
    }

    val filtered = uiState.titles.filter { title ->
        (statusFilters.isEmpty() || title.status in statusFilters) &&
            (genreFilters.isEmpty() || title.genres.any { it in genreFilters }) &&
            (minRating == 0 || (title.rating ?: 0.0) >= minRating) &&
            (search.isBlank() || title.name.contains(search, ignoreCase = true))
    }
    val sorted = when (sortOrder) {
        // Newest interaction first, and a title with no usable timestamp at all sinks to the
        // bottom rather than floating to the top of "most recent".
        LibrarySortOrder.LAST_INTERACTION -> filtered.sortedWith(
            compareByDescending<LibraryTitle, String?>(nullsFirst()) { it.lastInteractionAt }
                .thenBy { it.name.lowercase() },
        )
        LibrarySortOrder.TITLE -> filtered.sortedBy { it.name.lowercase() }
        LibrarySortOrder.YEAR_NEWEST -> filtered.sortedWith(compareByDescending<LibraryTitle> { it.year ?: Int.MIN_VALUE }.thenBy { it.name.lowercase() })
        LibrarySortOrder.RATING_HIGHEST -> filtered.sortedWith(compareByDescending<LibraryTitle> { it.rating ?: -1.0 }.thenBy { it.name.lowercase() })
    }

    LibraryScreen(
        titles = sorted,
        search = search,
        onSearchChange = { search = it },
        statusFilters = statusFilters,
        onToggleStatus = { s -> statusFilters = if (s in statusFilters) statusFilters - s else statusFilters + s },
        availableGenres = availableGenres,
        genreFilters = genreFilters,
        onToggleGenre = { g -> genreFilters = if (g in genreFilters) genreFilters - g else genreFilters + g },
        minRating = minRating,
        onMinRatingChange = { minRating = it },
        sortOrder = sortOrder,
        onSortOrderChange = { sortOrder = it },
        grouping = grouping,
        onGroupingChange = { grouping = it },
        onResetFilters = {
            statusFilters = emptySet()
            genreFilters = emptySet()
            minRating = 0
            sortOrder = LibrarySortOrder.LAST_INTERACTION
            grouping = LibraryGrouping.NONE
        },
        viewMode = viewMode,
        onToggleViewMode = onToggleViewMode,
        gridColumns = gridColumns,
        onGridColumnsChange = onGridColumnsChange,
        onOpenProfile = onOpenProfile,
        profileInitial = profileInitial,
        onTitleClick = onTitleClick,
        onFabExpandedChange = onFabExpandedChange,
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    titles: List<LibraryTitle>,
    search: String,
    onSearchChange: (String) -> Unit,
    statusFilters: Set<LibraryStatus>,
    onToggleStatus: (LibraryStatus) -> Unit,
    availableGenres: List<String>,
    genreFilters: Set<String>,
    onToggleGenre: (String) -> Unit,
    minRating: Int,
    onMinRatingChange: (Int) -> Unit,
    sortOrder: LibrarySortOrder,
    onSortOrderChange: (LibrarySortOrder) -> Unit,
    grouping: LibraryGrouping,
    onGroupingChange: (LibraryGrouping) -> Unit,
    onResetFilters: () -> Unit,
    viewMode: LibraryViewMode,
    onToggleViewMode: () -> Unit,
    gridColumns: Int,
    onGridColumnsChange: (Int) -> Unit,
    onOpenProfile: () -> Unit,
    profileInitial: String = "C",
    onTitleClick: (String) -> Unit,
    onFabExpandedChange: (Boolean) -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    var showFilterSheet by remember { mutableStateOf(false) }
    val activeFilterCount = statusFilters.size + genreFilters.size + (if (minRating > 0) 1 else 0)

    // Status order matches the filter sheet's status chips — grouping just re-buckets the
    // already-sorted list, so relative order within each bucket is unaffected.
    val groupedTitles: List<Pair<LibraryStatus?, List<LibraryTitle>>> = if (grouping == LibraryGrouping.STATUS) {
        listOf(LibraryStatus.WATCHED, LibraryStatus.WATCHING, LibraryStatus.WATCHLIST, LibraryStatus.DROPPED)
            .mapNotNull { status ->
                val group = titles.filter { it.status == status }
                if (group.isEmpty()) null else status to group
            }
    } else {
        listOf(null to titles)
    }
    // Which headers are present — switching "Group by" (or a status filter that changes which
    // header rows exist) re-anchors the lazy list's first-visible index without the user having
    // scrolled. Feeding that shape in as the scroll hook's content key stops the re-anchor from
    // being misread as a downward scroll, which otherwise fed back into a collapse/expand loop
    // between this and the shrinking search bar below (#187).
    val headerShape = groupedTitles.map { it.first }

    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val collapsed = if (viewMode == LibraryViewMode.GRID) {
        rememberCollapseOnScroll(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset, headerShape)
    } else {
        rememberCollapseOnScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, headerShape)
    }
    androidx.compose.runtime.LaunchedEffect(collapsed) { onFabExpandedChange(!collapsed) }

    Column(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        // Eyebrow and title share a column beside the avatar rather than each claiming a
        // row of their own — the avatar is taller than the eyebrow, so the old layout paid
        // for its height twice over (#142).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "THE COLLECTION",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("Library", style = MaterialTheme.typography.headlineLarge)
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
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .widthIn(max = ContentReadingMaxWidth)
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
                    IconButton(onClick = { showFilterSheet = true }, modifier = Modifier.size(32.dp)) {
                        BadgedBox(badge = {
                            if (activeFilterCount > 0) {
                                Badge { Text(activeFilterCount.toString()) }
                            }
                        }) {
                            Icon(
                                Icons.Filled.FilterList,
                                contentDescription = "Filter and sort library",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    IconButton(onClick = onToggleViewMode, modifier = Modifier.size(32.dp)) {
                        Icon(
                            // Shows the icon for the mode a tap will switch TO, not the current mode —
                            // the current mode is already visible in the list/grid below it.
                            if (viewMode == LibraryViewMode.GRID) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                            contentDescription = if (viewMode == LibraryViewMode.GRID) "Switch to list view" else "Switch to grid view",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        if (showFilterSheet) {
            LibraryFilterSheet(
                statusFilters = statusFilters,
                onToggleStatus = onToggleStatus,
                availableGenres = availableGenres,
                genreFilters = genreFilters,
                onToggleGenre = onToggleGenre,
                minRating = minRating,
                onMinRatingChange = onMinRatingChange,
                sortOrder = sortOrder,
                onSortOrderChange = onSortOrderChange,
                grouping = grouping,
                onGroupingChange = onGroupingChange,
                onReset = onResetFilters,
                onDismiss = { showFilterSheet = false },
            )
        }

        ExpressivePullToRefresh(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
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
                        columns = GridCells.Adaptive(minSize = posterMinTileWidth(gridColumns)),
                        state = gridState,
                        contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 100.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .pinchToResizeGrid(gridColumns, onGridColumnsChange),
                    ) {
                        groupedTitles.forEach { (status, group) ->
                            if (status != null) {
                                item(span = { GridItemSpan(maxLineSpan) }, key = "header-${status.name}") {
                                    LibraryGroupHeader(libraryStatusLabel(status))
                                }
                            }
                            items(group, key = LibraryTitle::id) { title ->
                                LibraryGridCard(
                                    title,
                                    columns = gridColumns,
                                    onClick = { onTitleClick(title.id) },
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 100.dp),
                        verticalArrangement = Arrangement.spacedBy(GroupedSeamGap),
                    ) {
                        groupedTitles.forEach { (status, group) ->
                            if (status != null) {
                                item(key = "header-${status.name}") {
                                    LibraryGroupHeader(libraryStatusLabel(status))
                                }
                            }
                            itemsIndexed(group, key = { _, title -> title.id }) { index, title ->
                                LibraryListRow(
                                    title,
                                    shape = groupedItemShape(isFirst = index == 0, isLast = index == group.lastIndex),
                                    onClick = { onTitleClick(title.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * [columns] is the current grid density (#126). A card only has room for so much: at three or
 * more across the poster is too narrow for the secondary line, and at four the title goes too —
 * the artwork carries it, and the status badge alone stays as the one piece of state worth
 * keeping legible.
 */
@Composable
private fun LibraryGridCard(title: LibraryTitle, columns: Int, onClick: () -> Unit) {
    val showTitle = columns <= 3
    val showMeta = columns <= 2
    val padding = if (columns >= 3) 8.dp else 12.dp

    PosterSurface(
        tint = tintForKey(title.id),
        imageUrl = title.posterUrl,
        cornerRadius = posterGridCornerRadius(columns),
        onClick = onClick,
    ) {
        // Two independently-aligned children, not one Row(SpaceBetween): a plain Row hands the
        // "FILM"/"SERIES" label first dibs on width and only gives the badge whatever's left,
        // so at larger font scales the badge lost that tug-of-war and wrapped mid-word over the
        // label. Aligning each to its own corner gives both the card's full width to lay out in.
        val cardPadding = if (columns >= 3) 6.dp else 10.dp
        if (showMeta) {
            Text(
                if (title.type == MediaType.TV) "SERIES" else "FILM",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.TopStart).padding(cardPadding),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.align(Alignment.TopEnd).padding(cardPadding),
        ) {
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
        if (showTitle) {
            Column(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(padding)) {
                Text(
                    title.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFFF3EAD9),
                    maxLines = if (showMeta) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showMeta) {
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
    }
}

@Composable
private fun LibraryListRow(title: LibraryTitle, shape: Shape, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
}

private fun starGlyphs(rating: Double): String {
    val n = rating.toInt().coerceIn(0, 5)
    return "★".repeat(n) + "☆".repeat(5 - n)
}

private fun libraryStatusLabel(status: LibraryStatus): String = when (status) {
    LibraryStatus.WATCHED -> "Watched"
    LibraryStatus.WATCHING -> "Watching"
    LibraryStatus.WATCHLIST -> "Watchlist"
    LibraryStatus.DROPPED -> "Dropped"
}

@Composable
private fun LibraryGroupHeader(label: String) {
    Text(
        label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

/**
 * The Library filter/sort sheet (#120/KP-050) — replaces the old always-visible status row.
 * Status stays a [ConnectedToggleGroup] (multi-select), sort/grouping are single-select
 * [SegmentedGroup]s, minimum rating is a tappable star row (tapping the already-selected star
 * clears it back to "any"), and genres — when the library has any — are wrap-flowing
 * [FilterChip]s.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryFilterSheet(
    statusFilters: Set<LibraryStatus>,
    onToggleStatus: (LibraryStatus) -> Unit,
    availableGenres: List<String>,
    genreFilters: Set<String>,
    onToggleGenre: (String) -> Unit,
    minRating: Int,
    onMinRatingChange: (Int) -> Unit,
    sortOrder: LibrarySortOrder,
    onSortOrderChange: (LibrarySortOrder) -> Unit,
    grouping: LibraryGrouping,
    onGroupingChange: (LibraryGrouping) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).padding(bottom = 24.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                Text("Filter & sort", style = MaterialTheme.typography.titleMedium)
                val hasAnyFilter = statusFilters.isNotEmpty() || genreFilters.isNotEmpty() || minRating > 0 ||
                    sortOrder != LibrarySortOrder.LAST_INTERACTION || grouping != LibraryGrouping.NONE
                if (hasAnyFilter) {
                    TextButton(onClick = onReset) {
                        Text("Reset")
                    }
                }
            }

            FilterSheetLabel("Status")
            ConnectedToggleGroup(
                options = listOf(
                    ChoiceOption(LibraryStatus.WATCHED, "Watched"),
                    ChoiceOption(LibraryStatus.WATCHING, "Watching"),
                    ChoiceOption(LibraryStatus.WATCHLIST, "Watchlist"),
                    ChoiceOption(LibraryStatus.DROPPED, "Dropped"),
                ),
                selected = statusFilters,
                onToggle = onToggleStatus,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            FilterSheetLabel("Sort by")
            SegmentedGroup(
                options = listOf(
                    ChoiceOption(LibrarySortOrder.LAST_INTERACTION, "Smart"),
                    ChoiceOption(LibrarySortOrder.TITLE, "Title"),
                    ChoiceOption(LibrarySortOrder.YEAR_NEWEST, "Newest"),
                    ChoiceOption(LibrarySortOrder.RATING_HIGHEST, "Top rated"),
                ),
                selected = sortOrder,
                onSelect = onSortOrderChange,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            FilterSheetLabel("Group by")
            SegmentedGroup(
                options = listOf(
                    ChoiceOption(LibraryGrouping.NONE, "None"),
                    ChoiceOption(LibraryGrouping.STATUS, "Status"),
                ),
                selected = grouping,
                onSelect = onGroupingChange,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            FilterSheetLabel("Minimum rating")
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(bottom = if (availableGenres.isEmpty()) 0.dp else 16.dp),
            ) {
                for (star in 1..5) {
                    IconButton(onClick = { onMinRatingChange(if (minRating == star) 0 else star) }) {
                        Icon(
                            if (star <= minRating) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "At least $star star${if (star == 1) "" else "s"}",
                            tint = if (star <= minRating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (availableGenres.isNotEmpty()) {
                FilterSheetLabel("Genres")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableGenres.forEach { genre ->
                        FilterChip(
                            selected = genre in genreFilters,
                            onClick = { onToggleGenre(genre) },
                            label = { Text(genre) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSheetLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
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
    private val librarySyncRepository: LibrarySyncRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(repository, librarySyncRepository) as T
}
