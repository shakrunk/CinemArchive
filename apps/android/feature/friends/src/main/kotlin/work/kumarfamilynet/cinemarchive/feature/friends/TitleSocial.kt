package work.kumarfamilynet.cinemarchive.feature.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import work.kumarfamilynet.cinemarchive.core.model.TitleDetail
import work.kumarfamilynet.cinemarchive.data.FriendsRules
import work.kumarfamilynet.cinemarchive.data.RecommendationDraft

/** Injected only into authenticated owner detail; anonymous shared detail has no social slot. */
@Composable
fun OwnerTitleSocial(source: TitleSocialSource, viewerUserId: String, detail: TitleDetail) {
    if (viewerUserId.isBlank() || !source.isActive()) return
    key(source, viewerUserId, detail.id) {
        var recommending by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp)) {
            detail.tmdbId?.takeIf { it > 0 }?.let {
                OutlinedButton(onClick = { recommending = true }) { Text("Recommend to a friend") }
            }
            TitleSocialSection(source, detail.id, viewerUserId)
        }
        val tmdbId = detail.tmdbId
        if (recommending && tmdbId != null && tmdbId > 0) {
            RecommendDialog(source, viewerUserId,
                RecommendationDraft(tmdbId, detail.type, detail.title, detail.year, detail.posterUrl),
                onDismiss = { recommending = false })
        }
    }
}

@Composable
fun TitleSocialSection(source: TitleSocialSource, titleId: String, viewerUserId: String, modifier: Modifier = Modifier) {
    if (viewerUserId.isBlank() || !source.isActive()) return
    key(source, titleId, viewerUserId) {
        val scope = rememberCoroutineScope()
        val controller = remember { TitleDiscussionController(source, titleId, viewerUserId, scope) }
        DisposableEffect(controller) { onDispose { controller.close() } }
        LaunchedEffect(controller) { controller.load() }
        val state by controller.state.collectAsState()
        Column(modifier.fillMaxWidth()) {
            Text("Reactions", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            if (state.loading) Text("Loading discussion…", style = MaterialTheme.typography.bodySmall)
            val mine = FriendsRules.myReaction(state.reactions, viewerUserId)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FriendsRules.summarize(state.reactions).forEach { reaction ->
                    val action = if (mine == reaction.emoji) "Remove" else "React with"
                    FilterChip(
                        selected = mine == reaction.emoji,
                        enabled = !state.loading && !state.busy,
                        onClick = { controller.react(reaction.emoji) },
                        label = { Text(if (reaction.count > 0) "${reaction.emoji} ${reaction.count}" else reaction.emoji) },
                        modifier = Modifier.semantics {
                            contentDescription = "$action ${reaction.emoji}. ${reaction.count} reactions" +
                                if (reaction.names.isEmpty()) "" else ": ${reaction.names.joinToString()}"
                        },
                    )
                }
            }
            Text("Comments", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp))
            if (!state.loading && state.comments.isEmpty() && state.error == null) {
                Text("No comments yet.", style = MaterialTheme.typography.bodySmall)
            }
            state.comments.forEach { comment ->
                Column(Modifier.padding(top = 10.dp)) {
                    Text(comment.authorName + " · " + discussionDate(comment.createdAt), style = MaterialTheme.typography.labelMedium)
                    Text(comment.body, style = MaterialTheme.typography.bodyMedium)
                    if (comment.authorId == viewerUserId) {
                        TextButton(
                            enabled = !state.busy,
                            onClick = { controller.delete(comment.id) },
                            modifier = Modifier.semantics { contentDescription = "Delete your comment: ${comment.body}" },
                        ) { Text("Delete") }
                    }
                }
            }
            OutlinedTextField(
                value = state.draft,
                onValueChange = controller::draft,
                enabled = !state.busy,
                label = { Text("Add a comment") },
                supportingText = { Text("${state.draft.length}/${FriendsRules.COMMENT_MAX_LENGTH}") },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            Button(enabled = !state.busy && !state.loading && FriendsRules.prepareComment(state.draft) != null,
                onClick = controller::post) { Text(if (state.busy && !state.loading) "Saving…" else "Post") }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !state.busy, onClick = controller::load) { Text("Retry loading discussion") }
            }
        }
    }
}

private fun discussionDate(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)

@Composable
fun RecommendDialog(source: TitleSocialSource, viewerUserId: String, draft: RecommendationDraft, onDismiss: () -> Unit) {
    if (viewerUserId.isBlank() || !source.isActive()) return
    key(source, viewerUserId, draft.tmdbId, draft.type) {
        val scope = rememberCoroutineScope()
        val controller = remember { RecommendationController(source, viewerUserId, draft, scope) }
        DisposableEffect(controller) { onDispose { controller.close() } }
        LaunchedEffect(controller) { controller.load() }
        val state by controller.state.collectAsState()
        var query by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Recommend \"${draft.title}\"") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(note, { note = it.take(280) }, label = { Text("Note (optional)") },
                        supportingText = { Text("${note.length}/280") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(url, { url = it }, label = { Text("Where to watch (optional URL)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(query, { query = it }, label = { Text("Search friends") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (state.loading) Text("Loading friends…")
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = controller::load) { Text("Retry loading friends") }
                    }
                    if (!state.loading && state.error == null && state.friends.isEmpty()) Text("Add a friend first — recommendations go to accepted friends.")
                    val shown = state.friends.filter {
                        it.name.contains(query, ignoreCase = true) || it.username.orEmpty().contains(query, ignoreCase = true)
                    }
                    if (state.friends.isNotEmpty() && shown.isEmpty()) Text("No matching friends.")
                    shown.forEach { friend ->
                        val sending = friend.friendUserId in state.sending
                        val sent = friend.friendUserId in state.sent
                        val error = state.errors[friend.friendUserId]
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(friend.name, modifier = Modifier.weight(1f))
                                TextButton(
                                    enabled = !sending,
                                    onClick = { controller.send(friend.friendUserId, note, url) },
                                    modifier = Modifier.semantics {
                                        contentDescription = if (sent) "Send again to ${friend.name}" else "Send to ${friend.name}"
                                    },
                                ) { Text(if (sending) "Sending…" else if (error != null) "Retry" else if (sent) "Send again" else "Send") }
                            }
                            if (sent) Text("Sent", style = MaterialTheme.typography.bodySmall)
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            },
            confirmButton = { OutlinedButton(onClick = onDismiss) { Text("Done") } },
        )
    }
}
