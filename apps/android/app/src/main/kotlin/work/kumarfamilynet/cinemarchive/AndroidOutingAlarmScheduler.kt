package work.kumarfamilynet.cinemarchive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.Instant
import work.kumarfamilynet.cinemarchive.data.OutingAlarmScheduler

/**
 * Real [OutingAlarmScheduler]: a single exact alarm (re-armed on every schedule/edit/cancel/
 * completion) targeting [OutingCompletionReceiver], so the local completion engine runs even
 * if CinemArchive isn't in the foreground when the show lets out — see
 * docs/superpowers/plans/2026-07-21-android-cinema-outings.md §6.
 */
class AndroidOutingAlarmScheduler(private val context: Context) : OutingAlarmScheduler {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    private val pendingIntent: PendingIntent
        get() = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, OutingCompletionReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    override fun scheduleNext(nextTransitionAt: Instant?) {
        if (nextTransitionAt == null) {
            alarmManager.cancel(pendingIntent)
            return
        }
        // Exact alarms can be denied (the user revoked SCHEDULE_EXACT_ALARM in system
        // settings) — degrade to foreground-only reconciliation (app launch/resume already
        // covers this) rather than crash. canScheduleExactAlarms() is only meaningful/present
        // API 31+, which matches this app's minSdk, so no version gate is needed.
        if (!alarmManager.canScheduleExactAlarms()) return
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTransitionAt.toEpochMilli(), pendingIntent)
    }

    private companion object {
        const val REQUEST_CODE = 4200
    }
}
