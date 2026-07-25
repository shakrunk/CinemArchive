package work.kumarfamilynet.cinemarchive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import work.kumarfamilynet.cinemarchive.core.model.ApkInstallState

/**
 * The other half of [work.kumarfamilynet.cinemarchive.data.ApkInstaller]'s update flow.
 *
 * Committing a [PackageInstaller] session doesn't install anything on its own: the system
 * answers the commit by broadcasting back with `STATUS_PENDING_USER_ACTION` and its own
 * confirmation [Intent] tucked into [Intent.EXTRA_INTENT], and waits for the app to launch it.
 * Without this receiver the broadcast landed nowhere, so the prompt never appeared, the commit
 * still reported success, and the Install button looked inert — which is exactly what it was
 * doing before this existed. Granting `REQUEST_INSTALL_PACKAGES` doesn't skip the dialog; it
 * only makes the app eligible to raise it.
 *
 * Lives in `app` rather than `data` because it has to be manifest-registered (the broadcast
 * arrives long after the composable that started the install may have left the screen), and a
 * manifest receiver has to be declared in the module that owns the manifest — the same
 * constraint [AndroidOutingAlarmScheduler] notes for its own target.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val installer = (context.applicationContext as CinemArchiveApplication).apkInstaller
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = intent.confirmationIntent()
                if (confirmation == null) {
                    installer.publishInstallState(
                        ApkInstallState.Failed("The system didn't hand back an install prompt"),
                    )
                    return
                }
                installer.publishInstallState(ApkInstallState.AwaitingConfirmation)
                // FLAG_ACTIVITY_NEW_TASK is required starting an activity from a receiver.
                // The background-activity-launch restrictions don't bite here: this only ever
                // runs moments after the user tapped Install, with the app in the foreground.
                context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            PackageInstaller.STATUS_SUCCESS ->
                installer.publishInstallState(ApkInstallState.Installed)

            // Declining the system dialog isn't an error — drop back to the plain
            // "update available" state so the button reads as retryable rather than broken.
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                installer.publishInstallState(ApkInstallState.Idle)

            else ->
                installer.publishInstallState(ApkInstallState.Failed(describe(status, message)))
        }
    }

    private fun Intent.confirmationIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_INTENT)
        }

    /** The raw `EXTRA_STATUS_MESSAGE` is framework shorthand ("INSTALL_FAILED_VERSION_DOWNGRADE"),
     *  so the likely causes get spelled out. CONFLICT especially: a sideloaded debug build and a
     *  CI-signed release APK have different signing keys, and no amount of retrying fixes that. */
    private fun describe(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_BLOCKED ->
            "The system blocked this install. Check that CinemArchive is still allowed to install unknown apps."
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "This update conflicts with the copy already installed — usually a different signing key. " +
                "Uninstall the current build first, then install the release APK."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "This APK isn't compatible with this device."
        PackageInstaller.STATUS_FAILURE_INVALID ->
            "The downloaded APK was rejected as invalid or corrupt."
        PackageInstaller.STATUS_FAILURE_STORAGE ->
            "Not enough storage to install the update."
        else -> message?.takeIf { it.isNotBlank() }?.let { "Install failed: $it" } ?: "Install failed"
    }
}
