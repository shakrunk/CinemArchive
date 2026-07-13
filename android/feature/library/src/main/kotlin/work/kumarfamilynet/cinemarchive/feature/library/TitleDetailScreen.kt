package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import work.kumarfamilynet.cinemarchive.core.model.EpisodeDetail
import work.kumarfamilynet.cinemarchive.core.model.SeasonDetail
import work.kumarfamilynet.cinemarchive.core.model.TitleDetail
import work.kumarfamilynet.cinemarchive.core.model.Viewing
import work.kumarfamilynet.cinemarchive.data.LibraryRepository

class TitleDetailViewModel(repository: LibraryRepository, titleId: String) : ViewModel() {
    val uiState = repository.observeTitleDetail(titleId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun TitleDetailRoute(repository: LibraryRepository, titleId: String, onBack: () -> Unit) {
    val viewModel: TitleDetailViewModel =
        viewModel(factory = TitleDetailViewModelFactory(repository, titleId))
    val detail by viewModel.uiState.collectAsStateWithLifecycle()
    TitleDetailScreen(detail, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleDetailScreen(detail: TitleDetail?, onBack: () -> Unit) {
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
            item { TitleHeader(detail) }

            if (detail.seasons.isNotEmpty()) {
                item { Text("Seasons", style = MaterialTheme.typography.titleMedium) }
                items(detail.seasons, key = SeasonDetail::id) { season -> SeasonRow(season) }
            }

            if (detail.viewings.isNotEmpty()) {
                item { Text("Viewing history", style = MaterialTheme.typography.titleMedium) }
                items(detail.viewings, key = Viewing::id) { viewing -> ViewingRow(viewing) }
            }
        }
    }
}

@Composable
private fun TitleHeader(detail: TitleDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        detail.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodyMedium) }
        detail.director?.let { Text("Directed by $it", style = MaterialTheme.typography.bodyMedium) }
        detail.network?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        detail.synopsis?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        if (detail.genres.isNotEmpty()) {
            Text(detail.genres.joinToString(" · "), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SeasonRow(season: SeasonDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Season ${season.seasonNumber} — ${season.episodesWatched}/${season.episodeCount} watched",
            style = MaterialTheme.typography.titleSmall,
        )
        season.episodes.forEach { episode -> EpisodeRow(episode) }
    }
}

@Composable
private fun EpisodeRow(episode: EpisodeDetail) {
    val watched = episode.watchCount > 0
    val label = buildString {
        append("${episode.episodeNumber}. ${episode.episodeName ?: "Untitled"}")
        if (watched) append(" — watched ${episode.watchCount}×")
        episode.latestRating?.let { append(" — ★$it") }
    }
    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
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
