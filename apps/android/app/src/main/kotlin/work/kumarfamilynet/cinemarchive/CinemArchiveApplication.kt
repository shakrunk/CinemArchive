package work.kumarfamilynet.cinemarchive

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import work.kumarfamilynet.cinemarchive.core.model.ApkInstallState
import work.kumarfamilynet.cinemarchive.core.model.InstallSource
import work.kumarfamilynet.cinemarchive.core.model.UpdateCheckResult
import work.kumarfamilynet.cinemarchive.core.model.UpdateDownloadPolicy
import work.kumarfamilynet.cinemarchive.data.AccountRuntimeManager
import work.kumarfamilynet.cinemarchive.data.ApkInstaller
import work.kumarfamilynet.cinemarchive.data.AppUpdateController
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

    /** Share viewing always uses only the public apikey, even while an owner is signed in. */
    val sharedLibraryRepository: work.kumarfamilynet.cinemarchive.data.SharingRepository by lazy {
        work.kumarfamilynet.cinemarchive.data.SharingRepository(supabaseClient, { null })
    }

    /** Public Discover browsing; it works signed-out (anon key) so it is deliberately not part
     *  of any account runtime. */
    val discoverRepository: DiscoverRepository by lazy { DiscoverRepository(supabaseClient, authRepository) }

    /** Device-level UI preferences (theme, fonts, grid density): not account data. */
    val preferencesRepository: PreferencesRepository by lazy { PreferencesRepository(this) }

    private val installSourceProvider: InstallSourceProvider by lazy { InstallSourceProvider(this) }

    val appUpdateRepository: AppUpdateRepository by lazy {
        val feedOverride = BuildConfig.UPDATE_FEED_URL.takeIf { it.isNotBlank() }
        if (feedOverride == null) {
            AppUpdateRepository(installSourceProvider, BuildConfig.VERSION_NAME)
        } else {
            AppUpdateRepository(installSourceProvider, BuildConfig.VERSION_NAME, latestReleaseUrl = feedOverride)
        }
    }

    /** The one shared update check every surface reads; see [AppUpdateController]. */
    val appUpdateController: AppUpdateController by lazy {
        AppUpdateController(fetch = appUpdateRepository::checkForUpdate)
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
                    preferences = preferencesRepository,
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

    // Activities currently resumed. The install receiver reads this: a confirmation prompt
    // can only be launched while the app is in the foreground.
    private var resumedActivities = 0

    /** Whether one of this app's activities is resumed right now. Main thread only. */
    val isInForeground: Boolean get() = resumedActivities > 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumedActivities++ }
            override fun onActivityPaused(activity: Activity) { resumedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) {
                // The first activity start of a process is the user opening the app: check
                // then, rather than on every process start (alarms and the boot receiver start
                // the process too, and shouldn't spend the GitHub rate limit or the battery).
                if (!checkedOnOpen) {
                    checkedOnOpen = true
                    applicationScope.launch { runAutomaticUpdateCheck(fromBackground = false) }
                }
            }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // Bring up (only) the stored session's owner; everything account-scoped — sync, outing
        // reconciliation, outbox flush, Ledger-layout reconcile — now starts inside that runtime.
        accountRuntimeManager.start()
        applicationScope.launch {
            // Before any check: clears the staged APK once this build has caught up with it,
            // along with the "ready to install" notification that announced it.
            if (apkInstaller.reconcileAfterLaunch()) UpdateInstallNotifier.clear(this@CinemArchiveApplication)
            restoreStagedUpdates()
        }
        applicationScope.launch { keepPeriodicCheckScheduled() }
    }

    private var checkedOnOpen = false

    /** Keeps the daily background check scheduled exactly while automatic checks are on. */
    private suspend fun keepPeriodicCheckScheduled() {
        if (appUpdateRepository.installSource == InstallSource.PLAY_STORE) return
        preferencesRepository.observeAutoCheckUpdates().collect { enabled ->
            if (enabled) {
                UpdateScheduler.schedulePeriodicCheck(this@CinemArchiveApplication)
            } else {
                UpdateScheduler.cancelPeriodicCheck(this@CinemArchiveApplication)
            }
        }
    }

    /**
     * Whenever a check finds an update that an earlier process already downloaded, offer the
     * install straight away instead of downloading it again.
     */
    private suspend fun restoreStagedUpdates() {
        appUpdateController.state
            .filterIsInstance<UpdateCheckResult.Available>()
            .collect { apkInstaller.restoreIfStaged(it.latestVersion) }
    }

    /**
     * The automatic update check — on opening the app, and daily from [UpdateCheckWorker].
     * Sideloaded-install analogue of Play's own background auto-update (issue #166);
     * Play-installed builds are untouched (see #147/AppUpdateRepository.checkForUpdate's own
     * gate).
     *
     * A found update is downloaded in the background when the download policy allows it and the
     * install permission is granted, via [UpdateDownloadWorker] so the policy's network
     * constraint applies. It stops at "ready": installing always waits for a tap, since Android
     * can block the system's prompt from the background. Otherwise a check that ran with the app
     * in the background announces the update in a notification; in the foreground the banner
     * already does.
     */
    suspend fun runAutomaticUpdateCheck(fromBackground: Boolean) {
        if (appUpdateRepository.installSource == InstallSource.PLAY_STORE) return
        if (!preferencesRepository.observeAutoCheckUpdates().first()) return

        val result = appUpdateController.check() as? UpdateCheckResult.Available ?: return
        val policy = preferencesRepository.observeUpdateDownloadPolicy().first()
        val canDownload = result.apkUrl != null && apkInstaller.canRequestInstalls()
        when {
            apkInstaller.installState.value is ApkInstallState.Ready -> Unit
            canDownload && policy != UpdateDownloadPolicy.NOTIFY_ONLY ->
                UpdateScheduler.enqueueDownload(this, policy)
            // Daily runs would otherwise repeat the same announcement; a dismissed banner means
            // the user has seen this version.
            fromBackground && preferencesRepository.observeDismissedUpdateVersion().first() != result.latestVersion ->
                UpdateInstallNotifier.postAvailable(this, result.latestVersion)
        }
    }
}
