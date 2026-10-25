package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import work.kumarfamilynet.cinemarchive.core.model.ApkInstallState
import work.kumarfamilynet.cinemarchive.core.model.UpdateCheckResult

/**
 * Whether the home banner shows, given the shared check, the install progress and the version
 * the user last dismissed. An update under way always shows — dismissing applies only to the
 * plain "available" announcement, and only for that one version.
 */
fun shouldShowUpdateBanner(
    result: UpdateCheckResult,
    installState: ApkInstallState,
    dismissedVersion: String?,
): Boolean {
    if (result !is UpdateCheckResult.Available) return false
    val inProgress = installState !is ApkInstallState.Idle && installState !is ApkInstallState.Failed
    return inProgress || result.latestVersion != dismissedVersion
}

/**
 * A slim card at the top of every tab while an update is available or under way, so finding
 * one doesn't depend on visiting Settings → About. Tapping it opens [UpdateSheet].
 */
@Composable
fun UpdateBanner(
    result: UpdateCheckResult.Available,
    installState: ApkInstallState,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = when (installState) {
        is ApkInstallState.Downloading ->
            "Downloading ${result.latestVersion}" + (installState.fraction?.let { " · ${(it * 100).toInt()}%" } ?: "…")
        is ApkInstallState.Ready -> "CinemArchive ${result.latestVersion} is ready to install"
        ApkInstallState.Committing, ApkInstallState.AwaitingConfirmation -> "Installing ${result.latestVersion}…"
        else -> "CinemArchive ${result.latestVersion} is available"
    }
    val dismissible = installState is ApkInstallState.Idle || installState is ApkInstallState.Failed

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onOpen).padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        ) {
            Icon(Icons.Filled.SystemUpdate, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            TextButton(onClick = onOpen) { Text(if (installState is ApkInstallState.Ready) "Install" else "View") }
            if (dismissible) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss until the next release")
                }
            }
        }
    }
}
