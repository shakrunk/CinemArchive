package work.kumarfamilynet.cinemarchive.feature.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.ChoiceOption
import work.kumarfamilynet.cinemarchive.core.designsystem.ReadingWidthColumn
import work.kumarfamilynet.cinemarchive.core.designsystem.SegmentedGroup
import work.kumarfamilynet.cinemarchive.data.ActivityEvent
import work.kumarfamilynet.cinemarchive.data.ActivityKind
import work.kumarfamilynet.cinemarchive.data.Friendship
import work.kumarfamilynet.cinemarchive.data.FriendshipRelation
import work.kumarfamilynet.cinemarchive.data.FriendsRepository
import work.kumarfamilynet.cinemarchive.data.FriendsRules
import work.kumarfamilynet.cinemarchive.data.InviteConnection
import work.kumarfamilynet.cinemarchive.data.Recommendation

private enum class FriendsSection { FRIENDS, INBOX, ACTIVITY }

/**
 * Friends (web `Friends.tsx`): requests and friendships, suggested friends from invite lineage,
 * the recommendation inbox and the friend activity feed. Everything runs through the same RPCs and
 * RLS as web via [FriendsRepository]; nothing is mirrored into Room.
 */
@Composable
fun FriendsRoute(
    repository: FriendsRepository,
    viewerUserId: String,
    onBack: () -> Unit,
    onOpenFriendLibrary: (friendUserId: String, label: String) -> Unit,
    showBack: Boolean = true,
    onEditFriendAccess: ((friendUserId: String, label: String) -> Unit)? = null,
) {
    var section by remember { mutableStateOf(FriendsSection.FRIENDS) }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp, 8.dp, 20.dp, 2.dp)) {
            if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Friends", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
        }
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            SegmentedGroup(
                options = listOf(
                    ChoiceOption(FriendsSection.FRIENDS, "Friends"),
                    ChoiceOption(FriendsSection.INBOX, "Inbox"),
                    ChoiceOption(FriendsSection.ACTIVITY, "Activity"),
                ),
                selected = section,
                onSelect = { section = it },
            )
        }
        when (section) {
            FriendsSection.FRIENDS -> FriendsList(repository, viewerUserId, onOpenFriendLibrary, onEditFriendAccess)
            FriendsSection.INBOX -> InboxList(repository)
            FriendsSection.ACTIVITY -> ActivityList(repository, onOpenFriendLibrary)
        }
    }
}

