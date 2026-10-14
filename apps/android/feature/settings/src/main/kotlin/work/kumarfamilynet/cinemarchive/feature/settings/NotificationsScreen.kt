package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.ReadingWidthColumn
import work.kumarfamilynet.cinemarchive.data.AppNotification
import work.kumarfamilynet.cinemarchive.data.NotificationRoute
import work.kumarfamilynet.cinemarchive.data.NotificationRules
import work.kumarfamilynet.cinemarchive.data.NotificationsRepository

/** The persistent inbox — web `NotificationCenter`: unread dots, mark one/all read, dismiss,
 *  load-more pagination, and tap routing (title detail / Profile). */
@Composable
fun NotificationsRoute(
    repository: NotificationsRepository,
    onBack: () -> Unit,
    onOpenTitle: (String) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenFriends: () -> Unit,
    showBack: Boolean = true,
) {
    val inbox by repository.inbox.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { repository.load() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp, 8.dp, 20.dp, 2.dp)) {
            if (showBack) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            }
            Text("Notifications", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp).weight(1f))
            if (inbox.unreadCount > 0) {
                TextButton(onClick = { scope.launch { repository.markAllRead() } }) { Text("Mark all read") }
            }
        }
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 4.dp, 20.dp, 28.dp)) {
            item {
                ReadingWidthColumn {
                    if (inbox.error != null && inbox.items.isEmpty()) {
                        Text(inbox.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { scope.launch { repository.load() } }, modifier = Modifier.padding(top = 8.dp)) { Text("Retry") }
                    } else if (!inbox.loaded) {
                        Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (inbox.items.isEmpty()) {
                        Text("You're all caught up.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(inbox.items, key = { it.id }) { n ->
                ReadingWidthColumn {
                    NotificationRow(
                        n,
                        onClick = {
                            scope.launch { repository.markRead(n.id) }
                            when (val route = NotificationRules.route(n)) {
                                is NotificationRoute.TitleDetail -> onOpenTitle(route.titleId)
                                NotificationRoute.Profile -> onOpenProfile()
                                NotificationRoute.Friends -> onOpenFriends()
                                NotificationRoute.None -> Unit
                            }
                        },
                        onDismiss = { scope.launch { repository.delete(n.id) } },
                    )
                }
            }
            if (inbox.hasMore && inbox.items.isNotEmpty()) {
                item {
                    ReadingWidthColumn {
                        OutlinedButton(onClick = { scope.launch { repository.loadMore() } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text("Load older")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(n: AppNotification, onClick: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp, 8.dp, 4.dp, 8.dp)) {
            Surface(
                shape = CircleShape,
                color = if (n.isUnread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.size(8.dp),
            ) {}
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(NotificationRules.describe(n), style = MaterialTheme.typography.bodyMedium)
                Text(
                    n.createdAt.take(10),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss notification") }
        }
    }
}
