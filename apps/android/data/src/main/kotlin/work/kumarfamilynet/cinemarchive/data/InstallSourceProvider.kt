package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import work.kumarfamilynet.cinemarchive.core.model.InstallSource

/** Play Store's own package name — the only installer that means "Play owns our updates". */
private const val PLAY_STORE_PACKAGE = "com.android.vending"

/**
 * Resolves how this build was installed, once per process (the answer cannot change without a
 * reinstall, which restarts us anyway).
 *
 * The app is pre-distribution, so today every install is a sideload; this exists so the update
 * flow branches on the real answer rather than that assumption once it ships to Play.
 */
class InstallSourceProvider(private val context: Context) {

    val source: InstallSource by lazy { resolve() }

    private fun resolve(): InstallSource {
        val installer = runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()

        return when (installer) {
            PLAY_STORE_PACKAGE -> InstallSource.PLAY_STORE
            null -> InstallSource.UNKNOWN
            else -> InstallSource.SIDELOADED
        }
    }
}
