package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.data.LegacyArchiveStatus
import work.kumarfamilynet.cinemarchive.data.LegacyRestoreResult

/**
 * Offered when a pre-account-separation database holding unsynced changes and/or local-only items
 * exists on this device and hasn't been finished by this account (or claimed by another one). It
 * shows COUNTS ONLY — never titles or any content — because the archive's owner is unknown and it
 * may belong to someone other than whoever is signed in. Restoring is ONE explicit confirmation
 * that these items are the signed-in user's; nothing is ever adopted automatically, and the
 * original archive file is never modified or deleted. Unsynced changes are copied and uploaded;
 * local-only items are kept on this phone and are not uploaded (whether they ever reached the
 * server cannot be known).
 */
@Composable
fun LegacyRecoverySection(
    loadStatus: suspend () -> LegacyArchiveStatus,
    restore: suspend (includeLocalOnly: Boolean) -> LegacyRestoreResult,
) {
    var status by remember { mutableStateOf<LegacyArchiveStatus?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { status = runCatching { loadStatus() }.getOrNull() }

    val current = status
    val offered = current != null && current.present && !current.claimed &&
        (current.pendingChanges > 0 || current.localOnlyItems > 0 || current.skippedRows > 0)
    if (current == null || !offered) {
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp)) }
        return
    }

    val pending = current.pendingChanges
    val localOnly = current.localOnlyItems
    val skipped = current.skippedRows
    val retryOnly = pending == 0 && localOnly == 0

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                if (retryOnly) "Some items could not be restored yet" else "Unrecovered data found on this device",
                style = MaterialTheme.typography.titleSmall,
            )
            val parts = buildList {
                if (pending > 0) add("$pending unsynced change${plural(pending)} that never reached the server")
                if (localOnly > 0) add("$localOnly item${plural(localOnly)} that exist only on this phone")
            }
            if (parts.isNotEmpty()) {
                Text(
                    "Found from before accounts were kept separate: ${parts.joinToString(" and ")}. " +
                        "Restore them only if they were made with this account.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (skipped > 0) {
                Text(
                    "$skipped item${plural(skipped)} couldn't be restored yet. You can retry after the next sync.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            OutlinedButton(onClick = { confirming = true }, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) {
                Text(if (busy) "Restoring…" else if (retryOnly) "Retry" else "Review & restore")
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(if (retryOnly) "Retry restoring?" else "Restore to this account?") },
            text = {
                Column {
                    Text(
                        "These were saved on this phone before each account had its own storage, so we can't tell whose they are. " +
                            "Restoring copies them into the account you're signed in as now, on this phone. " +
                            "Only continue if this is the account you used then. The original file is kept either way.",
                    )
                    if (pending > 0) {
                        Text(
                            "$pending unsynced change${plural(pending)} will be restored and uploaded.",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (localOnly > 0) {
                        Text(
                            "$localOnly other item${plural(localOnly)} will be kept on this phone; they will not be uploaded.",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    busy = true
                    scope.launch {
                        message = when (val result = restore(localOnly > 0)) {
                            is LegacyRestoreResult.Success ->
                                "Restored ${result.restoredOutboxEntries} change(s)" +
                                    (if (result.skippedRows > 0) "; ${result.skippedRows} item(s) couldn't be restored yet, retry after the next sync." else ".")
                            is LegacyRestoreResult.Failure -> "Couldn't restore: ${result.message}"
                        }
                        status = runCatching { loadStatus() }.getOrNull()
                        busy = false
                    }
                }) { Text("Restore to this account") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Not now") } },
        )
    }
}

private fun plural(n: Int) = if (n == 1) "" else "s"
