package work.kumarfamilynet.cinemarchive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Runs once after this app is updated.
 *
 * Starting the process is enough to clean up the staged update APK: [CinemArchiveApplication]
 * calls `ApkInstaller.reconcileAfterLaunch` on every process start.
 *
 * After an *in-app* update it also offers the way back in. Android doesn't relaunch an app that
 * replaced itself — the old process is killed and the user is dropped on whatever was underneath
 * — and it blocks this receiver from starting an activity itself (verified on Android 16:
 * "Background activity launch blocked"). A notification tap is allowed to, so this posts
 * "updated — tap to open". Updates from anywhere else (adb, a store) are left alone.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Same guard as BootCompletedReceiver: act only on the one system action registered.
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as CinemArchiveApplication
        if (!app.apkInstaller.wasJustUpdatedInApp()) return

        UpdateInstallNotifier.postUpdated(context, BuildConfig.VERSION_NAME)
    }
}
