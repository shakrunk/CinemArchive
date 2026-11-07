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
import work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.data.JellyfinPlay

/**
 * "Add to history?" notifications for new Jellyfin watches. Each play gets its own notification
 * with **Add** and **Dismiss** actions; nothing is written to the library until the user taps Add
 * (or answers in Settings → Import & sync). Same permission-gated, best-effort pattern as
 * [UpdateInstallNotifier]: if `POST_NOTIFICATIONS` isn't granted the plays stay queued and the
 * Import & sync screen shows them instead.
 *
 * Every notification is tagged with the account's tag and posted through
 * [AppAccountRuntime.postIfCurrent], so sign-out or an account switch removes them and a late
 * post can't land for the wrong account.
 */
object JellyfinNotifier {
    private const val CHANNEL_ID = "jellyfin_watches"

    /** Stable per play, so an answer replaces (or removes) the prompt it came from. */
    private fun notificationId(playKey: String): Int = "jellyfin:$playKey".hashCode()

    fun postPlays(context: Context, runtime: AppAccountRuntime, plays: List<JellyfinPlay>, note: String? = null) {
        if (plays.isEmpty() || !canPost(context)) return
        plays.forEach { play ->
            val ownerId = runtime.ownerId
            val text = "Add ${play.describe()}, watched ${play.date}, to your watch history?" + (note?.let { " $it" } ?: "")
            val builder = base(context, "Jellyfin · new watch", text)
                .setContentIntent(openApp(context, ownerId, notificationId(play.key)))
                .addAction(0, "Add", action(context, JellyfinActionReceiver.ACTION_ADD, ownerId, play.key))
                .addAction(0, "Dismiss", action(context, JellyfinActionReceiver.ACTION_DISMISS, ownerId, play.key))
            post(context, runtime, notificationId(play.key), builder)
        }
    }

    /** Brief result after an answer; replaces the prompt and goes away on its own. */
    fun postOutcome(context: Context, runtime: AppAccountRuntime, playKey: String, text: String) {
        if (!canPost(context)) return
        val builder = base(context, "Jellyfin", text)
            .setContentIntent(openApp(context, runtime.ownerId, notificationId(playKey)))
            .setTimeoutAfter(OUTCOME_TIMEOUT_MS)
        post(context, runtime, notificationId(playKey), builder)
    }

    /** The series isn't in the library: tapping opens the normal Add flow for it. */
    fun postAddFlow(context: Context, runtime: AppAccountRuntime, playKey: String, match: MediaSearchResult) {
        if (!canPost(context)) return
        val open = Intent(context, MainActivity::class.java)
            .setPackage(context.packageName)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(OutingCompletionReceiver.EXTRA_OWNER_ID, runtime.ownerId)
            .putExtra(MainActivity.EXTRA_ADD_TMDB_ID, match.tmdbId)
            .putExtra(MainActivity.EXTRA_ADD_TYPE, match.type.name)
            .putExtra(MainActivity.EXTRA_ADD_TITLE, match.title)
            .apply { match.year?.let { putExtra(MainActivity.EXTRA_ADD_YEAR, it) } }
        val intent = PendingIntent.getActivity(
            context, notificationId(playKey), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = base(context, "Jellyfin · not in your library", "${match.title} isn't in your library yet. Tap to add it.")
            .setContentIntent(intent)
        post(context, runtime, notificationId(playKey), builder)
    }

    fun cancel(context: Context, runtime: AppAccountRuntime, playKey: String) {
        NotificationManagerCompat.from(context).cancel(AppAccountRuntime.notificationTag(runtime.ownerId), notificationId(playKey))
    }

    private fun base(context: Context, title: String, text: String): NotificationCompat.Builder {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    }

    private fun post(context: Context, runtime: AppAccountRuntime, id: Int, builder: NotificationCompat.Builder) {
        // Checked here (not only in canPost) so lint sees the permission guard next to the call.
        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runtime.postIfCurrent {
            NotificationManagerCompat.from(context).notify(AppAccountRuntime.notificationTag(runtime.ownerId), id, builder.build())
        }
    }

    private fun canPost(context: Context): Boolean =
        ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context, ownerId: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java)
                .setPackage(context.packageName)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(OutingCompletionReceiver.EXTRA_OWNER_ID, ownerId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun action(context: Context, action: String, ownerId: String, playKey: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            "$action:$playKey".hashCode(),
            Intent(context, JellyfinActionReceiver::class.java)
                .setAction(action)
                .putExtra(OutingCompletionReceiver.EXTRA_OWNER_ID, ownerId)
                .putExtra(JellyfinActionReceiver.EXTRA_PLAY_KEY, playKey),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Jellyfin watches", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Asks before adding something you watched on Jellyfin to your history."
            },
        )
    }

    private const val OUTCOME_TIMEOUT_MS = 6_000L
}

/** Carries the Add-flow hand-off a notification tap delivers to [MainActivity]. */
internal fun mediaSearchResultFromExtras(tmdbId: Int, type: String?, title: String?, year: Int?): MediaSearchResult? {
    if (tmdbId <= 0 || title.isNullOrEmpty()) return null
    val mediaType = runCatching { MediaType.valueOf(type.orEmpty()) }.getOrNull() ?: return null
    return MediaSearchResult(tmdbId, title, year, mediaType, null, null)
}
