package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
 */
@Composable
fun UpdatesSection(
    installSource: InstallSource,
    autoCheckEnabled: Boolean,
    onSetAutoCheck: (Boolean) -> Unit,
    result: UpdateCheckResult,
    canInstallDirectly: Boolean,
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
                    if (result is UpdateCheckResult.Checking) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        // Always available, even with the toggle off — the toggle governs the
                        // automatic check only.
                        OutlinedButton(onClick = onCheckNow) { Text("Check") }
                    }
                }

                if (result is UpdateCheckResult.Available) {
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
