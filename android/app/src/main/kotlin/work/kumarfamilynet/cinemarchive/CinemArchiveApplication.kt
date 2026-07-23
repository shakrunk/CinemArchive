package work.kumarfamilynet.cinemarchive

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.data.AuthRepository
import work.kumarfamilynet.cinemarchive.data.LedgerLayoutRepository
import work.kumarfamilynet.cinemarchive.data.LedgerRepository
import work.kumarfamilynet.cinemarchive.data.LibraryRepository
import work.kumarfamilynet.cinemarchive.data.LibrarySyncRepository
import work.kumarfamilynet.cinemarchive.data.MutationOutbox
import work.kumarfamilynet.cinemarchive.data.OutingsRepository
import work.kumarfamilynet.cinemarchive.data.PreferencesRepository
import work.kumarfamilynet.cinemarchive.data.SupabaseLedgerLayoutWriter
import work.kumarfamilynet.cinemarchive.data.SupabaseRemoteMutationWriter
import work.kumarfamilynet.cinemarchive.data.SupabaseRestClient
import work.kumarfamilynet.cinemarchive.data.TitleConflictHandler

class CinemArchiveApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database: LibraryDatabase by lazy { LibraryDatabase.create(this) }

    private val supabaseClient: SupabaseRestClient by lazy {
        SupabaseRestClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY)
    }

    val authRepository: AuthRepository by lazy { AuthRepository(this, supabaseClient) }

    // Real writer now that AuthRepository can supply a session — pushes are a no-op
    // (PushResult.Retry, same as the old UnconfiguredRemoteMutationWriter) until the user
    // actually signs in, since sessionProvider throws and push() already catches that.
    private val outbox: MutationOutbox by lazy {
        MutationOutbox(
            database.outboxDao(),
            SupabaseRemoteMutationWriter(supabaseClient) { authRepository.currentSession() ?: error("Not signed in") },
            TitleConflictHandler(database.titleDao()),
        )
    }

    val preferencesRepository: PreferencesRepository by lazy { PreferencesRepository(this) }
    val ledgerLayoutRepository: LedgerLayoutRepository by lazy {
        LedgerLayoutRepository(this, authRepository, SupabaseLedgerLayoutWriter(supabaseClient))
    }

    // The read half of sync — sync_library_changes RPC pull, replacing DevFixtureSeed now
    // that a real session/writer exist. See this class's plan doc for why one RPC serves
    // both bootstrap and incremental sync.
    val librarySyncRepository: LibrarySyncRepository by lazy {
        LibrarySyncRepository(
            context = this,
            client = supabaseClient,
            authRepository = authRepository,
            titleDao = database.titleDao(),
            seasonDao = database.seasonDao(),
            episodeDao = database.episodeDao(),
            watchEventDao = database.episodeWatchEventDao(),
            ratingDao = database.episodeRatingDao(),
            reviewDao = database.episodeReviewDao(),
            viewingDao = database.viewingDao(),
        )
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
            cinemaOutingDao = database.cinemaOutingDao(),
            outbox = outbox,
        )
    }

    val outingsRepository: OutingsRepository by lazy {
        OutingsRepository(
            cinemaOutingDao = database.cinemaOutingDao(),
            viewingDao = database.viewingDao(),
            titleDao = database.titleDao(),
            outbox = outbox,
            alarmScheduler = AndroidOutingAlarmScheduler(this),
        )
    }

    val ledgerRepository: LedgerRepository by lazy {
        LedgerRepository(
            titleDao = database.titleDao(),
            viewingDao = database.viewingDao(),
            titleCastDao = database.titleCastDao(),
            titleCrewDao = database.titleCrewDao(),
            cinemaOutingDao = database.cinemaOutingDao(),
            watchEventDao = database.episodeWatchEventDao(),
            seasonDao = database.seasonDao(),
        )
    }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            librarySyncRepository.syncNow()
            // App-launch reconciliation trigger (docs/superpowers/plans/2026-07-21-android-
            // cinema-outings.md §5) — must run after sync so a trip completed on another
            // device already reflects locally before this pass decides what's due.
            outingsRepository.completeDueOutings()
        }
        applicationScope.launch { outbox.flush() }
    }
}
