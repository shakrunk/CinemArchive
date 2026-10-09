package work.kumarfamilynet.cinemarchive.feature.friends

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import work.kumarfamilynet.cinemarchive.data.OutingPlansSource
import work.kumarfamilynet.cinemarchive.data.PublicOutingPlan
import work.kumarfamilynet.cinemarchive.core.model.CinemaFormat

fun outingPlanText(plan: PublicOutingPlan): String = listOfNotNull(
    "🎬 ${plan.title}",
    DateTimeFormatter.ofPattern("EEE MMM d, h:mm a").withZone(ZoneId.systemDefault()).format(Instant.parse(plan.showtime)),
    plan.venue,
    when (plan.format) {
        null, CinemaFormat.STANDARD -> null
        CinemaFormat.IMAX -> "IMAX"
        CinemaFormat.THREE_D -> "3D"
        CinemaFormat.DOLBY -> "Dolby"
        CinemaFormat.SEVENTY_MM -> "70mm"
        CinemaFormat.DRIVE_IN -> "Drive-in"
        CinemaFormat.OTHER -> "Other"
    },
    plan.seat?.let { "I'm in $it — grab a seat nearby!" },
).joinToString(" · ")

fun outingCalendarIntent(plan: PublicOutingPlan): Intent = Intent(Intent.ACTION_INSERT)
    .setData(CalendarContract.Events.CONTENT_URI)
    .putExtra(CalendarContract.Events.TITLE, plan.title)
    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, Instant.parse(plan.showtime).toEpochMilli())
    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, Instant.parse(plan.endsAt).toEpochMilli())
    .putExtra(CalendarContract.Events.EVENT_LOCATION, plan.venue)
    .putExtra(CalendarContract.Events.DESCRIPTION, listOfNotNull(
        plan.companions.takeIf { it.isNotEmpty() }?.joinToString(prefix = "With "), plan.seat,
    ).joinToString("\n"))

@Composable
fun OutingPlansDialog(
    source: OutingPlansSource, outingId: String, onDismiss: () -> Unit,
    onShare: ((PublicOutingPlan) -> Unit)? = null,
    onCalendar: ((PublicOutingPlan) -> Unit)? = null,
) {
    if (!source.isActive()) return
    key(source, outingId) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val controller = remember { OutingPlansController(source, outingId, scope) }
        DisposableEffect(controller) { onDispose { controller.close() } }
        LaunchedEffect(controller) { controller.load() }
        val state by controller.state.collectAsState()
        var query by remember { mutableStateOf("") }
        var copied by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Share outing plans") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.loading) Text("Loading current plans…")
                    state.plan?.let { Text(outingPlanText(it)) }
                    Text("Friends receive a snapshot. Share again if your plans change. Tickets, booking references and private notes stay private.", style = MaterialTheme.typography.bodySmall)
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = controller::load, enabled = !state.loading && !state.preparing) { Text("Retry loading plans") }
                    }
                    if (state.plan != null) {
                        TextButton(enabled = !state.preparing, onClick = { controller.prepareExternal { plan ->
                            if (onShare != null) onShare(plan) else context.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, outingPlanText(plan)), "Share outing plans"))
                        } }) { Text("Share text") }
                        TextButton(enabled = !state.preparing, onClick = { controller.prepareExternal { plan ->
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Outing plans", outingPlanText(plan)))
                            copied = true
                        } }) { Text(if (copied) "Copied" else "Copy text") }
                        TextButton(enabled = !state.preparing, onClick = { controller.prepareExternal { plan ->
                            if (onCalendar != null) onCalendar(plan) else context.startActivity(outingCalendarIntent(plan))
                        } }) { Text("Add to calendar") }
                    }
                    OutlinedTextField(query, { query = it }, label = { Text("Search friends") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (!state.loading && state.error == null && state.friends.isEmpty()) Text("Add a friend to share plans in the app.")
                    val shown = state.friends.filter { it.name.contains(query, true) || it.username.orEmpty().contains(query, true) }
                    if (state.friends.isNotEmpty() && shown.isEmpty()) Text("No matching friends.")
                    shown.forEach { friend ->
                        Column {
                            Row {
                                Text(friend.name, modifier = Modifier.weight(1f))
                                TextButton(enabled = state.plan != null && friend.friendUserId !in state.sending && !state.loading,
                                    onClick = { controller.send(friend.friendUserId) },
                                    modifier = Modifier.semantics { contentDescription = "Share plans with ${friend.name}" }) {
                                    Text(if (friend.friendUserId in state.sending) "Sending…" else if (friend.friendUserId in state.failures) "Retry" else if (friend.friendUserId in state.sent) "Share again" else "Send")
                                }
                            }
                            if (friend.friendUserId in state.sent) Text("Plans shared", style = MaterialTheme.typography.bodySmall)
                            state.delivered[friend.friendUserId]?.let { Text(outingPlanText(it), style = MaterialTheme.typography.bodySmall) }
                            state.failures[friend.friendUserId]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        )
    }
}
