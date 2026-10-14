package work.kumarfamilynet.cinemarchive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Exact alarms (like the one [AndroidOutingAlarmScheduler] arms) don't survive a device
 * reboot — this re-arms the next one on boot rather than leaving a scheduled outing silently
 * relying on the user reopening the app before showtime.
 *
 * Only the stored session's owner is brought up (the account runtime manager started in
 * [CinemArchiveApplication.onCreate] builds it); with no session there is nothing to re-arm,
 * and there is no unfenced global database to fall back on.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as CinemArchiveApplication
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                app.awaitRuntime(ownerId = null)?.completeDueOutings()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
