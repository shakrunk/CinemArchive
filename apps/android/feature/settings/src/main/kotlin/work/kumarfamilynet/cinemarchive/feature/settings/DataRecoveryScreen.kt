package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import work.kumarfamilynet.cinemarchive.core.designsystem.ReadingWidthColumn
import work.kumarfamilynet.cinemarchive.data.LegacyArchiveStatus
import work.kumarfamilynet.cinemarchive.data.LegacyRestoreResult

/**
 * Maintenance tools that used to be inlined at the bottom of Profile: legacy-archive recovery,
 * the saved-change recovery panels (outings, lifecycle, lists, titles, viewings, tickets) and the
 * catalog refresh. They are rarely needed, so they live one tap away rather than between the
 * settings rows and Sign out. Each panel renders nothing unless it has something to show.
 */
@Composable
fun DataRecoveryRoute(
    legacyLoadStatus: suspend () -> LegacyArchiveStatus,
    legacyRestore: suspend (Boolean) -> LegacyRestoreResult,
    onBack: () -> Unit,
    showBack: Boolean = true,
    outingRecovery: (@Composable () -> Unit)? = null,
    lifecycleChangesContent: (@Composable () -> Unit)? = null,
    listChangesContent: (@Composable () -> Unit)? = null,
    titleChangesContent: (@Composable () -> Unit)? = null,
    viewingChangesContent: (@Composable () -> Unit)? = null,
    ticketChangesContent: (@Composable () -> Unit)? = null,
    catalogRefreshContent: (@Composable () -> Unit)? = null,
) {
    SettingsPanelScreen(
        title = "Data & recovery",
        description = "Unsynced changes, restore tools and catalog refresh.",
        onBack = onBack,
        showBack = showBack,
    ) {
        LegacyRecoverySection(legacyLoadStatus, legacyRestore)
        outingRecovery?.invoke()
        lifecycleChangesContent?.invoke()
        listChangesContent?.invoke()
        titleChangesContent?.invoke()
        viewingChangesContent?.invoke()
        ticketChangesContent?.invoke()
        catalogRefreshContent?.invoke()
    }
}

/** A settings sub-screen that hosts an existing panel under a standard back-arrow header. */
@Composable
fun SettingsPanelScreen(
    title: String,
    description: String,
    onBack: () -> Unit,
    showBack: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp, 8.dp, 20.dp, 2.dp)) {
            if (showBack) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
        }
        LazyColumn(contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 28.dp), modifier = Modifier.weight(1f)) {
            item {
                ReadingWidthColumn {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    content()
                }
            }
        }
    }
}
