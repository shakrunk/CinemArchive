package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
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
import work.kumarfamilynet.cinemarchive.core.designsystem.ChoiceOption
import work.kumarfamilynet.cinemarchive.core.designsystem.GroupedSeamGap
import work.kumarfamilynet.cinemarchive.core.designsystem.SegmentedGroup
import work.kumarfamilynet.cinemarchive.core.designsystem.groupedItemShape
import work.kumarfamilynet.cinemarchive.core.model.ApkInstallState
import work.kumarfamilynet.cinemarchive.core.model.InstallSource
import work.kumarfamilynet.cinemarchive.core.model.UpdateCheckResult
import work.kumarfamilynet.cinemarchive.core.model.UpdateDownloadPolicy

/**
 * Settings → Updates (#148): the auto-check toggle plus a manual check that runs regardless of
 * it, with the result shown inline.
 *
 * Downloading and installing live in [UpdateSheet], which [onOpenUpdate] opens — the same sheet
 * the home banner and the update notifications open — so there is one place the flow runs and
 * this section only reports where it stands.
 */
@Composable
fun UpdatesSection(
    installSource: InstallSource,
    autoCheckEnabled: Boolean,
    onSetAutoCheck: (Boolean) -> Unit,
    result: UpdateCheckResult,
    installState: ApkInstallState,
    onCheckNow: () -> Unit,
    isChecking: Boolean,
    onOpenUpdate: () -> Unit,
    downloadPolicy: UpdateDownloadPolicy,
    onSetDownloadPolicy: (UpdateDownloadPolicy) -> Unit,
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

        if (installSource != InstallSource.PLAY_STORE) {
            Surface(
                shape = groupedItemShape(isFirst = false, isLast = false),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(bottom = GroupedSeamGap),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp)) {
                    Text("Download updates automatically", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Installing always waits for you",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The app's own M3 Expressive group (as Appearance → Theme uses), with a glyph
                    // per option so the choice reads before its word does.
                    SegmentedGroup(
                        options = listOf(
                            ChoiceOption(UpdateDownloadPolicy.WIFI_ONLY, "Wi-Fi only", icon = Icons.Filled.Wifi),
                            ChoiceOption(UpdateDownloadPolicy.ANY_NETWORK, "Always", icon = Icons.Filled.SignalCellularAlt),
                            ChoiceOption(UpdateDownloadPolicy.NOTIFY_ONLY, "Never", icon = Icons.Filled.Block),
                        ),
                        selected = downloadPolicy,
                        onSelect = onSetDownloadPolicy,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
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
                        if (isChecking || result is UpdateCheckResult.Checking) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            // Always available, even with the toggle off — the toggle governs the
                            // automatic check only.
                            OutlinedButton(onClick = onCheckNow) { Text("Check") }
                        }
                    }
                }

                if (result is UpdateCheckResult.Available) {
                    Button(onClick = onOpenUpdate, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Text(
                            when (installState) {
                                is ApkInstallState.Downloading ->
                                    "Downloading" + (installState.fraction?.let { " · ${(it * 100).toInt()}%" } ?: "…")
                                is ApkInstallState.Ready -> "Install ${result.latestVersion}"
                                ApkInstallState.Committing, ApkInstallState.AwaitingConfirmation -> "Installing…"
                                else -> "View update"
                            },
                        )
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
