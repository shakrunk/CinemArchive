package work.kumarfamilynet.cinemarchive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import work.kumarfamilynet.cinemarchive.data.JellyfinConfirmResult
import work.kumarfamilynet.cinemarchive.data.rethrowIfCancelled

/**
 * Schedules the periodic Jellyfin check on WorkManager. It exists only while a Jellyfin account
 * is connected. WorkManager's periodic floor is 15 minutes, so background polling is slower than
 * the web app's five; the app also checks whenever it opens and from Import & sync.
 */
object JellyfinScheduler {
    private const val PERIODIC_CHECK = "jellyfin_watch_check"

    fun update(context: Context, connected: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!connected) {
            manager.cancelUniqueWork(PERIODIC_CHECK)
            return
        }
        val request = PeriodicWorkRequestBuilder<JellyfinCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // Idempotent: keeps an existing schedule rather than restarting its clock each launch.
        manager.enqueueUniquePeriodicWork(PERIODIC_CHECK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun enqueueAnswer(context: Context, action: String, ownerId: String, playKey: String) {
        val request = OneTimeWorkRequestBuilder<JellyfinAnswerWorker>()
            .setConstraints(
                // Dismissing is local; only Add needs the network (TMDB lookup and catalog fetch).
                Constraints.Builder().setRequiredNetworkType(if (action == JellyfinActionReceiver.ACTION_ADD) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED).build(),
            )
            .setInputData(workDataOf(JellyfinAnswerWorker.KEY_ACTION to action, JellyfinAnswerWorker.KEY_OWNER to ownerId, JellyfinAnswerWorker.KEY_PLAY to playKey))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("jellyfin_answer:$action:$playKey", ExistingWorkPolicy.KEEP, request)
    }
}

/** Polls the server and announces each newly queued play once. No-op when signed out. */
class JellyfinCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CinemArchiveApplication
        val runtime = app.awaitRuntime(null) ?: return Result.success()
        runtime.runOwned { runtime.checkJellyfinAndNotify() }
        // An unreachable server (away from home, asleep) just waits for the next run.
        return Result.success()
    }
}

/** Handles a tap on a prompt's Add / Dismiss action. */
class JellyfinAnswerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val action = inputData.getString(KEY_ACTION) ?: return Result.success()
        val ownerId = inputData.getString(KEY_OWNER) ?: return Result.success()
        val key = inputData.getString(KEY_PLAY) ?: return Result.success()
        val app = applicationContext as CinemArchiveApplication
        // Only ever acts for the account the prompt was posted for.
        val runtime = app.awaitRuntime(ownerId) ?: return Result.success()
        runtime.runOwned {
            val watch = runtime.syncServices.jellyfin
            val play = watch.pending().firstOrNull { it.key == key }
            if (play == null) {
                JellyfinNotifier.cancel(app, runtime, key)
            } else if (action == JellyfinActionReceiver.ACTION_DISMISS) {
                watch.resolve(key)
                JellyfinNotifier.cancel(app, runtime, key)
            } else {
                try {
                    val result = runtime.syncServices.jellyfinConfirm.confirm(play)
                    watch.resolve(key)
                    when (result) {
                        JellyfinConfirmResult.Added -> JellyfinNotifier.postOutcome(app, runtime, key, "Added ${play.describe()} to your history.")
                        JellyfinConfirmResult.Duplicate -> JellyfinNotifier.postOutcome(app, runtime, key, "Already in your history.")
                        JellyfinConfirmResult.Unmatched -> JellyfinNotifier.postOutcome(app, runtime, key, "Couldn't match that to a title.")
                        is JellyfinConfirmResult.OpenAddFlow -> JellyfinNotifier.postAddFlow(app, runtime, key, result.match)
                    }
                } catch (e: Exception) {
                    e.rethrowIfCancelled()
                    // Keep it queued so the user can retry.
                    JellyfinNotifier.postPlays(app, runtime, listOf(play), note = "Couldn't add that watch. Try again.")
                }
            }
        }
        return Result.success()
    }

    companion object {
        const val KEY_ACTION = "action"
        const val KEY_OWNER = "owner"
        const val KEY_PLAY = "play"
    }
}

/** Notification action targets. Not exported: the broadcasts come from this app's own PendingIntents. */
class JellyfinActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.takeIf { it == ACTION_ADD || it == ACTION_DISMISS } ?: return
        val ownerId = intent.getStringExtra(OutingCompletionReceiver.EXTRA_OWNER_ID) ?: return
        val key = intent.getStringExtra(EXTRA_PLAY_KEY) ?: return
        JellyfinScheduler.enqueueAnswer(context, action, ownerId, key)
    }

    companion object {
        const val ACTION_ADD = "work.kumarfamilynet.cinemarchive.JELLYFIN_ADD"
        const val ACTION_DISMISS = "work.kumarfamilynet.cinemarchive.JELLYFIN_DISMISS"
        const val EXTRA_PLAY_KEY = "jellyfin_play_key"
    }
}
