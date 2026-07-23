package work.kumarfamilynet.cinemarchive.feature.upnext

import android.content.Intent
import android.provider.CalendarContract
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.PostShowSheet
import work.kumarfamilynet.cinemarchive.core.designsystem.PosterSurface
import work.kumarfamilynet.cinemarchive.core.designsystem.rememberCollapseOnScroll
import work.kumarfamilynet.cinemarchive.core.designsystem.tintForKey
import work.kumarfamilynet.cinemarchive.core.model.CinemaOuting
import work.kumarfamilynet.cinemarchive.core.model.CinemaOutingRules
import work.kumarfamilynet.cinemarchive.core.model.LibraryTitle
import work.kumarfamilynet.cinemarchive.core.model.UpNextBoard
import work.kumarfamilynet.cinemarchive.core.model.UpNextOuting
import work.kumarfamilynet.cinemarchive.core.model.UpNextWatching
import work.kumarfamilynet.cinemarchive.data.LibraryRepository
import work.kumarfamilynet.cinemarchive.data.LibrarySyncRepository
import work.kumarfamilynet.cinemarchive.data.OutingsRepository

class UpNextViewModel(
    private val repository: LibraryRepository,
    private val outingsRepository: OutingsRepository,
    private val librarySyncRepository: LibrarySyncRepository,
) : ViewModel() {
    val board = repository.observeUpNext()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpNextBoard(emptyList(), emptyList()))

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    /** Pull-to-refresh: same pull-then-reconcile ordering as the app-resume trigger in
     *  MainActivity — pull remote changes down before deciding which outings are due. */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                librarySyncRepository.syncNow()
                outingsRepository.completeDueOutings()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun onMarkEpisodeWatched(titleId: String) {
        viewModelScope.launch {
            repository.advanceNextEpisode(titleId, LocalDate.now().toString())
        }
    }

    fun onCancelOuting(outingId: String) {
        viewModelScope.launch { outingsRepository.cancelOuting(outingId) }
    }

    fun onRatePostShow(viewingId: String, titleId: String, rating: Double) {
        viewModelScope.launch { repository.rateViewing(viewingId, titleId, rating) }
    }

    fun onSaveFollowUpNotes(viewingId: String, notes: String) {
        viewModelScope.launch { repository.updateViewingNotes(viewingId, notes) }
    }

    fun onDismissFollowUp(outingId: String) {
        viewModelScope.launch { outingsRepository.dismissFollowUp(outingId) }
    }

    fun onDidntMakeIt(outingId: String) {
        viewModelScope.launch { outingsRepository.revertCompletion(outingId) }
    }
}

private class UpNextViewModelFactory(
    private val repository: LibraryRepository,
    private val outingsRepository: OutingsRepository,
    private val librarySyncRepository: LibrarySyncRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        UpNextViewModel(repository, outingsRepository, librarySyncRepository) as T
}

