package work.kumarfamilynet.cinemarchive.feature.ledger

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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import work.kumarfamilynet.cinemarchive.core.model.LedgerBoard
import work.kumarfamilynet.cinemarchive.core.model.LedgerCategoryCount
import work.kumarfamilynet.cinemarchive.core.model.LedgerStats
import work.kumarfamilynet.cinemarchive.core.model.LedgerWatchlistEntry
import work.kumarfamilynet.cinemarchive.data.LedgerRepository

/**
 * The hero stat ribbon ([LedgerStats]) plus the four simplest additional widgets
 * ([LedgerBoard] kdoc explains why those four specifically). The full 20-widget
 * customizable board (docs/android-contracts/ledger.md) is a separate, larger workstream
 * that also needs `user_prefs.ledger_layout` sync, which isn't wired up yet.
 */
data class LedgerUiState(val stats: LedgerStats, val board: LedgerBoard)

class LedgerViewModel(repository: LedgerRepository) : ViewModel() {
    val uiState = combine(repository.observeLedgerStats(), repository.observeLedgerBoard(), ::LedgerUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun LedgerRoute(repository: LedgerRepository, onBack: () -> Unit) {
    val viewModel: LedgerViewModel = viewModel(factory = LedgerViewModelFactory(repository))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LedgerScreen(uiState, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(uiState: LedgerUiState?, onBack: () -> Unit = {}) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("The Ledger") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.headlineSmall)
                    }
                },
            )
        },
    ) { innerPadding ->
        if (uiState == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val (stats, board) = uiState

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(innerPadding),
        ) {
            item { StatRow("Movies", stats.totalMovies.toString()) }
            item { StatRow("Series", stats.totalSeries.toString()) }
            item { StatRow("Viewings logged", stats.totalViewings.toString()) }
            item { StatRow("Average rating", stats.averageRating?.let { "★%.1f".format(it) } ?: "—") }
            item { StatRow("Movie minutes watched", stats.totalWatchedMovieMinutes.toString()) }

            item { SectionHeader("Feature Lengths") }
            items(board.runtimeBuckets, key = { "runtime-${it.label}" }) { CategoryRow(it) }

            if (board.networks.isNotEmpty()) {
                item { SectionHeader("On the Air") }
                items(board.networks, key = { "network-${it.label}" }) { CategoryRow(it) }
            }

            if (board.decades.isNotEmpty()) {
                item { SectionHeader("By the Era") }
                items(board.decades, key = { "decade-${it.label}" }) { CategoryRow(it) }
            }

            item {
                SectionHeader(
                    if (board.watchlistMovieMinutesOwed > 0) {
                        "Coming Attractions — ${board.watchlistMovieMinutesOwed} movie minutes owed"
                    } else {
                        "Coming Attractions"
                    },
                )
            }
            if (board.watchlist.isEmpty()) {
                item { Text("Nothing on the watchlist.", style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(board.watchlist, key = LedgerWatchlistEntry::titleId) { WatchlistRow(it) }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun CategoryRow(category: LedgerCategoryCount) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(category.label, style = MaterialTheme.typography.bodyMedium)
        Text(category.count.toString(), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WatchlistRow(entry: LedgerWatchlistEntry) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(entry.title, style = MaterialTheme.typography.bodyMedium)
        entry.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodyMedium) }
    }
}

private class LedgerViewModelFactory(
    private val repository: LedgerRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LedgerViewModel(repository) as T
}
