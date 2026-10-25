package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.model.ApkInstallState
import work.kumarfamilynet.cinemarchive.core.model.UpdateCheckResult
import work.kumarfamilynet.cinemarchive.core.model.UpdateDownloadPolicy
import work.kumarfamilynet.cinemarchive.data.ApkInstaller
import work.kumarfamilynet.cinemarchive.data.AppUpdateController
import work.kumarfamilynet.cinemarchive.data.PreferencesRepository

/**
 * [UpdateSheet] wired to the shared [AppUpdateController] and [ApkInstaller], for hosts that
 * just decide when it is open (the home banner, Settings → About, a notification tap).
 */
@Composable
fun UpdateSheetRoute(
    appUpdateController: AppUpdateController,
    apkInstaller: ApkInstaller,
    preferencesRepository: PreferencesRepository,
    onDismiss: () -> Unit,
) {
    val result by appUpdateController.state.collectAsStateWithLifecycle()
    val installState by apkInstaller.installState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    // Re-read whenever the app returns to the foreground: the user grants the permission in
    // system Settings and comes straight back, and a stale "false" would strand them here.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val canInstallDirectly = remember(lifecycleState) { apkInstaller.canRequestInstalls() }
    val downloadPolicy by preferencesRepository.observeUpdateDownloadPolicy()
        .collectAsStateWithLifecycle(initialValue = UpdateDownloadPolicy.WIFI_ONLY)
    var confirmMeteredDownload by remember { mutableStateOf(false) }
    val startDownload = { (result as? UpdateCheckResult.Available)?.let(apkInstaller::startDownload) }

    if (confirmMeteredDownload) {
        val size = (result as? UpdateCheckResult.Available)?.apkSizeBytes?.let { " (${megabytes(it)})" }.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmMeteredDownload = false },
            title = { Text("Download on mobile data?") },
            text = { Text("You're not on Wi-Fi. Downloading the update$size may use your data allowance.") },
            confirmButton = {
                TextButton(onClick = { confirmMeteredDownload = false; startDownload() }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { confirmMeteredDownload = false }) { Text("Not now") } },
        )
    }

    UpdateSheet(
        result = result,
        installState = installState,
        canInstallDirectly = canInstallDirectly,
        // Started on the installer's own scope, so closing the sheet doesn't cancel them.
        onDownload = {
            // Only "Any network" means the user already agreed to spend mobile data.
            if (downloadPolicy != UpdateDownloadPolicy.ANY_NETWORK && isMeteredNetwork(context)) {
                confirmMeteredDownload = true
            } else {
                startDownload()
            }
        },
        onInstall = apkInstaller::startInstall,
        onCheckAgain = { scope.launch { appUpdateController.check(force = true) } },
        onOpenReleasePage = { url -> uriHandler.openUri(url) },
        onGrantInstallPermission = {
            apkInstaller.unknownSourcesSettingsIntent()?.let { context.startActivity(it) }
        },
        onDismiss = onDismiss,
    )
}

/** The distinct bodies the sheet shows. States sharing a body share a key, so moving between
 *  them doesn't restart the crossfade. */
private enum class SheetBody { STATUS, DETAILS, DOWNLOADING, INSTALLING }

