package work.kumarfamilynet.cinemarchive

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.model.ApkInstallState
import work.kumarfamilynet.cinemarchive.core.model.InstallSource
import work.kumarfamilynet.cinemarchive.core.model.UpdateCheckResult
import work.kumarfamilynet.cinemarchive.data.ApkInstaller
import work.kumarfamilynet.cinemarchive.data.AppUpdateRepository
import work.kumarfamilynet.cinemarchive.data.AuthRepository
import work.kumarfamilynet.cinemarchive.data.DiscoverRepository
import work.kumarfamilynet.cinemarchive.data.InstallSourceProvider
import work.kumarfamilynet.cinemarchive.data.LedgerLayoutRepository
import work.kumarfamilynet.cinemarchive.data.LedgerRepository
import work.kumarfamilynet.cinemarchive.data.LibraryRepository
import work.kumarfamilynet.cinemarchive.data.LibrarySyncRepository
import work.kumarfamilynet.cinemarchive.data.ListsRepository
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

    val discoverRepository: DiscoverRepository by lazy { DiscoverRepository(supabaseClient, authRepository) }

    val preferencesRepository: PreferencesRepository by lazy { PreferencesRepository(this) }

    private val installSourceProvider: InstallSourceProvider by lazy { InstallSourceProvider(this) }

    val appUpdateRepository: AppUpdateRepository by lazy {
        AppUpdateRepository(installSourceProvider, BuildConfig.VERSION_NAME)
    }

    val apkInstaller: ApkInstaller by lazy { ApkInstaller(this) }

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
            cinemaOutingDao = database.cinemaOutingDao(),
            titleCastDao = database.titleCastDao(),
            titleCrewDao = database.titleCrewDao(),
            listDao = database.listDao(),
            listItemDao = database.listItemDao(),
        )
    }

    val listsRepository: ListsRepository by lazy {
        ListsRepository(
            listDao = database.listDao(),
            listItemDao = database.listItemDao(),
            outbox = outbox,
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
            titleCastDao = database.titleCastDao(),
            titleCrewDao = database.titleCrewDao(),
            theaterInterestDao = database.theaterInterestDao(),
            outbox = outbox,
            episodeMetadataFetcher = discoverRepository,
        )
    }

    val outingsRepository: OutingsRepository by lazy {
        OutingsRepository(
            cinemaOutingDao = database.cinemaOutingDao(),
            viewingDao = database.viewingDao(),
            titleDao = database.titleDao(),
            outbox = outbox,
            venueNoteDao = database.venueNoteDao(),
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
            episodeDao = database.episodeDao(),
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
        // Ledger layout pull-on-sign-in/launch (docs/superpowers/plans/2026-07-23-android-
        // ledger-parity.md Phase A, ledger.md §4). observeSession() is a StateFlow, so
        // collecting it immediately replays whatever session is already stored — that single
        // subscription covers both "already signed in at cold start" and "just signed in
        // during this process's lifetime" without a second call site. distinctUntilChanged on
        // userId (not the whole session) skips token-refresh emissions, which share a userId.
        applicationScope.launch {
            authRepository.observeSession()
                .map { it?.userId }
                .distinctUntilChanged()
                .filterNotNull()
                .collect { ledgerLayoutRepository.reconcile() }
        }
        applicationScope.launch { checkAndInstallUpdateIfDue() }
    }

    /**
     * Sideloaded-install analogue of Play's own background auto-update (issue #166) — without
     * this, "Automatically check for updates" only ever ran when Settings → About happened to be
     * open (AboutScreen.kt's `LaunchedEffect(autoCheck)`), so granting the install permission and
     * turning the toggle on still required remembering to open that screen. Play-installed
     * builds are untouched (see #147/AppUpdateRepository.checkForUpdate's own gate); without the
     * install permission granted, this changes nothing from #146's original behavior — no silent
     * download, the user still has to open About and tap through manually.
     */
    private suspend fun checkAndInstallUpdateIfDue() {
        if (appUpdateRepository.installSource == InstallSource.PLAY_STORE) return
        if (!preferencesRepository.observeAutoCheckUpdates().first()) return

        val result = appUpdateRepository.checkForUpdate()
        val apkUrl = (result as? UpdateCheckResult.Available)?.apkUrl ?: return
        if (!apkInstaller.canRequestInstalls()) return

        // installState's decisive transitions (AwaitingConfirmation/Installed/Failed) arrive
        // later, off InstallStatusReceiver's broadcast — this has to keep collecting past
        // downloadAndInstall() returning (which only means the session was committed), and
        // stop itself once a terminal state lands rather than being cancelled from outside.
        coroutineScope {
            var downloadStarted = false
            val notifyJob = launch {
                apkInstaller.installState
                    .onEach { state ->
                        when (state) {
                            ApkInstallState.Downloading -> {
                                downloadStarted = true
                                UpdateInstallNotifier.postDownloading(this@CinemArchiveApplication, result.latestVersion)
                            }
                            ApkInstallState.AwaitingConfirmation -> UpdateInstallNotifier.postAwaitingConfirmation(this@CinemArchiveApplication)
                            ApkInstallState.Installed -> UpdateInstallNotifier.clear(this@CinemArchiveApplication)
                            is ApkInstallState.Failed -> UpdateInstallNotifier.postFailed(this@CinemArchiveApplication, state.message)
                            ApkInstallState.Idle -> Unit
                        }
                    }
                    // Stops once a terminal state lands. Idle only counts as terminal after a
                    // download started — the initial subscribe-time replay of the StateFlow's
                    // starting value is also Idle, and must not stop this before it begins.
                    .takeWhile { state ->
                        state !is ApkInstallState.Failed && state != ApkInstallState.Installed &&
                            !(state == ApkInstallState.Idle && downloadStarted)
                    }
                    .collect()
            }
            apkInstaller.downloadAndInstall(apkUrl)
            notifyJob.join()
        }
    }
}
