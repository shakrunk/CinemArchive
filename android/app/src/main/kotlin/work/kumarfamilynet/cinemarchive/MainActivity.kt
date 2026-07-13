package work.kumarfamilynet.cinemarchive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.data.LibraryRepository
import work.kumarfamilynet.cinemarchive.feature.library.LibraryRoute
import work.kumarfamilynet.cinemarchive.feature.library.TitleDetailRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as CinemArchiveApplication).libraryRepository
        setContent {
            CinemArchiveTheme {
                Surface { CinemArchiveApp(repository) }
            }
        }
    }
}

/**
 * Two-screen nav via local state rather than androidx.navigation — sufficient for the
 * current Library/Title-detail surface; revisit once Phase 2 adds more top-level routes.
 */
@Composable
private fun CinemArchiveApp(repository: LibraryRepository) {
    var selectedTitleId by remember { mutableStateOf<String?>(null) }
    val currentTitleId = selectedTitleId

    if (currentTitleId == null) {
        LibraryRoute(repository, onTitleClick = { selectedTitleId = it })
    } else {
        TitleDetailRoute(repository, currentTitleId, onBack = { selectedTitleId = null })
    }
}
