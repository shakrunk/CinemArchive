package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.model.EpisodeDetail
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.SeasonDetail
import work.kumarfamilynet.cinemarchive.core.model.TitleDetail
import work.kumarfamilynet.cinemarchive.core.model.Viewing
import work.kumarfamilynet.cinemarchive.data.LibraryRepository

class TitleDetailViewModel(private val repository: LibraryRepository, private val titleId: String) : ViewModel() {
    val uiState = repository.observeTitleDetail(titleId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Optimistic local write + queued remote push — see LibraryRepository.logEpisodeWatched. */
    fun onMarkWatched(episodeId: String) {
        viewModelScope.launch { repository.logEpisodeWatched(episodeId, LocalDate.now().toString()) }
    }

    fun onRateEpisode(episodeId: String, rating: Double) {
        viewModelScope.launch { repository.logEpisodeRating(episodeId, rating, Instant.now().toString()) }
    }

    fun onSubmitReview(episodeId: String, reviewText: String) {
        viewModelScope.launch { repository.logEpisodeReview(episodeId, reviewText, Instant.now().toString()) }
    }

    fun onLogViewing() {
        viewModelScope.launch { repository.logViewing(titleId, LocalDate.now().toString()) }
    }

    fun onChangeStatus(status: LibraryStatus) {
        viewModelScope.launch { repository.updateTitleStatus(titleId, status, Instant.now().toString()) }
    }
}

@Composable
fun TitleDetailRoute(repository: LibraryRepository, titleId: String, onBack: () -> Unit) {
    val viewModel: TitleDetailViewModel =
        viewModel(factory = TitleDetailViewModelFactory(repository, titleId))
    val detail by viewModel.uiState.collectAsStateWithLifecycle()
    TitleDetailScreen(
        detail,
        onBack,
        onMarkWatched = viewModel::onMarkWatched,
        onRateEpisode = viewModel::onRateEpisode,
        onSubmitReview = viewModel::onSubmitReview,
        onLogViewing = viewModel::onLogViewing,
        onChangeStatus = viewModel::onChangeStatus,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleDetailScreen(
    detail: TitleDetail?,
    onBack: () -> Unit,
    onMarkWatched: (String) -> Unit = {},
    onRateEpisode: (String, Double) -> Unit = { _, _ -> },
    onSubmitReview: (String, String) -> Unit = { _, _ -> },
    onLogViewing: () -> Unit = {},
    onChangeStatus: (LibraryStatus) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.title ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.headlineSmall)
                    }
                },
            )
        },
    ) { innerPadding ->
        if (detail == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(innerPadding),
        ) {
            item { TitleHeader(detail, onChangeStatus) }

            if (detail.seasons.isNotEmpty()) {
                item { Text("Seasons", style = MaterialTheme.typography.titleMedium) }
                items(detail.seasons, key = SeasonDetail::id) { season ->
                    SeasonRow(season, onMarkWatched, onRateEpisode, onSubmitReview)
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Viewing history", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onLogViewing) { Text("Log a viewing") }
                }
            }
            items(detail.viewings, key = Viewing::id) { viewing -> ViewingRow(viewing) }
        }
    }
}

@Composable
private fun TitleHeader(detail: TitleDetail, onChangeStatus: (LibraryStatus) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        detail.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodyMedium) }
        detail.director?.let { Text("Directed by $it", style = MaterialTheme.typography.bodyMedium) }
        detail.network?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        detail.synopsis?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        if (detail.genres.isNotEmpty()) {
            Text(detail.genres.joinToString(" · "), style = MaterialTheme.typography.labelLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LibraryStatus.entries.forEach { status ->
                if (status == detail.status) {
                    Text(
                        status.name,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(8.dp),
                    )
                } else {
                    TextButton(onClick = { onChangeStatus(status) }) { Text(status.name) }
                }
            }
        }
    }
}

@Composable
private fun SeasonRow(
    season: SeasonDetail,
    onMarkWatched: (String) -> Unit,
    onRateEpisode: (String, Double) -> Unit,
    onSubmitReview: (String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Season ${season.seasonNumber} — ${season.episodesWatched}/${season.episodeCount} watched",
            style = MaterialTheme.typography.titleSmall,
        )
        season.episodes.forEach { episode -> EpisodeRow(episode, onMarkWatched, onRateEpisode, onSubmitReview) }
    }
}

@Composable
private fun EpisodeRow(
    episode: EpisodeDetail,
    onMarkWatched: (String) -> Unit,
    onRateEpisode: (String, Double) -> Unit,
    onSubmitReview: (String, String) -> Unit,
) {
    val watched = episode.watchCount > 0
    val label = buildString {
        append("${episode.episodeNumber}. ${episode.episodeName ?: "Untitled"}")
        if (watched) append(" — watched ${episode.watchCount}×")
        episode.latestRating?.let { append(" — ★$it") }
    }
    var reviewExpanded by rememberSaveable(episode.id) { mutableStateOf(false) }
    var reviewText by rememberSaveable(episode.id) { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (!watched) {
                TextButton(onClick = { onMarkWatched(episode.id) }) {
                    Text("Mark watched")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (star in 1..5) {
                Text(
                    if (star <= (episode.latestRating ?: 0.0)) "★" else "☆",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .clickable { onRateEpisode(episode.id, star.toDouble()) }
                        .padding(2.dp),
                )
            }
            TextButton(onClick = { reviewExpanded = !reviewExpanded }) { Text("Review") }
        }
        if (reviewExpanded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = reviewText,
                    onValueChange = { reviewText = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Your review") },
                )
                TextButton(
                    onClick = {
                        onSubmitReview(episode.id, reviewText)
                        reviewText = ""
                        reviewExpanded = false
                    },
                    enabled = reviewText.isNotBlank(),
                ) { Text("Submit") }
            }
        }
    }
}

@Composable
private fun ViewingRow(viewing: Viewing) {
    Column {
        Text(viewing.date ?: "Before joining", style = MaterialTheme.typography.bodyMedium)
        viewing.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private class TitleDetailViewModelFactory(
    private val repository: LibraryRepository,
    private val titleId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TitleDetailViewModel(repository, titleId) as T
}
