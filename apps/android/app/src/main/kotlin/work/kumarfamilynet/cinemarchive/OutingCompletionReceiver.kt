package work.kumarfamilynet.cinemarchive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.model.OutingTransition

/**
 * Fires when the exact alarm armed by [AndroidOutingAlarmScheduler] goes off — runs the local
 * completion engine and posts the real "how was it?" notification per completed outing (the
 * Android-only payoff of docs/superpowers/plans/2026-07-21-android-cinema-outings.md §6). Also
 * the `BOOT_COMPLETED` re-arm target, since exact alarms don't survive a reboot.
 */
class OutingCompletionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as CinemArchiveApplication
        // An alarm armed before owner-scoping existed (no extra) or for a different account than
        // the one signed in now does nothing — it must never run against, or notify, another user.
        val ownerId = intent.getStringExtra(EXTRA_OWNER_ID) ?: return
        val pendingResult = goAsync()
        // This detached scope only keeps goAsync() alive. ALL real work (DB, poster fetch, posting)
        // runs inside the account runtime's own scope, so closing the runtime on sign-out / switch
        // cancels it, and the final post is re-checked against the exact sign-in.
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val runtime = app.awaitRuntime(ownerId) ?: return@launch
                runtime.runOwned {
                    runtime.completeDueOutings().forEach { postCompletionNotification(context, runtime, it) }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Runtime closed mid-flight: nothing may be posted for that account.
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun postCompletionNotification(context: Context, runtime: AppAccountRuntime, transition: OutingTransition) {
        val ownerId = runtime.ownerId
        ensureChannel(context)
        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val openIntent = Intent(context, MainActivity::class.java)
            .setPackage(context.packageName)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(MainActivity.EXTRA_OPEN_TITLE_ID, transition.titleId)
            .putExtra(EXTRA_OWNER_ID, ownerId)
        val pendingIntent = PendingIntent.getActivity(
            context,
            transition.outingId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val posterBitmap = loadPosterBitmap(context, transition.posterUrl)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("${transition.titleName} just let out")
            .setContentText("How was it?")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (posterBitmap != null) {
            // Same bitmap for both: the collapsed-state corner thumbnail (setLargeIcon) and
            // the expanded-state hero image (BigPictureStyle) — the framework hides the
            // corner thumbnail automatically once expanded, no bigLargeIcon() override needed.
            builder.setLargeIcon(posterBitmap)
            builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(posterBitmap))
        }
        // The poster fetch above can take seconds — re-check the exact sign-in at the last moment,
        // atomically with respect to the runtime's teardown (which cancels this account's posts).
        runtime.postIfCurrent {
            NotificationManagerCompat.from(context)
                .notify(AppAccountRuntime.notificationTag(ownerId), transition.outingId.hashCode(), builder.build())
        }
    }

    /** Best-effort poster fetch for the notification — a network hiccup or missing poster
     *  degrades to the plain text notification rather than losing the "how was it?" prompt
     *  entirely. [ImageRequest.allowHardware] is disabled because [Bitmap]s destined for
     *  [android.app.Notification.Builder.setLargeIcon]/`BigPictureStyle` must be software
     *  bitmaps — a hardware bitmap can't be parceled into the system notification process. */
    private suspend fun loadPosterBitmap(context: Context, posterUrl: String?): Bitmap? {
        if (posterUrl.isNullOrBlank()) return null
        return try {
            val request = ImageRequest.Builder(context)
                .data(posterUrl)
                .allowHardware(false)
                .build()
            (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
        } catch (e: Exception) {
            null
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Cinema outings", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Lets you know when a scheduled cinema trip has ended, so you can rate it."
            },
        )
    }

    companion object {
        /** The account an alarm/notification was created for; consumers ignore a mismatch. */
        const val EXTRA_OWNER_ID = "owner_user_id"

        private const val CHANNEL_ID = "cinema_outings"
    }
}