@Composable
fun UpNextRoute(
    repository: LibraryRepository,
    outingsRepository: OutingsRepository,
    librarySyncRepository: LibrarySyncRepository,
    onTitleClick: (String) -> Unit,
    onFabExpandedChange: (Boolean) -> Unit = {},
) {
    val viewModel: UpNextViewModel = viewModel(
        factory = UpNextViewModelFactory(repository, outingsRepository, librarySyncRepository),
    )
    val board by viewModel.board.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    UpNextScreen(
        board,
        onTitleClick,
        onMarkWatched = viewModel::onMarkEpisodeWatched,
        onCancelOuting = viewModel::onCancelOuting,
        onRatePostShow = viewModel::onRatePostShow,
        onSaveFollowUpNotes = viewModel::onSaveFollowUpNotes,
        onDismissFollowUp = viewModel::onDismissFollowUp,
        onDidntMakeIt = viewModel::onDidntMakeIt,
        onFabExpandedChange = onFabExpandedChange,
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun UpNextScreen(
    board: UpNextBoard,
    onTitleClick: (String) -> Unit,
    onMarkWatched: (String) -> Unit,
    onCancelOuting: (String) -> Unit,
    onRatePostShow: (String, String, Double) -> Unit,
    onSaveFollowUpNotes: (String, String) -> Unit,
    onDismissFollowUp: (String) -> Unit,
    onDidntMakeIt: (String) -> Unit,
    onFabExpandedChange: (Boolean) -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    // A single shared tick for every marquee card's countdown label — the completion itself
    // is driven by the reconciler (app resume / launch), never by this cosmetic timer (web
    // plan §4.5).
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = Instant.now()
        }
    }
    var postShowEntry by remember { mutableStateOf<UpNextOuting?>(null) }

    val listState = rememberLazyListState()
    val collapsed = rememberCollapseOnScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
    LaunchedEffect(collapsed) { onFabExpandedChange(!collapsed) }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 100.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text(
                    "ON THE MARQUEE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Up Next",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(top = 2.dp, bottom = 20.dp),
                )
            }

            itemsIndexed(board.freshFromTheLobby, key = { _, it -> "lobby-${it.outing.id}" }) { index, entry ->
                FreshFromTheLobbyCard(
                    entry,
                    shape = groupShape(index, board.freshFromTheLobby.size),
                    onOpen = { postShowEntry = entry },
                )
            }

            itemsIndexed(board.onTheMarquee, key = { _, it -> "marquee-${it.outing.id}" }) { index, entry ->
                MarqueeCard(
                    entry,
                    now,
                    shape = groupShape(index, board.onTheMarquee.size),
                    onOpen = { onTitleClick(entry.outing.titleId) },
                    onCancel = { onCancelOuting(entry.outing.id) },
                )
            }

            if (board.watching.isEmpty() && board.watchlist.isEmpty() && board.onTheMarquee.isEmpty() && board.freshFromTheLobby.isEmpty()) {
                item {
                    Text(
                        "Nothing queued up yet — start something from your Library.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (board.watching.isNotEmpty()) {
                item {
                    Text(
                        "NEXT EPISODE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                    )
                }
            }
            itemsIndexed(board.watching, key = { _, it -> it.id }) { index, title ->
                ContinueWatchingCard(
                    title,
                    shape = groupShape(index, board.watching.size),
                    onOpen = { onTitleClick(title.id) },
                    onMarkWatched = { onMarkWatched(title.id) },
                )
            }

            if (board.watchlist.isNotEmpty()) {
                item {
                    Text(
                        "ON YOUR WATCHLIST",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                    )
                }
            }
            itemsIndexed(board.watchlist, key = { _, it -> it.id }) { index, title ->
                WatchlistCard(title, shape = groupShape(index, board.watchlist.size), onOpen = { onTitleClick(title.id) })
            }
        }
    }

    postShowEntry?.let { entry ->
        val viewingId = entry.outing.completedViewingId
        if (viewingId != null) {
            PostShowSheet(
                titleName = entry.titleName,
                venue = entry.outing.venue,
                companions = entry.outing.companions,
                initialRating = 0.0,
                initialNotes = "",
                onRate = { onRatePostShow(viewingId, entry.outing.titleId, it) },
                onSaveNotes = { onSaveFollowUpNotes(viewingId, it) },
                onDidntMakeIt = {
                    onDidntMakeIt(entry.outing.id)
                    postShowEntry = null
                },
                onDismiss = {
                    onDismissFollowUp(entry.outing.id)
                    postShowEntry = null
                },
            )
        }
    }
}

private val GroupOuterCorner = 24.dp
private val GroupInnerCorner = 6.dp

/** Rows of the same card type stacked back-to-back (no header between them) read as one
 *  group rather than a stack of independent cards: only the group's outermost corners get
 *  the full radius, the touching edges in between get a small one, and [Arrangement.spacedBy]
 *  above still leaves a thin seam between rows so they don't fuse into a single hitbox. */
