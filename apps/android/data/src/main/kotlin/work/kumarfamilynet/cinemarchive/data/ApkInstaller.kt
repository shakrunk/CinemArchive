package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Installs an update APK for sideloaded builds.
 *
 * Two paths, by design (#146): with REQUEST_INSTALL_PACKAGES granted we download the release
 * asset and hand it to [PackageInstaller], so the update is one confirmation away. Without it
 * we never fail silently — the caller falls back to opening the release page, and can send the
 * user to the "install unknown apps" screen if they want the direct path next time.
 */
class ApkInstaller(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    /** Whether the system will let us hand it a package to install. */
    fun canRequestInstalls(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Settings screen where the user grants "install unknown apps" for this app. */
    fun unknownSourcesSettingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri(),
            )
        } else {
            null
        }

    fun releasePageIntent(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Streams [apkUrl] straight into a [PackageInstaller] session — no copy in app storage to
     * clean up afterwards. Returns the session's [android.app.PendingIntent]-backed status
     * intent via [onStatusIntent] once the system is ready to prompt.
     */
    suspend fun downloadAndInstall(apkUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(apkUrl).build()
            httpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download failed (${response.code})" }
                val body = response.body ?: error("Download returned no body")
                val declaredLength = body.contentLength()

                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL,
                )
                if (declaredLength > 0) params.setSize(declaredLength)

                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("cinemarchive-update", 0, declaredLength).use { out ->
                        body.byteStream().copyTo(out)
                        session.fsync(out)
                    }
                    session.commit(installStatusSender(sessionId))
                }
            }
        }
    }

    private fun installStatusSender(sessionId: Int) =
        android.app.PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(INSTALL_STATUS_ACTION).setPackage(context.packageName),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
        ).intentSender

    companion object {
        const val INSTALL_STATUS_ACTION = "work.kumarfamilynet.cinemarchive.INSTALL_STATUS"
    }
}
