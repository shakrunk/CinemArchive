package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.model.TicketGrant
import work.kumarfamilynet.cinemarchive.core.model.TicketSendingRules
import work.kumarfamilynet.cinemarchive.data.Friendship
import work.kumarfamilynet.cinemarchive.data.TicketSharingRepository
import work.kumarfamilynet.cinemarchive.data.rethrowIfCancelled
import work.kumarfamilynet.cinemarchive.data.userMessage

/**
 * "Send to a friend" (#220): hand an outing's current ticket (the photo and its decoded barcode) to
 * accepted friends. Each row shows whether that friend was sent it and whether they have opened it,
 * and a sent ticket can be taken back. Sending replaces nothing: it is the owner's existing private
 * ticket, readable by that one friend until it is taken back, replaced or they stop being friends.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendTicketSheet(sharing: TicketSharingRepository, outingId: String, titleName: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var friends by remember(sharing) { mutableStateOf<List<Friendship>?>(null) }
    var grants by remember(sharing, outingId) { mutableStateOf<List<TicketGrant>>(emptyList()) }
    var error by remember(sharing, outingId) { mutableStateOf<String?>(null) }
    val busy = remember(sharing, outingId) { mutableStateListOf<String>() }

    LaunchedEffect(sharing, outingId) {
        try { friends = sharing.friends(); grants = sharing.grants(outingId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (cause: Exception) { cause.rethrowIfCancelled(); if (friends == null) friends = emptyList(); error = cause.userMessage("Couldn't load your friends.") }
    }

    fun act(friendId: String, failure: String, work: suspend () -> List<TicketGrant>) {
        if (friendId in busy) return
        busy += friendId; error = null
        scope.launch {
            try { grants = work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) { cause.rethrowIfCancelled(); error = cause.userMessage(failure) }
            finally { busy -= friendId }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Send ticket", style = MaterialTheme.typography.headlineSmall)
            Text(
                "$titleName: a friend you send it to can open the ticket photo and its code in the app until you take it back. " +
                    "Seats, booking references and notes are not shared. You can send it to up to ${TicketSendingRules.MAX_RECIPIENTS} friends.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val list = friends
            when {
                list == null -> CircularProgressIndicator()
                list.isEmpty() -> Text("Add a friend to send them a ticket.")
                else -> list.forEach { friend ->
                    val grant = grants.firstOrNull { it.userId == friend.friendUserId }
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(friend.name, style = MaterialTheme.typography.titleSmall)
                                if (grant != null) Text(
                                    if (grant.openedAt != null) "Sent · opened" else "Sent · not opened yet",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (grant != null) TextButton(
                                enabled = friend.friendUserId !in busy,
                                onClick = { act(friend.friendUserId, "Couldn't take the ticket back.") { sharing.revoke(grant.grantId); sharing.grants(outingId) } },
                                modifier = Modifier.semantics { contentDescription = "Take the ticket back from ${friend.name}" },
                            ) { Text("Take back") }
                            else TextButton(
                                enabled = friend.friendUserId !in busy && grants.size < TicketSendingRules.MAX_RECIPIENTS,
                                onClick = { act(friend.friendUserId, "Couldn't send the ticket.") { sharing.send(outingId, listOf(friend.friendUserId)) } },
                                modifier = Modifier.semantics { contentDescription = "Send the ticket to ${friend.name}" },
                            ) { Text(if (friend.friendUserId in busy) "Sending…" else "Send") }
                        }
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}
