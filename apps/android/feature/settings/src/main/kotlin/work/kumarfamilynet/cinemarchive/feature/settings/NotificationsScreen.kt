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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import work.kumarfamilynet.cinemarchive.core.designsystem.ReadingWidthColumn
import work.kumarfamilynet.cinemarchive.data.AppNotification
import work.kumarfamilynet.cinemarchive.data.NotificationRoute
import work.kumarfamilynet.cinemarchive.data.NotificationRules
import work.kumarfamilynet.cinemarchive.data.NotificationsRepository
import work.kumarfamilynet.cinemarchive.data.NotificationType
import work.kumarfamilynet.cinemarchive.data.SharedOutingNotification
import work.kumarfamilynet.cinemarchive.data.parseSharedOutingNotification

/** The persistent inbox — web `NotificationCenter`: unread dots, mark one/all read, dismiss,
 *  load-more pagination, and tap routing (title detail / Profile). */
@Composable
fun NotificationsRoute(
    repository: NotificationsRepository,
    onBack: () -> Unit,
    onOpenTitle: (String) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenPostShow: (String) -> Unit,
    onGotTicketsToo: suspend (SharedOutingNotification) -> Unit,
    onAddToCalendar: (SharedOutingNotification) -> Unit,
    showBack: Boolean = true,
) {
    val inbox by repository.inbox.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var actionError by remember { mutableStateOf<String?>(null) }
    var opening by remember { mutableStateOf(false) }
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
                    actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
                                is NotificationRoute.TitleDetail -> if (n.type == NotificationType.OUTING_COMPLETED)
                                    onOpenPostShow(route.titleId) else onOpenTitle(route.titleId)
                                NotificationRoute.Profile -> onOpenProfile()
                                NotificationRoute.Friends -> onOpenFriends()
                                NotificationRoute.None -> Unit
                            }
                        },
                        onDismiss = { scope.launch { repository.delete(n.id) } },
                        actionsEnabled = !opening,
                        onGotTicketsToo = { shared ->
                            if (!opening) {
                                opening = true
                                actionError = null
                                scope.launch {
                                    try {
                                        repository.markRead(n.id)
                                        onGotTicketsToo(shared)
                                    } catch (cancelled: CancellationException) { throw cancelled
                                    } catch (failure: Exception) { actionError = failure.message ?: "Couldn't open these plans. Try again."
                                    } finally { opening = false }
                                }
                            }
                        },
                        onAddToCalendar = { shared ->
                            scope.launch { repository.markRead(n.id) }
                            try { onAddToCalendar(shared) }
                            catch (_: Exception) { actionError = "No calendar app is available to open these plans." }
                        },
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
private fun NotificationRow(
    n: AppNotification, onClick: () -> Unit, onDismiss: () -> Unit,
    actionsEnabled: Boolean,
    onGotTicketsToo: (SharedOutingNotification) -> Unit,
    onAddToCalendar: (SharedOutingNotification) -> Unit,
) {
    val shared = if (n.type == NotificationType.OUTING_PLANS_SHARED) parseSharedOutingNotification(n.payload) else null
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
                shared?.let { snapshot ->
                    Text(listOfNotNull(snapshot.plan.showtime, snapshot.plan.venue, snapshot.plan.seat?.let { "Seat $it" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall)
                    if (snapshot.type == work.kumarfamilynet.cinemarchive.core.model.MediaType.MOVIE) {
                        TextButton(enabled = actionsEnabled, onClick = { onGotTicketsToo(snapshot) }) { Text("I've got tickets too") }
                    }
                    TextButton(onClick = { onAddToCalendar(snapshot) }) { Text("Add to calendar") }
                }
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss notification") }
        }
    }
}
