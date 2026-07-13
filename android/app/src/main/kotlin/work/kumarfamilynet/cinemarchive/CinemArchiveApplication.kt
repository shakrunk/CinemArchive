package work.kumarfamilynet.cinemarchive

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.data.DevFixtureSeed
import work.kumarfamilynet.cinemarchive.data.LibraryRepository

class CinemArchiveApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database: LibraryDatabase by lazy { LibraryDatabase.create(this) }

    val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(
            titleDao = database.titleDao(),
            seasonDao = database.seasonDao(),
            episodeDao = database.episodeDao(),
            watchEventDao = database.episodeWatchEventDao(),
            ratingDao = database.episodeRatingDao(),
            viewingDao = database.viewingDao(),
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Temporary — see DevFixtureSeed kdoc. Replaced by real sync once
        // docs/android-sync-contract.md's sync_library_changes RPC exists.
        applicationScope.launch { DevFixtureSeed.seedIfEmpty(database) }
    }
}
