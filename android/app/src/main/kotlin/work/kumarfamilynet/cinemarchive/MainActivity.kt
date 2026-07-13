package work.kumarfamilynet.cinemarchive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.core.model.ArchiveThemeMode
import work.kumarfamilynet.cinemarchive.data.LibraryRepository
import work.kumarfamilynet.cinemarchive.data.PreferencesRepository
import work.kumarfamilynet.cinemarchive.feature.library.LibraryRoute
import work.kumarfamilynet.cinemarchive.feature.library.TitleDetailRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as CinemArchiveApplication).libraryRepository
        val preferencesRepository = (application as CinemArchiveApplication).preferencesRepository
        setContent {
            val themeMode by preferencesRepository.observeThemeMode()
                .collectAsStateWithLifecycle(initialValue = ArchiveThemeMode.DARK)
            val scope = rememberCoroutineScope()
            CinemArchiveTheme(mode = themeMode) {
                Surface {
                    CinemArchiveApp(
                        repository,
                        themeMode = themeMode,
                        onCycleTheme = { scope.launch { preferencesRepository.setThemeMode(themeMode.next()) } },
                    )
                }
            }
        }
    }
}

/**
 * Two-screen nav via local state rather than androidx.navigation — sufficient for the
 * current Library/Title-detail surface; revisit once Phase 2 adds more top-level routes.
 */
@Composable
private fun CinemArchiveApp(
    repository: LibraryRepository,
    themeMode: ArchiveThemeMode,
    onCycleTheme: () -> Unit,
) {
    var selectedTitleId by remember { mutableStateOf<String?>(null) }
    val currentTitleId = selectedTitleId

    if (currentTitleId == null) {
        LibraryRoute(repository, themeMode, onCycleTheme, onTitleClick = { selectedTitleId = it })
    } else {
        TitleDetailRoute(repository, currentTitleId, onBack = { selectedTitleId = null })
    }
}
