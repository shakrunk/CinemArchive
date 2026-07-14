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
import work.kumarfamilynet.cinemarchive.data.LedgerRepository
import work.kumarfamilynet.cinemarchive.data.LibraryRepository
import work.kumarfamilynet.cinemarchive.data.PreferencesRepository
import work.kumarfamilynet.cinemarchive.feature.ledger.LedgerRoute
import work.kumarfamilynet.cinemarchive.feature.library.LibraryRoute
import work.kumarfamilynet.cinemarchive.feature.library.TitleDetailRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as CinemArchiveApplication).libraryRepository
        val ledgerRepository = (application as CinemArchiveApplication).ledgerRepository
        val preferencesRepository = (application as CinemArchiveApplication).preferencesRepository
        setContent {
            val themeMode by preferencesRepository.observeThemeMode()
                .collectAsStateWithLifecycle(initialValue = ArchiveThemeMode.DARK)
            val scope = rememberCoroutineScope()
            CinemArchiveTheme(mode = themeMode) {
                Surface {
                    CinemArchiveApp(
                        repository,
                        ledgerRepository,
                        themeMode = themeMode,
                        onCycleTheme = { scope.launch { preferencesRepository.setThemeMode(themeMode.next()) } },
                    )
                }
            }
        }
    }
}

private sealed interface Screen {
    data object Library : Screen
    data class TitleDetail(val titleId: String) : Screen
    data object Ledger : Screen
}

/**
 * Nav via local state rather than androidx.navigation — sufficient for this still-small
 * (three-screen) surface; revisit once more top-level routes arrive (Phase 4's sharing/
 * social/notifications surfaces).
 */
@Composable
private fun CinemArchiveApp(
    repository: LibraryRepository,
    ledgerRepository: LedgerRepository,
    themeMode: ArchiveThemeMode,
    onCycleTheme: () -> Unit,
) {
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }

    when (val current = screen) {
        is Screen.Library -> LibraryRoute(
            repository,
            themeMode,
            onCycleTheme,
            onTitleClick = { screen = Screen.TitleDetail(it) },
            onOpenLedger = { screen = Screen.Ledger },
        )
        is Screen.TitleDetail -> TitleDetailRoute(repository, current.titleId, onBack = { screen = Screen.Library })
        is Screen.Ledger -> LedgerRoute(ledgerRepository, onBack = { screen = Screen.Library })
    }
}
