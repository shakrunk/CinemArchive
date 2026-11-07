package work.kumarfamilynet.cinemarchive.feature.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.model.MemberStatus
import work.kumarfamilynet.cinemarchive.core.model.OutingMember
import work.kumarfamilynet.cinemarchive.core.model.SharedOutingRules
import work.kumarfamilynet.cinemarchive.data.Friendship
import work.kumarfamilynet.cinemarchive.data.SharedOutingsRepository
import work.kumarfamilynet.cinemarchive.data.rethrowIfCancelled
import work.kumarfamilynet.cinemarchive.data.userMessage

/**
 * "Invite to join" (#206): unlike "Send", which hands a friend a one-time copy, an invitation puts
 * the outing on their Up Next once they accept, and keeps its time, venue and status current.
 * Only accepted friends can be invited; the server enforces it. The owner sees each friend's answer
 * and can take an invitation back.
 */
@Composable
internal fun OutingInviteSection(
    repository: SharedOutingsRepository,
    outingId: String,
    friends: List<Friendship>,
    enabled: Boolean,
) {
    val scope = rememberCoroutineScope()
    var members by remember(repository, outingId) { mutableStateOf<List<OutingMember>?>(null) }
    var error by remember(repository, outingId) { mutableStateOf<String?>(null) }
    val busy = remember(repository, outingId) { mutableStateListOf<String>() }

    LaunchedEffect(repository, outingId) {
        try { members = repository.members(outingId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (cause: Exception) { cause.rethrowIfCancelled(); members = emptyList(); error = cause.userMessage("Couldn't load your invitations.") }
    }

    fun act(friendId: String, failure: String, work: suspend () -> List<OutingMember>) {
        if (friendId in busy) return
        busy += friendId; error = null
        scope.launch {
            try { members = work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) { cause.rethrowIfCancelled(); error = cause.userMessage(failure) }
            finally { busy -= friendId }
        }
    }

    Text("Invite to join", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Text(
        "An invited friend sees this outing on their Up Next once they accept, and it stays up to date if you change the time or venue. " +
            "They never see tickets, booking references or private notes.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val current = members.orEmpty()
    val activeCount = current.count { it.status != MemberStatus.DECLINED }
    friends.forEach { friend ->
        val member = current.firstOrNull { it.userId == friend.friendUserId }
        val invited = member != null && member.status != MemberStatus.DECLINED
        FriendPickerRow(friend.name, action = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (invited) {
                    TextButton(
                        enabled = friend.friendUserId !in busy,
                        onClick = { act(friend.friendUserId, "Couldn't take back that invitation.") {
                            repository.removeMember(member!!.sharedOutingId, friend.friendUserId)
                            repository.members(outingId)
                        } },
                        modifier = Modifier.semantics { contentDescription = "Remove ${friend.name} from this outing" },
                    ) { Text("Remove") }
                } else {
                    TextButton(
                        enabled = enabled && members != null && friend.friendUserId !in busy && activeCount < SharedOutingRules.MAX_INVITEES,
                        onClick = { act(friend.friendUserId, "Couldn't send the invitation.") { repository.invite(outingId, listOf(friend.friendUserId)) } },
                        modifier = Modifier.semantics { contentDescription = "Invite ${friend.name} to this outing" },
                    ) { Text(if (friend.friendUserId in busy) "Inviting…" else if (member != null) "Invite again" else "Invite") }
                }
            }
        }) {
            when (member?.status) {
                MemberStatus.INVITED -> Text("Invited · waiting for an answer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                MemberStatus.ACCEPTED -> Text("Going", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                MemberStatus.DECLINED -> Text("Declined", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                null -> Unit
            }
        }
    }
    if (activeCount >= SharedOutingRules.MAX_INVITEES) {
        Text("An outing can invite up to ${SharedOutingRules.MAX_INVITEES} friends.", style = MaterialTheme.typography.bodySmall)
    }
}
