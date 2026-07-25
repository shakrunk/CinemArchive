package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import work.kumarfamilynet.cinemarchive.core.model.ApkInstallState

/**
 * Installs an update APK for sideloaded builds.
 *
 * Two paths, by design (#146): with REQUEST_INSTALL_PACKAGES granted we download the release
 * asset and hand it to [PackageInstaller], so the update is one confirmation away. Without it
 * we never fail silently — the caller falls back to opening the release page, and can send the
 * user to the "install unknown apps" screen if they want the direct path next time.
 *
 * **Committing a session is not the end of the flow.** [PackageInstaller] answers a commit by
 * broadcasting back to [INSTALL_STATUS_ACTION] with `STATUS_PENDING_USER_ACTION` and the
 * system's confirmation [Intent] in [Intent.EXTRA_INTENT]; nothing installs until *the app*
 * launches that intent. The granted permission only makes us eligible to ask. That broadcast
 * needs a manifest-registered receiver, which has to live in the `app` module — see
 * `InstallStatusReceiver`, which is also what drives [installState] past [
 * ApkInstallState.Downloading].
 */
class ApkInstaller(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val installStateFlow = MutableStateFlow<ApkInstallState>(ApkInstallState.Idle)

    /** Observable progress of the current install, for the Settings UI. Advanced from here
     *  while downloading and from `InstallStatusReceiver` once the system takes over. */
    val installState: StateFlow<ApkInstallState> = installStateFlow.asStateFlow()

    /** Entry point for the install-status receiver, which lives in `app` and can't reach this
     *  class's private state directly. */
    fun publishInstallState(state: ApkInstallState) {
        installStateFlow.value = state
    }
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
     * clean up afterwards. Success here means only that the session was *committed*: the
     * system's confirmation prompt is raised later, off the [INSTALL_STATUS_ACTION] broadcast
     * (see this class's kdoc). Watch [installState] for what actually happened.
     */
    suspend fun downloadAndInstall(apkUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        installStateFlow.value = ApkInstallState.Downloading
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
        }.onFailure { error ->
            installStateFlow.value = ApkInstallState.Failed(error.message ?: "Download failed")
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
