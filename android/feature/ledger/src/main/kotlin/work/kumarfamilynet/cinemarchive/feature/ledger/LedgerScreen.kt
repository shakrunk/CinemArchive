package work.kumarfamilynet.cinemarchive.feature.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import work.kumarfamilynet.cinemarchive.core.model.LedgerStats
import work.kumarfamilynet.cinemarchive.data.LedgerRepository

/**
 * The Ledger's hero stat ribbon only — see [LedgerStats] kdoc. The full 20-widget
 * customizable board (docs/android-contracts/ledger.md) is a separate, larger workstream
 * that also needs `user_prefs.ledger_layout` sync, which isn't wired up yet.
 */
class LedgerViewModel(repository: LedgerRepository) : ViewModel() {
    val stats = repository.observeLedgerStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun LedgerRoute(repository: LedgerRepository, onBack: () -> Unit) {
    val viewModel: LedgerViewModel = viewModel(factory = LedgerViewModelFactory(repository))
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    LedgerScreen(stats, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(stats: LedgerStats?, onBack: () -> Unit = {}) {
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
        if (stats == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatRow("Movies", stats.totalMovies.toString())
            StatRow("Series", stats.totalSeries.toString())
            StatRow("Viewings logged", stats.totalViewings.toString())
            StatRow("Average rating", stats.averageRating?.let { "★%.1f".format(it) } ?: "—")
            StatRow("Movie minutes watched", stats.totalWatchedMovieMinutes.toString())
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

private class LedgerViewModelFactory(
    private val repository: LedgerRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LedgerViewModel(repository) as T
}
