package work.kumarfamilynet.cinemarchive.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import work.kumarfamilynet.cinemarchive.core.model.ApkInstallState
import work.kumarfamilynet.cinemarchive.core.model.ApkInstallState.Failed.Stage
import work.kumarfamilynet.cinemarchive.core.model.UpdateCheckResult
import work.kumarfamilynet.cinemarchive.core.model.compareVersions

/** What [ApkInstaller] needs to know about a downloaded APK before trusting it. */
data class ApkArchiveInfo(val packageName: String, val versionName: String?)

/**
 * Downloads and installs update APKs for sideloaded builds, as two separate steps.
 *
 * **Download** ([download]) stages the release APK under `cacheDir/updates/` and only reports
 * [ApkInstallState.Ready] once the file is a ZIP, matches the release's `SHA256SUMS` entry
 * (when the release publishes one), and is a newer build of this same package. A file that
 * fails any check is deleted, so nothing unverified is ever handed to the installer.
 *
 * **Install** ([install]) copies the staged file into a [PackageInstaller] session and commits
 * it, asking the system to skip its confirmation dialog: an app updating itself is allowed to,
 * so usually the app is simply replaced and restarts on the new version. It is only ever started
 * by an explicit user action, so when the system does still ask, its prompt is raised while the
 * app is in the foreground (#166's automatic path used to
 * commit from a background process start, where Android blocks the prompt). Declining that
 * prompt goes back to [ApkInstallState.Ready], so a retry never downloads again.
 *
 * **Committing a session is not the end of the flow.** [PackageInstaller] answers a commit by
 * broadcasting back to [INSTALL_STATUS_ACTION] with `STATUS_PENDING_USER_ACTION` and the
 * system's confirmation [Intent] in [Intent.EXTRA_INTENT]; nothing installs until *the app*
 * launches that intent. That broadcast needs a manifest-registered receiver, which has to live
 * in the `app` module — see `InstallStatusReceiver`, which drives [installState] from there.
 *
 * Without REQUEST_INSTALL_PACKAGES none of this runs (#146): the caller falls back to opening
 * the release page, and can send the user to the "install unknown apps" screen.
 */
