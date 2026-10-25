package work.kumarfamilynet.cinemarchive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Posts progress for an update found or downloaded in the background (issue #166). The
 * automatic path stops once the APK is staged, so the last word is always "ready — tap to
 * install", and every notification opens the update sheet, where that is one tap away.
 * Same permission-gated, best-effort pattern as [OutingCompletionReceiver]'s notification: if
 * `POST_NOTIFICATIONS` isn't granted, the download still proceeds, it's just silent.
 */
object UpdateInstallNotifier {
    private const val CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_ID = 9001

    /** [fraction] null shows an indeterminate bar (no size reported). */
    fun postDownloading(context: Context, versionName: String, fraction: Float?) =
        notify(context, "Downloading update", "CinemArchive $versionName is downloading.", ongoing = true) {
            setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            setOnlyAlertOnce(true)
        }

    fun postAvailable(context: Context, versionName: String) =
        notify(context, "Update available", "CinemArchive $versionName is available. Tap to see what's new.", ongoing = false)

    fun postReady(context: Context, versionName: String) =
        notify(context, "Update ready to install", "CinemArchive $versionName is downloaded. Tap to install.", ongoing = false)

    /** The way back in after an in-app update: Android closes the app and won't reopen it. */
    fun postUpdated(context: Context, versionName: String) =
        notify(context, "CinemArchive updated", "Version $versionName is installed. Tap to open.", ongoing = false, openUpdateSheet = false)

    /** The install prompt arrived after the user left the app; it reopens with them. */
    fun postReadyToConfirm(context: Context) =
        notify(context, "Confirm the update", "Open CinemArchive to finish installing the update.", ongoing = false)

    fun postFailed(context: Context, message: String) =
        notify(context, "Update download failed", message, ongoing = false)

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun notify(
        context: Context,
        title: String,
        text: String,
        ongoing: Boolean,
        // Every notification but "updated" is about an update still to act on.
        openUpdateSheet: Boolean = true,
        configure: NotificationCompat.Builder.() -> Unit = {},
    ) {
        ensureChannel(context)
        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent(context, openUpdateSheet))
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply(configure)
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    private fun openAppIntent(context: Context, openUpdateSheet: Boolean): PendingIntent =
        PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_UPDATE, openUpdateSheet)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_LOW).apply {
                description = "New versions found, and progress for updates downloaded in the background."
            },
        )
    }
}