@Composable
private fun StatusText(message: String?, isError: Boolean) {
    if (message == null) return
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

private fun CoroutineScope.runAction(
    onStatus: (String, Boolean) -> Unit,
    successMessage: String?,
    reload: suspend () -> Unit,
    block: suspend () -> Unit,
) {
    launch {
        try {
            block()
            if (successMessage != null) onStatus(successMessage, false)
            reload()
        } catch (e: Exception) {
            onStatus(e.message ?: "Something went wrong.", true)
        }
    }
}

@Composable
private fun FriendsList(
    repository: FriendsRepository,
    viewerUserId: String,
    onOpenFriendLibrary: (String, String) -> Unit,
    onEditFriendAccess: ((String, String) -> Unit)?,
) {
    var friendships by remember { mutableStateOf<List<Friendship>>(emptyList()) }
    var suggestions by remember { mutableStateOf<List<InviteConnection>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var email by remember { mutableStateOf("") }
    var confirmBlock by remember { mutableStateOf<Friendship?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        try {
            friendships = repository.listFriendships()
            suggestions = runCatching { repository.listInviteConnections() }.getOrDefault(emptyList())
        } catch (e: Exception) {
            status = (e.message ?: "Couldn't load friends.") to true
        }
        loading = false
    }
    LaunchedEffect(Unit) { reload() }
    val report: (String, Boolean) -> Unit = { m, err -> status = m to err }

    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 4.dp, 20.dp, 28.dp)) {
        item {
            ReadingWidthColumn {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Add a friend by email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = email.isNotBlank(),
                    onClick = {
                        val address = email
                        scope.launch {
                            try {
                                val found = repository.sendFriendRequestByEmail(address)
                                if (found == null) {
                                    status = FriendsRules.NO_USER_FOUND_MESSAGE to true
                                } else {
                                    status = FriendsRules.REQUEST_SENT_MESSAGE to false
                                    email = ""
                                    reload()
                                }
                            } catch (e: Exception) {
                                status = (e.message ?: FriendsRules.SEND_REQUEST_FAILED) to true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Send friend request") }
                StatusText(status?.first, status?.second == true)
            }
        }
        if (loading) item { ReadingWidthColumn { Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        else if (friendships.isEmpty()) item {
            ReadingWidthColumn { Text("No friends yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
        }
        items(friendships, key = { it.friendUserId }) { f ->
            ReadingWidthColumn {
                FriendRow(
                    f,
                    f.relationFor(viewerUserId),
                    onAccept = { scope.runAction(report, null, ::reload) { repository.acceptFriendRequest(f.friendUserId) } },
                    onDecline = { scope.runAction(report, null, ::reload) { repository.declineFriendRequest(f.friendUserId) } },
                    onCancel = { scope.runAction(report, FriendsRules.REQUEST_CANCELLED_MESSAGE, ::reload) { repository.cancelFriendRequest(f.friendUserId) } },
                    onBlock = { confirmBlock = f },
                    onUnblock = { scope.runAction(report, null, ::reload) { repository.unblockFriend(f.friendUserId) } },
                    onViewLibrary = { onOpenFriendLibrary(f.friendUserId, f.libraryLabel) },
                    onEditAccess = onEditFriendAccess?.let { edit -> { edit(f.friendUserId, f.libraryLabel) } },
                )
            }
        }
        if (suggestions.isNotEmpty()) {
            item {
                ReadingWidthColumn {
                    Text("Suggested", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
                }
            }
            items(suggestions, key = { "s-" + it.userId }) { s ->
                ReadingWidthColumn {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp, 8.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(s.displayName?.takeIf { it.isNotBlank() } ?: s.username ?: "Unknown user", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    when (s.connection) {
                                        work.kumarfamilynet.cinemarchive.data.InviteConnectionKind.INVITED_YOU -> "Invited you"
                                        work.kumarfamilynet.cinemarchive.data.InviteConnectionKind.INVITED_BY_YOU -> "You invited them"
                                        else -> ""
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { scope.runAction(report, FriendsRules.REQUEST_SENT_MESSAGE, ::reload) { repository.sendFriendRequest(s.userId) } }) {
                                Text("Add")
                            }
                        }
                    }
                }
            }
        }
    }

    confirmBlock?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmBlock = null },
            title = { Text("Block ${f.name}?") },
            text = { Text(FriendsRules.BLOCK_CONFIRM_MESSAGE) },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlock = null
                    scope.runAction(report, null, ::reload) { repository.blockFriend(f.friendUserId) }
                }) { Text("Block") }
            },
            dismissButton = { TextButton(onClick = { confirmBlock = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun FriendRow(
    f: Friendship,
    relation: FriendshipRelation,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onViewLibrary: () -> Unit,
    onEditAccess: (() -> Unit)?,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(14.dp, 10.dp)) {
            Text(f.name, style = MaterialTheme.typography.titleSmall)
            Text(relation.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                when (relation) {
                    FriendshipRelation.REQUEST_RECEIVED -> {
                        Button(onClick = onAccept) { Text("Accept") }
                        OutlinedButton(onClick = onDecline) { Text("Decline") }
                    }
                    FriendshipRelation.REQUEST_SENT -> OutlinedButton(onClick = onCancel) { Text("Cancel request") }
                    FriendshipRelation.FRIENDS -> {
                        Button(onClick = onViewLibrary) { Text("View library") }
                        if (onEditAccess != null) OutlinedButton(onClick = onEditAccess) { Text("Edit access") }
                        OutlinedButton(onClick = onBlock) { Text("Block") }
                    }
                    FriendshipRelation.BLOCKED_BY_ME -> OutlinedButton(onClick = onUnblock) { Text("Unblock") }
                    FriendshipRelation.UNAVAILABLE, FriendshipRelation.UNKNOWN -> Unit
                }
            }
        }
    }
}

@Composable
private fun InboxList(repository: FriendsRepository) {
    var items by remember { mutableStateOf<List<Recommendation>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun reload() {
        try {
            items = repository.fetchRecommendations().filter { it.status != work.kumarfamilynet.cinemarchive.data.RecommendationStatus.DISMISSED }
        } catch (e: Exception) {
            status = (e.message ?: "Couldn't load your inbox.") to true
        }
        loading = false
    }
    LaunchedEffect(Unit) { reload() }
    val report: (String, Boolean) -> Unit = { m, err -> status = m to err }

    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 4.dp, 20.dp, 28.dp)) {
        item { ReadingWidthColumn { StatusText(status?.first, status?.second == true) } }
        if (loading) item { ReadingWidthColumn { Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        else if (items.isEmpty()) item { ReadingWidthColumn { Text("No recommendations yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        items(items, key = { it.id }) { r ->
            ReadingWidthColumn {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(modifier = Modifier.padding(14.dp, 10.dp)) {
                        Text("${r.senderName} sent you \"${r.title}\"" + (r.year?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.titleSmall)
                        r.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            if (r.isUnread) OutlinedButton(onClick = { scope.runAction(report, null, ::reload) { repository.markRecommendationRead(r.id) } }) { Text("Mark read") }
                            TextButton(onClick = { scope.runAction(report, null, ::reload) { repository.dismissRecommendation(r.id) } }) { Text("Dismiss") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityList(repository: FriendsRepository, onOpenFriendLibrary: (String, String) -> Unit) {
    var events by remember { mutableStateOf<List<ActivityEvent>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load(before: String?) {
        try {
            val page = repository.fetchFriendActivityFeed(before = before)
            events = if (before == null) page else events + page
            hasMore = FriendsRules.hasMore(page.size)
            status = null
        } catch (e: Exception) {
            status = e.message ?: "Couldn't load activity."
        }
        loading = false
    }
    LaunchedEffect(Unit) { load(null) }

    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 4.dp, 20.dp, 28.dp)) {
        item { ReadingWidthColumn { StatusText(status, true) } }
        if (loading) item { ReadingWidthColumn { Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        else if (events.isEmpty() && status == null) item { ReadingWidthColumn { Text("Nothing to show yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        items(events.filter { it.kind != ActivityKind.UNKNOWN }, key = { it.eventAt + it.friendUserId + it.titleId + it.kind.wire }) { e ->
            ReadingWidthColumn {
                Surface(
                    onClick = { onOpenFriendLibrary(e.friendUserId, e.friendName) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Column(modifier = Modifier.padding(14.dp, 10.dp)) {
                        Text("${e.friendName} ${e.kind.verb} \"${e.title}\"", style = MaterialTheme.typography.titleSmall)
                        Text(e.eventAt.take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (hasMore) item {
            ReadingWidthColumn {
                OutlinedButton(onClick = { scope.launch { FriendsRules.nextCursor(events)?.let { load(it) } } }, modifier = Modifier.fillMaxWidth()) { Text("Load older") }
            }
        }
    }
}