class ApkInstaller(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val currentVersionName: String = installedVersionName(context),
    private val inspectArchive: (File) -> ApkArchiveInfo? = { file -> readArchiveInfo(context, file) },
    private val clock: () -> Long = System::currentTimeMillis,
    // Outlives the screen that started a download or install, so leaving it mid-way doesn't
    // cancel the work.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val installStateFlow = MutableStateFlow<ApkInstallState>(ApkInstallState.Idle)

    /** Observable progress of the current download/install, for the update UI. Advanced from
     *  here and from `InstallStatusReceiver` once the system takes over. */
    val installState: StateFlow<ApkInstallState> = installStateFlow.asStateFlow()

    private val updatesDir = File(context.cacheDir, UPDATES_DIR)
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Serializes download/install/restore so two taps, or a tap racing the launch-time
    // download, never write the same staged file twice.
    private val mutex = Mutex()

    private var activeSessionId: Int? = null

    // Set once the receiver has actually launched the system prompt. Abandoned-prompt
    // detection keys on this rather than on AwaitingConfirmation alone, so it can't fire
    // while the session is still being written.
    @Volatile
    private var promptLaunched = false

    private val stashedConfirmationFlow = MutableStateFlow<Intent?>(null)

    /**
     * A confirmation prompt that arrived while no activity was in the foreground, where Android
     * would have blocked launching it. The activity collects this while resumed and launches it,
     * so a prompt stashed even moments after a resume is still picked up.
     */
    val stashedConfirmation: StateFlow<Intent?> = stashedConfirmationFlow.asStateFlow()

    /** Entry point for the install-status receiver, which lives in `app`. */
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
     * Downloads and verifies [release]'s APK, ending in [ApkInstallState.Ready]. Returns at once
     * if that release is already staged, or while a download or install is under way.
     */
    suspend fun download(release: UpdateCheckResult.Available): Result<Unit> = withContext(Dispatchers.IO) {
        val apkUrl = release.apkUrl
            ?: return@withContext Result.failure(IllegalStateException("Release has no APK"))
        mutex.withLock {
            when (installStateFlow.value) {
                is ApkInstallState.Downloading, ApkInstallState.Committing,
                ApkInstallState.AwaitingConfirmation -> return@withLock Result.success(Unit)
                else -> Unit
            }
            if (restoreStagedLocked(release.latestVersion)) return@withLock Result.success(Unit)

            installStateFlow.value = ApkInstallState.Downloading(0, release.apkSizeBytes)
            val part = File(updatesDir, "${stagedName(release.latestVersion)}.part")
            try {
                updatesDir.mkdirs()
                val expectedSha = release.checksumUrl?.let { fetchExpectedSha256(it, apkUrl) }
                val actualSha = downloadTo(apkUrl, part, release.apkSizeBytes)
                if (expectedSha != null && !expectedSha.equals(actualSha, ignoreCase = true)) {
                    throw IOException("The downloaded update didn't match its published checksum")
                }
                verifyArchive(part)
                val staged = stagedFile(release.latestVersion)
                updatesDir.listFiles()?.filter { it != part }?.forEach { it.delete() }
                if (!part.renameTo(staged)) throw IOException("Couldn't stage the downloaded update")
                prefs.edit {
                    putString(KEY_STAGED_VERSION, release.latestVersion)
                    putString(KEY_STAGED_SHA256, actualSha)
                }
                installStateFlow.value = ApkInstallState.Ready(release.latestVersion)
                Result.success(Unit)
            } catch (e: CancellationException) {
                part.delete()
                installStateFlow.value = ApkInstallState.Idle
                throw e
            } catch (e: Throwable) {
                part.delete()
                installStateFlow.value = ApkInstallState.Failed(e.userMessage("Download failed"), Stage.DOWNLOAD)
                Result.failure(e)
            }
        }
    }

    /** [download] on this installer's own scope, for UI callers. */
    fun startDownload(release: UpdateCheckResult.Available) {
        scope.launch { download(release) }
    }

    /** [install] on this installer's own scope, for UI callers. */
    fun startInstall() {
        scope.launch { install() }
    }

    /**
     * Restores [ApkInstallState.Ready] when [version] is already staged and still verifies, so
     * an update downloaded in an earlier process doesn't download again. Returns whether it did.
     */
    suspend fun restoreIfStaged(version: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (installStateFlow.value !is ApkInstallState.Idle &&
                installStateFlow.value !is ApkInstallState.Failed
            ) {
                return@withLock installStateFlow.value is ApkInstallState.Ready
            }
            restoreStagedLocked(version)
        }
    }

    // Called with mutex held.
    private fun restoreStagedLocked(version: String): Boolean {
        val file = stagedFile(version)
        val stagedSha = prefs.getString(KEY_STAGED_SHA256, null)
        val valid = file.isFile &&
            prefs.getString(KEY_STAGED_VERSION, null) == version &&
            stagedSha != null &&
            runCatching { sha256Of(file).equals(stagedSha, ignoreCase = true) && verifyArchive(file) }
                .getOrDefault(false)
        if (!valid) {
            if (file.exists()) file.delete()
            return false
        }
        installStateFlow.value = ApkInstallState.Ready(version)
        return true
    }

    /**
     * Hands the staged APK to [PackageInstaller]. Must only be called from a user action in the
     * foreground — see the class kdoc. Success here means only that the session was committed;
     * watch [installState] for the outcome.
     */
    suspend fun install(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            // A failed install keeps its staged APK, so retrying it is an install, not a download.
            val version = when (val state = installStateFlow.value) {
                is ApkInstallState.Ready -> state.version
                is ApkInstallState.Failed -> prefs.getString(KEY_STAGED_VERSION, null)
                    .takeIf { state.stage == Stage.INSTALL }
                else -> null
            } ?: return@withLock Result.failure(IllegalStateException("No update is ready to install"))
            val file = stagedFile(version)
            if (!file.isFile) {
                // cacheDir can be purged under storage pressure between staging and installing.
                installStateFlow.value = ApkInstallState.Failed(
                    "The downloaded update was cleared from storage. Download it again.",
                    Stage.DOWNLOAD,
                )
                return@withLock Result.failure(IOException("Staged update missing"))
            }

            installStateFlow.value = ApkInstallState.Committing
            promptLaunched = false
            stashedConfirmationFlow.value = null
            val installer = context.packageManager.packageInstaller
            activeSessionId?.let { runCatching { installer.abandonSession(it) } }
            runCatching {
                // commit(), not apply(): a successful install kills this process, and the next
                // launch reads this to tell an installed update from an abandoned one.
                prefs.edit(commit = true) { putString(KEY_PENDING_VERSION, version) }

                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setSize(file.length())
                // An app updating itself may skip the confirmation dialog (API 31+, which is our
                // minSdk) when it declares UPDATE_PACKAGES_WITHOUT_USER_ACTION and targets a
                // recent enough SDK. The system can still ask, so STATUS_PENDING_USER_ACTION
                // stays handled; when it doesn't, the process is replaced straight away.
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                val sessionId = installer.createSession(params)
                activeSessionId = sessionId
                installer.openSession(sessionId).use { session ->
                    file.inputStream().use { input ->
                        session.openWrite("cinemarchive-update", 0, file.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                    session.commit(installStatusSender(sessionId))
                }
            }.onFailure { error ->
                activeSessionId = null
                prefs.edit { remove(KEY_PENDING_VERSION) }
                installStateFlow.value = ApkInstallState.Failed(error.userMessage("Install failed"), Stage.INSTALL)
            }
        }
    }

    /** Called by the receiver once it has launched the system's confirmation prompt. */
    fun onPromptLaunched() {
        promptLaunched = true
        installStateFlow.value = ApkInstallState.AwaitingConfirmation
    }

    /** Called by the receiver when the prompt arrived with no activity in the foreground. */
    fun stashConfirmation(confirmation: Intent) {
        stashedConfirmationFlow.value = confirmation
    }

    /** Called by the activity once it has launched the stashed prompt. */
    fun onStashedConfirmationLaunched(confirmation: Intent) {
        stashedConfirmationFlow.compareAndSet(confirmation, null)
        onPromptLaunched()
    }

    /**
     * Called when the system reports the install ended without installing. Keeps the staged
     * APK, so the retry is an install rather than another download.
     */
    fun onInstallEnded(failure: String?) {
        activeSessionId = null
        promptLaunched = false
        prefs.edit { remove(KEY_PENDING_VERSION) }
        val version = prefs.getString(KEY_STAGED_VERSION, null)
        installStateFlow.value = when {
            failure != null -> ApkInstallState.Failed(failure, Stage.INSTALL)
            version != null && stagedFile(version).isFile -> ApkInstallState.Ready(version)
            else -> ApkInstallState.Idle
        }
    }

    /**
     * Called when the activity has been back in the foreground for a moment. A system prompt
     * the user left without answering (Home, recents) reports nothing, so an install still
     * awaiting confirmation at this point was probably abandoned: offer the install again.
     *
     * Only the displayed state changes. The session and the pending-version record are left
     * alone, because the user may still return to that prompt from recents and accept it, and
     * that install must still be recognised on the next launch. A new [install] abandons the
     * old session itself, and [reconcileAfterLaunch] forgets a pending version that never landed.
     */
    fun resetIfPromptAbandoned() {
        if (installStateFlow.value != ApkInstallState.AwaitingConfirmation || !promptLaunched) return
        promptLaunched = false
        val version = prefs.getString(KEY_STAGED_VERSION, null)
        installStateFlow.value = if (version != null && stagedFile(version).isFile) {
            ApkInstallState.Ready(version)
        } else {
            ApkInstallState.Idle
        }
    }

    /**
     * Run once per process start, including starts by the package-replaced broadcast.
     *
     * Deletes the staged APK once the running build has caught up with it, and forgets an
     * install attempt that didn't end on the version now running (declined, failed, or killed
     * mid-way). An attempt that did is kept for [consumeJustUpdatedVersion], so the first
     * launch the user actually sees can say the update worked.
     *
     * Returns whether a staged update was cleaned up, i.e. whether this build is that update
     * (or newer) and anything announcing it as ready can be dismissed.
     */
    fun reconcileAfterLaunch(): Boolean {
        val staged = prefs.getString(KEY_STAGED_VERSION, null)
        val caughtUp = staged != null && compareVersions(currentVersionName, staged) >= 0
        if (caughtUp) {
            updatesDir.deleteRecursively()
            prefs.edit {
                remove(KEY_STAGED_VERSION)
                remove(KEY_STAGED_SHA256)
            }
        }
        val pending = prefs.getString(KEY_PENDING_VERSION, null)
        if (pending != null && compareVersions(currentVersionName, pending) != 0) {
            prefs.edit { remove(KEY_PENDING_VERSION) }
        }
        return caughtUp
    }

    /**
     * The version this build was just updated to by an in-app install, returned once, or null.
     *
     * A self-update kills the process before `STATUS_SUCCESS` can arrive, so success is judged
     * here instead: the version recorded right before committing is the one now running.
     */
    fun consumeJustUpdatedVersion(): String? {
        val pending = prefs.getString(KEY_PENDING_VERSION, null) ?: return null
        prefs.edit { remove(KEY_PENDING_VERSION) }
        return pending.takeIf { compareVersions(currentVersionName, it) == 0 }
    }

    private fun stagedName(version: String) = "CinemArchive-v$version.apk"

    private fun stagedFile(version: String) = File(updatesDir, stagedName(version))

    /** Streams [url] into [target], publishing progress, and returns the file's SHA-256. */
    private fun downloadTo(url: String, target: File, fallbackTotal: Long?): String {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed (${response.code})")
            val body = response.body ?: throw IOException("Download returned no body")
            val total = body.contentLength().takeIf { it > 0 } ?: fallbackTotal
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            var lastPublishedAt = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        bytes += read
                        val now = clock()
                        if (now - lastPublishedAt >= PROGRESS_INTERVAL_MILLIS) {
                            lastPublishedAt = now
                            installStateFlow.value = ApkInstallState.Downloading(bytes, total)
                        }
                    }
                }
            }
            installStateFlow.value = ApkInstallState.Downloading(bytes, total ?: bytes)
            return digest.digest().toHex()
        }
    }

    private fun fetchExpectedSha256(checksumUrl: String, apkUrl: String): String {
        val request = Request.Builder().url(checksumUrl).build()
        val body = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Couldn't fetch the update's checksum (${response.code})")
            response.body?.string().orEmpty()
        }
        val apkName = apkUrl.substringAfterLast('/')
        return parseSha256Sums(body, apkName)
            ?: throw IOException("The update's checksum file doesn't list $apkName")
    }

    /** Throws unless [file] is an APK of this package, newer than the running build. */
    private fun verifyArchive(file: File): Boolean {
        if (!file.hasZipHeader()) throw IOException("The downloaded update isn't an APK")
        val info = inspectArchive(file) ?: throw IOException("The downloaded update couldn't be read as an APK")
        if (info.packageName != context.packageName) {
            throw IOException("The downloaded update is for a different app (${info.packageName})")
        }
        val version = info.versionName ?: throw IOException("The downloaded update has no version")
        if (compareVersions(version, currentVersionName) <= 0) {
            throw IOException("The downloaded update ($version) isn't newer than this build ($currentVersionName)")
        }
        return true
    }

    private fun installStatusSender(sessionId: Int) =
        PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(INSTALL_STATUS_ACTION).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).intentSender

    companion object {
        const val INSTALL_STATUS_ACTION = "work.kumarfamilynet.cinemarchive.INSTALL_STATUS"

        private const val UPDATES_DIR = "updates"
        private const val PREFS_NAME = "app_updates"
        private const val KEY_STAGED_VERSION = "staged_version"
        private const val KEY_STAGED_SHA256 = "staged_sha256"
        private const val KEY_PENDING_VERSION = "pending_update_version"
        private const val PROGRESS_INTERVAL_MILLIS = 100L
    }
}

/**
 * Finds [fileName]'s hash in `sha256sum` output (`<hex>  <name>` per line, with `*` marking
 * binary mode). Returns null when the file isn't listed.
 */
internal fun parseSha256Sums(contents: String, fileName: String): String? =
    contents.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val hash = line.substringBefore(' ')
            val name = line.substringAfter(' ').trim().removePrefix("*")
            hash.takeIf { name == fileName && it.length == 64 }
        }
        .firstOrNull()
        ?.lowercase()

private fun File.hasZipHeader(): Boolean =
    runCatching {
        inputStream().use { input ->
            val header = ByteArray(4)
            input.read(header) == 4 &&
                header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
        }
    }.getOrDefault(false)

private fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun installedVersionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull().orEmpty()

private fun readArchiveInfo(context: Context, file: File): ApkArchiveInfo? =
    context.packageManager.getPackageArchiveInfo(file.path, 0)?.let {
        ApkArchiveInfo(it.packageName, it.versionName)
    }
