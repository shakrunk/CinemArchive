package work.kumarfamilynet.cinemarchive

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.data.DevFixtureSeed
import work.kumarfamilynet.cinemarchive.data.LibraryRepository
import work.kumarfamilynet.cinemarchive.data.MutationOutbox
import work.kumarfamilynet.cinemarchive.data.UnconfiguredRemoteMutationWriter

class CinemArchiveApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database: LibraryDatabase by lazy { LibraryDatabase.create(this) }

    // UnconfiguredRemoteMutationWriter until a real Supabase client + auth session exist
    // (blocked on a physical device — see docs/android-implementation-status.md). Queued
    // mutations stay durable in Room regardless; only the push itself is a no-op for now.
    private val outbox: MutationOutbox by lazy {
        MutationOutbox(database.outboxDao(), UnconfiguredRemoteMutationWriter())
    }

    val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(
            titleDao = database.titleDao(),
            seasonDao = database.seasonDao(),
            episodeDao = database.episodeDao(),
            watchEventDao = database.episodeWatchEventDao(),
            ratingDao = database.episodeRatingDao(),
            reviewDao = database.episodeReviewDao(),
            viewingDao = database.viewingDao(),
            outbox = outbox,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Temporary — see DevFixtureSeed kdoc. Replaced by real sync once
        // docs/android-sync-contract.md's sync_library_changes RPC exists.
        applicationScope.launch { DevFixtureSeed.seedIfEmpty(database) }
        applicationScope.launch { outbox.flush() }
    }
}
