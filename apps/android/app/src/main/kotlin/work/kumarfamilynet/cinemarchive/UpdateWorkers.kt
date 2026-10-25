package work.kumarfamilynet.cinemarchive

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.model.ApkInstallState
import work.kumarfamilynet.cinemarchive.core.model.UpdateCheckResult
import work.kumarfamilynet.cinemarchive.core.model.UpdateDownloadPolicy
import work.kumarfamilynet.cinemarchive.data.UpdateVerificationException

/**
 * Schedules the background halves of the updater on WorkManager, so they honour network
 * constraints and survive the process being killed:
 *
 * - a daily [UpdateCheckWorker], for users who rarely open the app;
 * - an [UpdateDownloadWorker] per found update, constrained to an unmetered network under the
 *   default Wi-Fi-only policy.
 */
object UpdateScheduler {
    private const val PERIODIC_CHECK = "update_check_daily"
    private const val DOWNLOAD = "update_download"

    /** Idempotent: keeps an existing schedule rather than restarting its clock each launch. */
    fun schedulePeriodicCheck(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(1, TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_CHECK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelPeriodicCheck(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_CHECK)
    }

    /** Queues the background download, waiting for a network [policy] allows. */
    fun enqueueDownload(context: Context, policy: UpdateDownloadPolicy) {
        val network = if (policy == UpdateDownloadPolicy.ANY_NETWORK) NetworkType.CONNECTED else NetworkType.UNMETERED
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .build()
        // KEEP: a download already queued or running for this update carries on.
        WorkManager.getInstance(context).enqueueUniqueWork(DOWNLOAD, ExistingWorkPolicy.KEEP, request)
    }
}

/** The daily background check. Finding an update hands off to the shared automatic path. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CinemArchiveApplication
        app.runAutomaticUpdateCheck(fromBackground = true)
        // A failed check (offline, rate limited) simply waits for tomorrow's run.
        return Result.success()
    }
}

/**
 * Downloads a found update in the background and announces it as ready. Stops at "ready": the
 * install always waits for a tap, because the system can block its prompt from the background.
 */
class UpdateDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CinemArchiveApplication
        val release = app.appUpdateController.check() as? UpdateCheckResult.Available ?: return Result.success()
        if (release.apkUrl == null || !app.apkInstaller.canRequestInstalls()) return Result.success()

        val outcome = coroutineScope {
            val progress = launch { postThrottledProgress(app, release.latestVersion) }
            app.apkInstaller.download(release).also { progress.cancel() }
        }
        return when (val state = app.apkInstaller.installState.value) {
            is ApkInstallState.Ready -> {
                UpdateInstallNotifier.postReady(app, state.version)
                Result.success()
            }
            is ApkInstallState.Failed -> {
                UpdateInstallNotifier.postFailed(app, state.message)
                // A dropped connection is worth another go (WorkManager backs off between
                // tries); a download that failed verification would only fail again.
                val error = outcome.exceptionOrNull()
                val retryable = error is java.io.IOException && error !is UpdateVerificationException
                if (retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            }
            else -> {
                UpdateInstallNotifier.clear(app)
                Result.success()
            }
        }
    }

    private suspend fun postThrottledProgress(app: CinemArchiveApplication, version: String) {
        // The installer publishes progress every 100 ms for the in-app bar; Android rate-limits
        // notification updates and drops the excess, so repost at most once a second, and only
        // when the whole percentage moved.
        var lastPercent = -1
        var lastPostedAt = 0L
        app.apkInstaller.installState.filterIsInstance<ApkInstallState.Downloading>().collect { state ->
            val percent = state.fraction?.let { (it * 100).toInt() } ?: -1
            val now = System.currentTimeMillis()
            if (lastPostedAt == 0L || (percent != lastPercent && now - lastPostedAt >= 1_000)) {
                lastPercent = percent
                lastPostedAt = now
                UpdateInstallNotifier.postDownloading(app, version, state.fraction)
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
