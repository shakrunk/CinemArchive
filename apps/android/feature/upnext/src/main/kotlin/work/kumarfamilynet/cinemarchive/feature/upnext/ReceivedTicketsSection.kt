package work.kumarfamilynet.cinemarchive.feature.upnext

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.TicketBarcodeImage
import work.kumarfamilynet.cinemarchive.core.model.OpenedTicket
import work.kumarfamilynet.cinemarchive.core.model.ReceivedTicket
import work.kumarfamilynet.cinemarchive.core.model.TicketSendingRules
import work.kumarfamilynet.cinemarchive.data.TicketSharingRepository
import work.kumarfamilynet.cinemarchive.data.rethrowIfCancelled
import work.kumarfamilynet.cinemarchive.data.userMessage

/** Online-only list of tickets friends sent the user (#220); a failure to load just shows the error line. */
@Stable
internal class ReceivedTicketsState(private val repository: TicketSharingRepository?, private val scope: CoroutineScope) {
    var tickets by mutableStateOf<List<ReceivedTicket>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun refresh() {
        val repo = repository ?: return
        scope.launch {
            try { tickets = repo.received(); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) { cause.rethrowIfCancelled(); error = cause.userMessage("Couldn't load tickets from friends.") }
        }
    }
}

@Composable
internal fun rememberReceivedTicketsState(repository: TicketSharingRepository?): ReceivedTicketsState {
    val scope = rememberCoroutineScope()
    val state = remember(repository) { ReceivedTicketsState(repository, scope) }
    LaunchedEffect(state) { state.refresh() }
    return state
}

private val whenFormat = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a")

/** "Tickets from friends": each opens the photo and the code a friend sent. */
@Composable
internal fun ReceivedTicketsSection(state: ReceivedTicketsState, repository: TicketSharingRepository, now: Instant) {
    val tickets = TicketSendingRules.ordered(state.tickets, now)
    if (tickets.isEmpty() && state.error == null) return
    var opening by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("TICKETS FROM FRIENDS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        tickets.forEach { ticket ->
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(listOfNotNull(ticket.title, ticket.year?.toString()).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(whenFormat.withZone(ZoneId.systemDefault()).format(Instant.parse(ticket.showtime)), ticket.venue).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("${ticket.senderName} sent you a ticket", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = { opening = ticket.grantId }) { Text(if (ticket.openedAt == null) "Open ticket" else "Open again") }
                }
            }
        }
    }
    tickets.firstOrNull { it.grantId == opening }?.let { ticket ->
        ReceivedTicketDialog(repository, ticket, onClose = { opening = null; state.refresh() })
    }
}

@Composable
private fun ReceivedTicketDialog(repository: TicketSharingRepository, ticket: ReceivedTicket, onClose: () -> Unit) {
    var opened by remember(ticket.grantId) { mutableStateOf<OpenedTicket?>(null) }
    var photo by remember(ticket.grantId) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var error by remember(ticket.grantId) { mutableStateOf<String?>(null) }

    LaunchedEffect(ticket.grantId) {
        try {
            val details = repository.open(ticket.grantId)
            opened = details
            val bytes = repository.download(details)
            photo = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            if (photo == null) error = "The ticket photo could not be shown."
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (cause: Exception) { cause.rethrowIfCancelled(); error = cause.userMessage("Couldn't open this ticket. Check your connection.") }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(ticket.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("From ${ticket.senderName}", style = MaterialTheme.typography.labelMedium)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val details = opened
                if (details == null && error == null) CircularProgressIndicator()
                photo?.let { Image(it, contentDescription = "Ticket photo", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth()) }
                details?.barcode?.let { TicketBarcodeImage(it) }
                if (details != null && details.barcode == null) Text("No code was read from this ticket; use the photo.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
