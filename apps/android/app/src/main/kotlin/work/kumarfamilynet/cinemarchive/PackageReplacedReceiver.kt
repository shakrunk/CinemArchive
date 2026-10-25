package work.kumarfamilynet.cinemarchive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Runs once after this app is updated, so the staged update APK in `cacheDir/updates/` is
 * cleaned up even if the user doesn't reopen the app straight away.
 *
 * The work itself happens in [CinemArchiveApplication.onCreate], which calls
 * `ApkInstaller.reconcileAfterLaunch` on every process start; this broadcast's job is only to
 * start the process. The "just updated" result (`ApkInstaller.consumeJustUpdatedVersion`) is
 * deliberately left alone, for the first launch the user actually sees.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
