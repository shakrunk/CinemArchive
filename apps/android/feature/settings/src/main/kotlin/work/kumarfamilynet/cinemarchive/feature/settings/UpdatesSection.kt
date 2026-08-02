package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import work.kumarfamilynet.cinemarchive.core.designsystem.GroupedSeamGap
import work.kumarfamilynet.cinemarchive.core.designsystem.groupedItemShape
import work.kumarfamilynet.cinemarchive.core.model.ApkInstallState
import work.kumarfamilynet.cinemarchive.core.model.InstallSource
import work.kumarfamilynet.cinemarchive.core.model.UpdateCheckResult

/**
 * Settings → Updates (#148): the auto-check toggle plus a manual check that runs regardless of
 * it, with the result shown inline.
 *
 * [onInstall] is only offered when an update is actually installable — a sideloaded build with
 * a published APK asset and the install permission granted. Otherwise the affordance is
 * [onOpenReleasePage], so an ungranted permission degrades to "here's the release" instead of
 * failing silently.
 *
 * [installState] is tracked separately from [result] because an install runs *against* an
 * already-found update: the download takes seconds and the system's confirmation dialog is a
 * mandatory extra step even with the permission granted, so both need to be visible or the
 * button reads as dead while it's actually working.
 */
@Composable
fun UpdatesSection(
    installSource: InstallSource,
    autoCheckEnabled: Boolean,
    onSetAutoCheck: (Boolean) -> Unit,
    result: UpdateCheckResult,
    canInstallDirectly: Boolean,
    installState: ApkInstallState,
    onCheckNow: () -> Unit,
    onInstall: (String) -> Unit,
    onOpenReleasePage: (String) -> Unit,
    onGrantInstallPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "UPDATES",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 10.dp),
        )

        Surface(
            shape = groupedItemShape(isFirst = true, isLast = false),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().padding(bottom = GroupedSeamGap),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Automatically check for updates", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (installSource == InstallSource.PLAY_STORE) {
                            "Play keeps this install up to date"
                        } else {
                            "Checks GitHub Releases on launch"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoCheckEnabled, onCheckedChange = onSetAutoCheck)
            }
        }

        Surface(
            shape = groupedItemShape(isFirst = false, isLast = true),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp, 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Check for updates", style = MaterialTheme.typography.titleSmall)
                        Text(
                            statusLine(result),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (result is UpdateCheckResult.Failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    // Min-height box so the row doesn't shrink when the button is swapped for
                    // the smaller spinner and back (#188); heightIn rather than height so a
                    // larger font scale can still grow the button instead of clipping it.
                    Box(modifier = Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
                        if (result is UpdateCheckResult.Checking) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            // Always available, even with the toggle off — the toggle governs the
                            // automatic check only.
                            OutlinedButton(onClick = onCheckNow) { Text("Check") }
                        }
                    }
                }

                if (result is UpdateCheckResult.Available) {
                    val busyLabel = when (installState) {
                        ApkInstallState.Downloading -> "Downloading ${result.latestVersion}…"
                        // The system dialog is up (or the user backgrounded it). Naming it
                        // matters: the app can't install by itself, and a plain spinner here
                        // looks identical to the old do-nothing bug.
                        ApkInstallState.AwaitingConfirmation -> "Waiting for the system installer…"
                        ApkInstallState.Installed -> "Installed — reopen to finish"
                        else -> null
                    }
                    if (busyLabel != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        ) {
                            if (installState != ApkInstallState.Installed) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                            Text(busyLabel, style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) {
                            if (canInstallDirectly && result.apkUrl != null) {
                                Button(onClick = { onInstall(result.apkUrl!!) }, modifier = Modifier.weight(1f)) {
                                    Text("Install ${result.latestVersion}")
                                }
                            } else {
                                Button(
                                    onClick = { onOpenReleasePage(result.releasePageUrl) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Open release")
                                }
                            }
                        }
                    }
                    if (installState is ApkInstallState.Failed) {
                        Text(
                            installState.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (!canInstallDirectly) {
                        OutlinedButton(
                            onClick = onGrantInstallPermission,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) {
                            Text("Allow installing updates in-app")
                        }
                    }
                }
            }
        }
    }
}

private fun statusLine(result: UpdateCheckResult): String = when (result) {
    UpdateCheckResult.Idle -> "Not checked yet"
    UpdateCheckResult.Checking -> "Checking…"
    is UpdateCheckResult.UpToDate -> "Up to date (${result.currentVersion})"
    is UpdateCheckResult.Available -> "Version ${result.latestVersion} is available"
    is UpdateCheckResult.Failed -> result.message
}
