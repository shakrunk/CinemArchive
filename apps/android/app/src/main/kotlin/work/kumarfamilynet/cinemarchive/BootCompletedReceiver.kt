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
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as CinemArchiveApplication
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                app.outingsRepository.completeDueOutings()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
