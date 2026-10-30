package work.kumarfamilynet.cinemarchive.feature.upnext

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.model.MemberStatus
import work.kumarfamilynet.cinemarchive.core.model.SharedOuting
import work.kumarfamilynet.cinemarchive.core.model.SharedOutingRules
import work.kumarfamilynet.cinemarchive.core.model.SharedOutingStatus
import work.kumarfamilynet.cinemarchive.data.SharedOutingsRepository
import work.kumarfamilynet.cinemarchive.data.rethrowIfCancelled
import work.kumarfamilynet.cinemarchive.data.userMessage

/** Online-only list of friends' outings (#206); a failure to load simply shows nothing but the error line. */
@Stable
internal class SharedOutingsState(private val repository: SharedOutingsRepository?, private val scope: CoroutineScope) {
    var outings by mutableStateOf<List<SharedOuting>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val busy = mutableStateListOf<String>()

    fun refresh() {
        val repo = repository ?: return
        scope.launch {
            try { outings = repo.list(); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) { cause.rethrowIfCancelled(); error = cause.userMessage("Couldn't load invitations from friends.") }
        }
    }

    fun respond(id: String, accept: Boolean) {
        val repo = repository ?: return
        if (id in busy) return
        busy += id; error = null
        scope.launch {
            try { repo.respond(id, accept); outings = repo.list() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) { cause.rethrowIfCancelled(); error = cause.userMessage("Couldn't send your answer. Try again.") }
            finally { busy -= id }
        }
    }
}

@Composable
internal fun rememberSharedOutingsState(repository: SharedOutingsRepository?): SharedOutingsState {
    val scope = rememberCoroutineScope()
    val state = remember(repository) { SharedOutingsState(repository, scope) }
    LaunchedEffect(state) { state.refresh() }
    return state
}

private val whenFormat = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a")

/** "Shared with you": invitations to answer, then outings the user is going to. */
@Composable
internal fun SharedOutingsSection(
    outings: List<SharedOuting>,
    busy: Collection<String>,
    error: String?,
    now: Instant,
    onRespond: (String, Boolean) -> Unit,
) {
    val invitations = SharedOutingRules.invitations(outings, now)
    val going = SharedOutingRules.going(outings)
    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SHARED WITH YOU", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        invitations.forEach { outing ->
            SharedOutingCard(outing) {
                Text("${outing.ownerName} invited you", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = outing.id !in busy,
                        onClick = { onRespond(outing.id, true) },
                        modifier = Modifier.semantics { contentDescription = "Accept invitation to ${outing.title}" },
                    ) { Text("I'm in") }
                    OutlinedButton(
                        enabled = outing.id !in busy,
                        onClick = { onRespond(outing.id, false) },
                        modifier = Modifier.semantics { contentDescription = "Decline invitation to ${outing.title}" },
                    ) { Text("Can't make it") }
                }
            }
        }
        going.forEach { outing ->
            SharedOutingCard(outing) {
                if (outing.status == SharedOutingStatus.CANCELLED) {
                    Text("Cancelled by ${outing.ownerName}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                } else {
                    Text("You're going with ${outing.ownerName}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (SharedOutingRules.canRespond(outing, now)) TextButton(
                        enabled = outing.id !in busy,
                        onClick = { onRespond(outing.id, false) },
                        modifier = Modifier.semantics { contentDescription = "Leave ${outing.title}" },
                    ) { Text("Can't make it after all") }
                }
            }
        }
    }
}

@Composable
private fun SharedOutingCard(outing: SharedOuting, footer: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                listOfNotNull(outing.title, outing.year?.toString()).joinToString(" · "),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                listOfNotNull(
                    whenFormat.withZone(ZoneId.systemDefault()).format(Instant.parse(outing.showtime)),
                    outing.venue,
                    outing.format?.takeIf { it.wireValue != "Standard" }?.wireValue,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (outing.companions.isNotEmpty()) Text(
                "With ${outing.companions.joinToString()}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            footer()
        }
    }
}
