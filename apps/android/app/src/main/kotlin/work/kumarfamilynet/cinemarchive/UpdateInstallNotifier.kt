package work.kumarfamilynet.cinemarchive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Posts progress for an update download/install triggered automatically at app launch (issue
 * #166) — without this, the system's `PackageInstaller` confirmation dialog (raised the moment
 * the download finishes, by [InstallStatusReceiver]) could appear with no lead-up if the app has
 * since left the foreground. Same permission-gated, best-effort pattern as
 * [OutingCompletionReceiver]'s notification: if `POST_NOTIFICATIONS` isn't granted, the
 * download/install still proceeds, it's just silent.
 */
object UpdateInstallNotifier {
    private const val CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_ID = 9001

    fun postDownloading(context: Context, versionName: String) =
        notify(context, "Downloading update", "CinemArchive $versionName is downloading in the background.", ongoing = true)

    fun postAwaitingConfirmation(context: Context) =
        notify(context, "Update ready to install", "Open CinemArchive to confirm the install.", ongoing = false)

    fun postFailed(context: Context, message: String) =
        notify(context, "Update download failed", message, ongoing = false)

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun notify(context: Context, title: String, text: String, ongoing: Boolean) {
        ensureChannel(context)
        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress for updates downloaded and installed automatically in the background."
            },
        )
    }
}
