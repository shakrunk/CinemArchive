package work.kumarfamilynet.cinemarchive

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import work.kumarfamilynet.cinemarchive.core.model.ApkInstallState
import work.kumarfamilynet.cinemarchive.core.model.InstallSource
import work.kumarfamilynet.cinemarchive.core.model.UpdateCheckResult
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
        AppUpdateRepository(installSourceProvider, BuildConfig.VERSION_NAME)
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
            override fun onActivityStarted(activity: Activity) = Unit
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
        applicationScope.launch { checkAndDownloadUpdateIfDue() }
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
     * Sideloaded-install analogue of Play's own background auto-update (issue #166) — without
     * this, "Automatically check for updates" only ever ran when Settings → About happened to be
     * open, so granting the install permission and turning the toggle on still required
     * remembering to open that screen. Play-installed builds are untouched (see
     * #147/AppUpdateRepository.checkForUpdate's own gate); without the install permission
     * granted, this changes nothing from #146's original behavior — no silent download.
     *
     * This only ever downloads. It stops at [ApkInstallState.Ready] and leaves the install to a
     * tap: it can run from a background process start (an alarm, a boot receiver), where
     * Android blocks the system's confirmation prompt, which used to strand the update in
     * "waiting for the system installer" with nothing on screen.
     */
    private suspend fun checkAndDownloadUpdateIfDue() {
        if (appUpdateRepository.installSource == InstallSource.PLAY_STORE) return
        if (!preferencesRepository.observeAutoCheckUpdates().first()) return

        val result = appUpdateController.check() as? UpdateCheckResult.Available ?: return
        if (result.apkUrl == null || !apkInstaller.canRequestInstalls()) return

        coroutineScope {
            val progressJob = launch {
                // The installer publishes progress every 100 ms for the in-app bar; Android
                // rate-limits notification updates and drops the excess, so repost at most once
                // a second, and only when the whole percentage moved.
                var lastPercent = -1
                var lastPostedAt = 0L
                apkInstaller.installState.filterIsInstance<ApkInstallState.Downloading>().collect { state ->
                    val percent = state.fraction?.let { (it * 100).toInt() } ?: -1
                    val now = System.currentTimeMillis()
                    if (lastPostedAt == 0L || (percent != lastPercent && now - lastPostedAt >= 1_000)) {
                        lastPercent = percent
                        lastPostedAt = now
                        UpdateInstallNotifier.postDownloading(this@CinemArchiveApplication, result.latestVersion, state.fraction)
                    }
                }
            }
            apkInstaller.download(result)
            progressJob.cancel()
            when (val state = apkInstaller.installState.value) {
                is ApkInstallState.Ready -> UpdateInstallNotifier.postReady(this@CinemArchiveApplication, state.version)
                is ApkInstallState.Failed -> UpdateInstallNotifier.postFailed(this@CinemArchiveApplication, state.message)
                else -> UpdateInstallNotifier.clear(this@CinemArchiveApplication)
            }
        }
    }
}