private fun groupShape(index: Int, count: Int): RoundedCornerShape {
    if (count <= 1) return RoundedCornerShape(GroupOuterCorner)
    val top = if (index == 0) GroupOuterCorner else GroupInnerCorner
    val bottom = if (index == count - 1) GroupOuterCorner else GroupInnerCorner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
private fun FreshFromTheLobbyCard(entry: UpNextOuting, shape: Shape, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .clickable(onClick = onOpen)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PosterSurface(
            tint = tintForKey(entry.outing.id),
            imageUrl = entry.posterUrl,
            modifier = Modifier.size(width = 56.dp, height = 80.dp),
            aspectRatio = 56f / 80f,
            cornerRadius = 14.dp,
        )
        Column {
            Text("FRESH FROM THE LOBBY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(entry.titleName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text("How was it?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun MarqueeCard(entry: UpNextOuting, now: Instant, shape: Shape, onOpen: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val outing = entry.outing
    val countdown = CinemaOutingRules.countdownLabel(outing, now)
    val isNowShowing = CinemaOutingRules.isNowShowing(outing, now)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onOpen)
            .padding(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PosterSurface(
                tint = tintForKey(outing.id),
                imageUrl = entry.posterUrl,
                modifier = Modifier.size(width = 56.dp, height = 80.dp),
                aspectRatio = 56f / 80f,
                cornerRadius = 14.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.titleName, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ConfirmationNumber, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    Text(
                        countdown,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                val details = listOfNotNull(outing.venue, outing.companions.takeIf { it.isNotEmpty() }?.let { "with ${it.joinToString(" & ")}" })
                    .joinToString(" · ")
                if (details.isNotBlank()) {
                    Text(details, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(modifier = Modifier.padding(top = 8.dp)) {
            TextButton(onClick = { addOutingToCalendar(context, entry) }) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Add to calendar", modifier = Modifier.padding(start = 6.dp))
            }
            if (!isNowShowing) {
                TextButton(onClick = onCancel) { Text("Cancel outing") }
            }
        }
    }
}

/** Uses Android's Calendar Provider insert intent rather than a hand-rolled `.ics` file (the
 *  web app's approach, docs/superpowers/plans/2026-07-11-cinema-outings.md §4.5) — Android has
 *  a native equivalent for "add this to my calendar" that needs no file/VALARM plumbing. */
private fun addOutingToCalendar(context: android.content.Context, entry: UpNextOuting) {
    val outing = entry.outing
    val details = listOfNotNull(
        outing.companions.takeIf { it.isNotEmpty() }?.let { "With ${it.joinToString(", ")}" },
        outing.seat?.let { "Seat $it" },
        outing.bookingRef?.let { "Booking ref $it" },
        outing.notes,
    ).joinToString("\n")
    val intent = Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, Instant.parse(outing.showtime).toEpochMilli())
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, Instant.parse(outing.endsAt).toEpochMilli())
        putExtra(CalendarContract.Events.TITLE, "🎬 ${entry.titleName}")
        outing.venue?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        putExtra(CalendarContract.Events.DESCRIPTION, details)
    }
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    }
}

@Composable
private fun ContinueWatchingCard(title: UpNextWatching, shape: Shape, onOpen: () -> Unit, onMarkWatched: () -> Unit) {
    val pct = if (title.episodesTotal > 0) (title.episodesWatched.toFloat() / title.episodesTotal) else 0f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onOpen)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PosterSurface(
            tint = tintForKey(title.id),
            imageUrl = title.posterUrl,
            modifier = Modifier.size(width = 56.dp, height = 80.dp),
            aspectRatio = 56f / 80f,
            cornerRadius = 14.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            val hasNext = title.nextSeasonNumber != null && title.nextEpisodeNumber != null
            Text(
                if (hasNext) title.nextEpisodeName ?: "Episode ${title.nextEpisodeNumber}" else title.name,
                style = MaterialTheme.typography.titleMedium,
            )
            if (hasNext) {
                Text(
                    title.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (hasNext) "S${title.nextSeasonNumber} E${title.nextEpisodeNumber}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "${title.episodesWatched} / ${title.episodesTotal} episodes",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(pct)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary),
                ) {}
            }
        }
        Surface(
            onClick = onMarkWatched,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, contentDescription = "Mark episode watched", modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Mirrors src/views/UpNext.tsx's formatReleaseDate: parses the plain YYYY-MM-DD as a local
 *  date (not an instant) so no timezone shift can push it a day off. */
private fun formatReleaseDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)) }
        .getOrDefault(iso)

@Composable
private fun WatchlistCard(title: LibraryTitle, shape: Shape, onOpen: () -> Unit) {
    val releaseDate = title.releaseDate
    val isUpcoming = releaseDate != null && runCatching { LocalDate.parse(releaseDate) > LocalDate.now() }.getOrDefault(false)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onOpen)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PosterSurface(
            tint = tintForKey(title.id),
            imageUrl = title.posterUrl,
            modifier = Modifier.size(width = 56.dp, height = 80.dp),
            aspectRatio = 56f / 80f,
            cornerRadius = 14.dp,
        )
        Column {
            Text(title.name, style = MaterialTheme.typography.titleMedium)
            Text(
                if (isUpcoming) "Releases ${formatReleaseDate(releaseDate!!)}" else "On your watchlist",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
