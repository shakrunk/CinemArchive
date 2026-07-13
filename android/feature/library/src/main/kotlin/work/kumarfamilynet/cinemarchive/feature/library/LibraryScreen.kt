package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import work.kumarfamilynet.cinemarchive.core.model.LibraryTitle
import work.kumarfamilynet.cinemarchive.data.LibraryRepository

data class LibraryUiState(val titles: List<LibraryTitle> = emptyList())

class LibraryViewModel(repository: LibraryRepository) : ViewModel() {
    val uiState = repository.observeLibrary()
        .map(::LibraryUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())
}

@Composable
fun LibraryRoute(repository: LibraryRepository, onTitleClick: (String) -> Unit) {
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModelFactory(repository))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LibraryScreen(uiState, onTitleClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(uiState: LibraryUiState, onTitleClick: (String) -> Unit = {}) {
    Scaffold(topBar = { TopAppBar(title = { Text("The Projection Room") }) }) { innerPadding ->
        if (uiState.titles.isEmpty()) {
            EmptyLibrary(modifier = Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(innerPadding),
            ) {
                items(uiState.titles, key = LibraryTitle::id) { title ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTitleClick(title.id) },
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(title.name, style = MaterialTheme.typography.titleLarge)
                            title.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
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
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(repository) as T
}