/**
 * The update flow in one place: what changed, then Download, then Install, with every wait
 * named and every failure retryable. Opened from the home banner, Settings → About, and the
 * update notifications, all of which read the same shared check and install state.
 *
 * Stateless: callers pass the shared [result] and [installState] and wire the actions to
 * `AppUpdateController` / `ApkInstaller`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(
    result: UpdateCheckResult,
    installState: ApkInstallState,
    canInstallDirectly: Boolean,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCheckAgain: () -> Unit,
    onOpenReleasePage: (String) -> Unit,
    onGrantInstallPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    val body = when {
        result !is UpdateCheckResult.Available -> SheetBody.STATUS
        installState is ApkInstallState.Downloading -> SheetBody.DOWNLOADING
        installState == ApkInstallState.Committing || installState == ApkInstallState.AwaitingConfirmation ->
            SheetBody.INSTALLING
        else -> SheetBody.DETAILS
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp, 0.dp, 20.dp, 28.dp)) {
            Text("APP UPDATE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                when (result) {
                    is UpdateCheckResult.Available -> "${result.currentVersion} → ${result.latestVersion}"
                    is UpdateCheckResult.UpToDate -> "You're up to date"
                    is UpdateCheckResult.Failed -> "Couldn't check for updates"
                    else -> "Checking for updates…"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            (result as? UpdateCheckResult.Available)?.publishedAt?.let { published ->
                Text(
                    "Released " + published.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedContent(
                targetState = body,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "updateSheetBody",
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
            ) { target ->
                // The outgoing body recomposes with the current state while it fades out, so each
                // branch re-checks its own preconditions instead of assuming them.
                val release = result as? UpdateCheckResult.Available
                when (target) {
                    SheetBody.STATUS -> StatusBody(result, onCheckAgain)
                    SheetBody.DOWNLOADING -> {
                        val downloading = installState as? ApkInstallState.Downloading
                        if (release != null && downloading != null) DownloadProgress(release.latestVersion, downloading)
                    }
                    SheetBody.INSTALLING -> BusyLine(
                        if (installState == ApkInstallState.Committing) {
                            "Installing — CinemArchive will close…"
                        } else {
                            // The system asked after all; its dialog is a step the app can't skip.
                            "Confirm the install in the system dialog"
                        },
                    )
                    SheetBody.DETAILS -> if (release != null) DetailsBody(
                        release = release,
                        installState = installState,
                        canInstallDirectly = canInstallDirectly,
                        onDownload = onDownload,
                        onInstall = onInstall,
                        onCheckAgain = onCheckAgain,
                        onOpenReleasePage = onOpenReleasePage,
                        onGrantInstallPermission = onGrantInstallPermission,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBody(result: UpdateCheckResult, onCheckAgain: () -> Unit) {
    when (result) {
        UpdateCheckResult.Checking, UpdateCheckResult.Idle -> BusyLine("Checking GitHub for a newer release…")
        is UpdateCheckResult.UpToDate -> Column {
            Text("CinemArchive ${result.currentVersion} is the latest release.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onCheckAgain, modifier = Modifier.padding(top = 12.dp)) { Text("Check again") }
        }
        is UpdateCheckResult.Failed -> Column {
            Notice(result.message, MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onCheckAgain, modifier = Modifier.padding(top = 12.dp)) { Text("Try again") }
        }
        is UpdateCheckResult.Available -> Unit
    }
}

@Composable
private fun DetailsBody(
    release: UpdateCheckResult.Available,
    installState: ApkInstallState,
    canInstallDirectly: Boolean,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCheckAgain: () -> Unit,
    onOpenReleasePage: (String) -> Unit,
    onGrantInstallPermission: () -> Unit,
) {
    val staged = installState is ApkInstallState.Ready ||
        (installState is ApkInstallState.Failed && installState.stage == ApkInstallState.Failed.Stage.INSTALL)

    Column {
        if (release.releaseNotes.isNotBlank()) {
            // Bounded so the actions stay on screen however long the notes run.
            ReleaseNotes(
                release.releaseNotes,
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            )
        }

        when {
            // The release is announced but its APK hasn't been attached yet; that resolves itself.
            release.apkUrl == null -> Notice(
                "This release is still being published. Check again in a minute.",
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
            !canInstallDirectly -> Notice(
                "To install updates from here, allow CinemArchive to install apps. " +
                    "Until then, you can download the update from its release page.",
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Said up front: installing replaces the running app, so it closes, and Android
            // doesn't reopen it (a notification offers to).
            staged -> Notice(
                "Downloaded and verified. CinemArchive will close while it installs.",
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (installState is ApkInstallState.Failed) Notice(installState.message, MaterialTheme.colorScheme.error)

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            when {
                release.apkUrl == null -> Button(onClick = onCheckAgain, modifier = Modifier.weight(1f)) { Text("Check again") }
                !canInstallDirectly -> Button(onClick = onGrantInstallPermission, modifier = Modifier.weight(1f)) { Text("Allow installs") }
                staged -> Button(onClick = onInstall, modifier = Modifier.weight(1f)) { Text("Install") }
                else -> Button(onClick = onDownload, modifier = Modifier.weight(1f)) {
                    val size = release.apkSizeBytes?.let { " · ${megabytes(it)}" }.orEmpty()
                    Text(if (installState is ApkInstallState.Failed) "Retry download$size" else "Download$size")
                }
            }
            OutlinedButton(onClick = { onOpenReleasePage(release.releasePageUrl) }) { Text("Release page") }
        }
    }
}

@Composable
private fun Notice(text: String, color: Color) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(12.dp))
    }
}

@Composable
internal fun BusyLine(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Real progress — percent and MB — with an indeterminate bar only when no size is known. */
@Composable
internal fun DownloadProgress(version: String, state: ApkInstallState.Downloading) {
    val fraction = state.fraction
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Downloading $version", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (fraction != null) Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
        }
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        }
        Text(
            state.totalBytes?.let { "${megabytes(state.bytes)} of ${megabytes(it)}" } ?: megabytes(state.bytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "You can close this; the download carries on.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun isMeteredNetwork(context: Context): Boolean =
    context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

internal fun megabytes(bytes: Long): String = "%.1f MB".format(bytes / 1_000_000.0)
