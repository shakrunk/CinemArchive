package work.kumarfamilynet.cinemarchive.feature.upnext

import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.PostShowSheet
import work.kumarfamilynet.cinemarchive.core.designsystem.PosterSurface
import work.kumarfamilynet.cinemarchive.core.designsystem.tintForKey
import work.kumarfamilynet.cinemarchive.core.model.CinemaOuting
import work.kumarfamilynet.cinemarchive.core.model.CinemaOutingRules
import work.kumarfamilynet.cinemarchive.core.model.LibraryTitle
import work.kumarfamilynet.cinemarchive.core.model.UpNextBoard
import work.kumarfamilynet.cinemarchive.core.model.UpNextOuting
import work.kumarfamilynet.cinemarchive.core.model.UpNextWatching
import work.kumarfamilynet.cinemarchive.data.LibraryRepository
import work.kumarfamilynet.cinemarchive.data.OutingsRepository

class UpNextViewModel(private val repository: LibraryRepository, private val outingsRepository: OutingsRepository) : ViewModel() {
    val board = repository.observeUpNext()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpNextBoard(emptyList(), emptyList()))

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
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = UpNextViewModel(repository, outingsRepository) as T
}

@Composable
fun UpNextRoute(repository: LibraryRepository, outingsRepository: OutingsRepository, onTitleClick: (String) -> Unit) {
    val viewModel: UpNextViewModel = viewModel(factory = UpNextViewModelFactory(repository, outingsRepository))
    val board by viewModel.board.collectAsStateWithLifecycle()
    UpNextScreen(
        board,
        onTitleClick,
        onMarkWatched = viewModel::onMarkEpisodeWatched,
        onCancelOuting = viewModel::onCancelOuting,
        onRatePostShow = viewModel::onRatePostShow,
        onSaveFollowUpNotes = viewModel::onSaveFollowUpNotes,
        onDismissFollowUp = viewModel::onDismissFollowUp,
        onDidntMakeIt = viewModel::onDidntMakeIt,
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

    LazyColumn(
        contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 100.dp),
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

        items(board.freshFromTheLobby, key = { "lobby-${it.outing.id}" }) { entry ->
            FreshFromTheLobbyCard(entry, onOpen = { postShowEntry = entry })
        }

        items(board.onTheMarquee, key = { "marquee-${it.outing.id}" }) { entry ->
            MarqueeCard(entry, now, onOpen = { onTitleClick(entry.outing.titleId) }, onCancel = { onCancelOuting(entry.outing.id) })
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
                    "CONTINUE WATCHING",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
            }
        }
        items(board.watching, key = UpNextWatching::id) { title ->
            ContinueWatchingCard(title, onOpen = { onTitleClick(title.id) }, onMarkWatched = { onMarkWatched(title.id) })
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
        items(board.watchlist, key = LibraryTitle::id) { title ->
            WatchlistCard(title, onOpen = { onTitleClick(title.id) })
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

@Composable
private fun FreshFromTheLobbyCard(entry: UpNextOuting, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
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
private fun MarqueeCard(entry: UpNextOuting, now: Instant, onOpen: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val outing = entry.outing
    val countdown = CinemaOutingRules.countdownLabel(outing, now)
    val isNowShowing = CinemaOutingRules.isNowShowing(outing, now)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
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
private fun ContinueWatchingCard(title: UpNextWatching, onOpen: () -> Unit, onMarkWatched: () -> Unit) {
    val pct = if (title.episodesTotal > 0) (title.episodesWatched.toFloat() / title.episodesTotal) else 0f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
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
            Text(title.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${title.episodesWatched} / ${title.episodesTotal} episodes",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
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
            Surface(
                onClick = onMarkWatched,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) {
                Text(
                    "Mark episode watched",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun WatchlistCard(title: LibraryTitle, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
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
                "Ready whenever you are",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
