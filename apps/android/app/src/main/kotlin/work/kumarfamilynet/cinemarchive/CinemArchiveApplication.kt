package work.kumarfamilynet.cinemarchive

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import work.kumarfamilynet.cinemarchive.core.model.ApkInstallState
import work.kumarfamilynet.cinemarchive.core.model.InstallSource
import work.kumarfamilynet.cinemarchive.core.model.UpdateCheckResult
import work.kumarfamilynet.cinemarchive.data.AccountRuntimeManager
import work.kumarfamilynet.cinemarchive.data.ApkInstaller
import work.kumarfamilynet.cinemarchive.data.AppUpdateRepository
import work.kumarfamilynet.cinemarchive.data.AuthRepository
import work.kumarfamilynet.cinemarchive.data.DiscoverRepository
import work.kumarfamilynet.cinemarchive.data.InstallSourceProvider
import work.kumarfamilynet.cinemarchive.data.PreferencesRepository
import work.kumarfamilynet.cinemarchive.data.SupabaseRestClient
import work.kumarfamilynet.cinemarchive.data.visibleRuntime

class CinemArchiveApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val supabaseClient: SupabaseRestClient by lazy {
        SupabaseRestClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY)
    }

    val authRepository: AuthRepository by lazy { AuthRepository(this, supabaseClient) }

    /** Public Discover browsing; it works signed-out (anon key) so it is deliberately not part
     *  of any account runtime. */
    val discoverRepository: DiscoverRepository by lazy { DiscoverRepository(supabaseClient, authRepository) }

    /** Device-level UI preferences (theme, fonts, grid density): not account data. */
    val preferencesRepository: PreferencesRepository by lazy { PreferencesRepository(this) }

    private val installSourceProvider: InstallSourceProvider by lazy { InstallSourceProvider(this) }

    val appUpdateRepository: AppUpdateRepository by lazy {
        AppUpdateRepository(installSourceProvider, BuildConfig.VERSION_NAME)
    }

    val apkInstaller: ApkInstaller by lazy { ApkInstaller(this) }

    /** Stable random Plex client id: identifies this install, not an account or a credential. */
    private val plexClientId: String by lazy {
        val prefs = getSharedPreferences("sync", MODE_PRIVATE)
        prefs.getString("plex_client_id", null)
            ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("plex_client_id", it).apply() }
    }

    /**
     * The only way to reach account data: one fully-isolated [AppAccountRuntime] per sign-in
     * (own Room file, DataStores, outbox, repositories, jobs), rebuilt on every sign-in /
     * sign-out / account switch. There is deliberately no app-wide database or repository left
     * here, and the legacy global `cinemarchive.db` is never opened.
     */
    val accountRuntimeManager: AccountRuntimeManager<AppAccountRuntime> by lazy {
        AccountRuntimeManager(
            identity = authRepository.observeIdentity(),
            scope = applicationScope,
            factory = { identity ->
                AppAccountRuntime(
                    context = this,
                    appScope = applicationScope,
                    identity = identity,
                    auth = authRepository,
                    client = supabaseClient,
                    discoverRepository = discoverRepository,
                    plexClientId = plexClientId,
                ).also { it.startBackgroundWork() }
            },
        )
    }

    /** The live runtime for [ownerId] (or whichever account is signed in when null), waiting
     *  briefly for a cold-start build — for receivers/alarms that may fire before the UI. Null
     *  if that account isn't the one signed in, so a stale alarm can't act for someone else. */
    suspend fun awaitRuntime(ownerId: String?): AppAccountRuntime? {
        val signedIn = authRepository.observeIdentity().value?.userId ?: return null
        if (ownerId != null && ownerId != signedIn) return null
        // Exact sign-in match (user id AND generation), re-evaluated on every change of either flow:
        // never returns a runtime that auth has already moved past, nor one the manager hasn't caught up to.
        return withTimeoutOrNull(20_000) {
            kotlinx.coroutines.flow.combine(accountRuntimeManager.runtime, authRepository.observeIdentity()) { rt, id ->
                visibleRuntime(id, rt)?.takeIf { ownerId == null || it.ownerId == ownerId }
            }.filterNotNull().first()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Bring up (only) the stored session's owner; everything account-scoped — sync, outing
        // reconciliation, outbox flush, Ledger-layout reconcile — now starts inside that runtime.
        accountRuntimeManager.start()
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
