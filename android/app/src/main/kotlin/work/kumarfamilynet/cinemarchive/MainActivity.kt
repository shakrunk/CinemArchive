package work.kumarfamilynet.cinemarchive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.feature.library.LibraryRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as CinemArchiveApplication).libraryRepository
        setContent {
            CinemArchiveTheme {
                Surface { LibraryRoute(repository) }
            }
        }
    }
}
