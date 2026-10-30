package work.kumarfamilynet.cinemarchive.feature.upnext

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.model.LibraryTitle
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.OutingPoll
import work.kumarfamilynet.cinemarchive.core.model.PollCandidate
import work.kumarfamilynet.cinemarchive.core.model.PollOption
import work.kumarfamilynet.cinemarchive.core.model.PollRules
import work.kumarfamilynet.cinemarchive.core.model.PollStatus
import work.kumarfamilynet.cinemarchive.data.Friendship
import work.kumarfamilynet.cinemarchive.data.OutingPollsRepository
import work.kumarfamilynet.cinemarchive.data.rethrowIfCancelled
import work.kumarfamilynet.cinemarchive.data.userMessage

private val whenFormat = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a")

private fun PollOption.whenLabel(): String = listOfNotNull(
    whenFormat.withZone(ZoneId.systemDefault()).format(Instant.parse(showtime)), venue,
).joinToString(" · ")

/**
 * Polls with friends (#203): see the polls you started or were asked into, tick every option that
 * works for you, and, as the owner, close on a winner and turn it into a real outing with
 * [onSchedule] (which gets the winner and the friends who ticked it). Online-only.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OutingPollsSheet(
    repository: OutingPollsRepository,
    movies: List<LibraryTitle>,
    onSchedule: (PollOption, List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var polls by remember(repository) { mutableStateOf<List<OutingPoll>?>(null) }
    var error by remember(repository) { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val busy = remember(repository) { mutableStateListOf<String>() }

    fun refresh() {
        scope.launch {
            try { polls = repository.list(); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) { cause.rethrowIfCancelled(); if (polls == null) polls = emptyList(); error = cause.userMessage("Couldn't load your polls. Check your connection.") }
        }
    }
    LaunchedEffect(repository) { refresh() }

    fun act(pollId: String, failure: String, work: suspend () -> Unit) {
        if (pollId in busy) return
        busy += pollId; error = null
        scope.launch {
            try { work(); polls = repository.list() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) { cause.rethrowIfCancelled(); error = cause.userMessage(failure) }
            finally { busy -= pollId }
        }
    }

    val now = Instant.now()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (creating) {
                NewPollForm(
                    repository, movies,
                    onCancel = { creating = false },
                    onCreated = { creating = false; refresh() },
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Polls with friends", style = MaterialTheme.typography.headlineSmall)
                    Button(onClick = { creating = true }) { Text("New poll") }
                }
                Text(
                    "Offer a few movies and times; friends tick every one that works for them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val list = polls
                when {
                    list == null -> CircularProgressIndicator()
                    list.isEmpty() -> Text("No polls yet.")
                    else -> list.forEach { poll ->
                        PollCard(
                            poll, now, poll.id in busy,
                            onVote = { optionIds -> act(poll.id, "Couldn't save your votes.") { repository.vote(poll.id, optionIds) } },
                            onClose = { optionId -> act(poll.id, "Couldn't close the poll.") { repository.close(poll.id, optionId) } },
                            onSchedule = onSchedule,
                        )
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(androidx.compose.ui.Alignment.End)) { Text("Close") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PollCard(
    poll: OutingPoll,
    now: Instant,
    busy: Boolean,
    onVote: (List<String>) -> Unit,
    onClose: (String?) -> Unit,
    onSchedule: (PollOption, List<String>) -> Unit,
) {
    val open = poll.status == PollStatus.OPEN
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(poll.question, style = MaterialTheme.typography.titleMedium)
            Text(
                if (poll.isOwner) "Your poll · ${poll.members.size} invited" else "From ${poll.ownerName}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            poll.options.forEach { option ->
                val canTick = open && PollRules.isUpcoming(option, now)
                val isWinner = option.id == poll.winnerOptionId
                Row(Modifier.fillMaxWidth().then(if (canTick) Modifier.clickable(enabled = !busy) {
                    val ticked = poll.options.filter { it.mine }.map { it.id }.toMutableSet()
                    if (!ticked.add(option.id)) ticked.remove(option.id)
                    onVote(ticked.toList())
                } else Modifier), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (open) Checkbox(
                        checked = option.mine, enabled = canTick && !busy, onCheckedChange = null,
                        modifier = Modifier.semantics { contentDescription = "Works for me: ${option.title}" },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            listOfNotNull(option.title, option.year?.toString()).joinToString(" · ") + if (isWinner) "  ✓ chosen" else "",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(option.whenLabel(), style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (option.votes == 0) "No votes yet" else "${option.votes} can make it: ${option.voters.joinToString()}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (open && poll.isOwner && PollRules.isUpcoming(option, now)) TextButton(
                        enabled = !busy, onClick = { onClose(option.id) },
                        modifier = Modifier.semantics { contentDescription = "Pick ${option.title} at ${option.whenLabel()}" },
                    ) { Text("Pick") }
                }
            }
            if (open) {
                val leaders = PollRules.leaders(poll, now)
                if (leaders.isNotEmpty()) Text("Leading: ${leaders.joinToString { it.title }}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (!PollRules.canVote(poll, now)) Text("Every showtime has passed.", style = MaterialTheme.typography.bodySmall)
                if (poll.isOwner) TextButton(enabled = !busy, onClick = { onClose(null) }) { Text("Cancel poll") }
            }
            if (poll.status == PollStatus.CANCELLED) Text("Cancelled", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            if (poll.status == PollStatus.CLOSED) poll.winner?.let { winner ->
                Text("Chosen: ${winner.title} · ${winner.whenLabel()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (poll.isOwner) FlowRow {
                    OutlinedButton(onClick = { onSchedule(winner, winner.voters) }) { Text("Schedule this") }
                }
            }
        }
    }
}

private class DraftOption {
    var title by mutableStateOf<LibraryTitle?>(null)
    var date by mutableStateOf<LocalDate?>(null)
    var time by mutableStateOf<LocalTime?>(null)
    var venue by mutableStateOf("")

    fun candidate(zone: ZoneId): PollCandidate? {
        val t = title ?: return null
        val d = date ?: return null
        val at = time ?: return null
        return PollCandidate(t.id, d.atTime(at).atZone(zone).toInstant(), venue.trim().takeIf { it.isNotEmpty() })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewPollForm(
    repository: OutingPollsRepository,
    movies: List<LibraryTitle>,
    onCancel: () -> Unit,
    onCreated: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    var question by rememberSaveable { mutableStateOf("") }
    val drafts = remember { mutableStateListOf(DraftOption(), DraftOption()) }
    var friends by remember { mutableStateOf<List<Friendship>?>(null) }
    val chosen = remember { mutableStateListOf<String>() }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickingMovieFor by remember { mutableStateOf<DraftOption?>(null) }
    var pickingDateFor by remember { mutableStateOf<DraftOption?>(null) }
    var pickingTimeFor by remember { mutableStateOf<DraftOption?>(null) }

    LaunchedEffect(repository) {
        try { friends = repository.friends() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (cause: Exception) { cause.rethrowIfCancelled(); friends = emptyList(); error = cause.userMessage("Couldn't load your friends.") }
    }

    val candidates = drafts.mapNotNull { it.candidate(zone) }
    val problem = if (candidates.size != drafts.size) "Choose a movie, a date and a time for every option."
        else PollRules.problem(question, candidates, chosen.size, Instant.now())

    Text("New poll", style = MaterialTheme.typography.headlineSmall)
    OutlinedTextField(question, { question = it.take(PollRules.MAX_QUESTION) }, label = { Text("Question (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    drafts.forEachIndexed { index, draft ->
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Option ${index + 1}", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = { pickingMovieFor = draft }, modifier = Modifier.fillMaxWidth()) {
                    Text(draft.title?.let { listOfNotNull(it.name, it.year?.toString()).joinToString(" · ") } ?: "Choose a movie")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickingDateFor = draft }) { Text(draft.date?.toString() ?: "Date") }
                    OutlinedButton(onClick = { pickingTimeFor = draft }) { Text(draft.time?.toString()?.take(5) ?: "Time") }
                }
                OutlinedTextField(draft.venue, { draft.venue = it.take(PollRules.MAX_VENUE) }, label = { Text("Theater (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (drafts.size > PollRules.MIN_OPTIONS) TextButton(onClick = { drafts.removeAt(index) }) { Text("Remove option") }
            }
        }
    }
    if (drafts.size < PollRules.MAX_OPTIONS) TextButton(onClick = { drafts.add(DraftOption()) }) { Text("Add another option") }

    Text("Ask", style = MaterialTheme.typography.titleSmall)
    when (val list = friends) {
        null -> CircularProgressIndicator()
        else -> {
            if (list.isEmpty()) Text("Add a friend to poll them.")
            list.forEach { friend ->
                Row(Modifier.fillMaxWidth().clickable {
                    if (friend.friendUserId in chosen) chosen.remove(friend.friendUserId)
                    else if (chosen.size < PollRules.MAX_FRIENDS) chosen.add(friend.friendUserId)
                }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = friend.friendUserId in chosen, onCheckedChange = null, modifier = Modifier.semantics { contentDescription = "Ask ${friend.name}" })
                    Text(friend.name, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (problem != null && (candidates.isNotEmpty() || chosen.isNotEmpty())) Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCancel, enabled = !sending) { Text("Back") }
        Button(
            enabled = !sending && problem == null,
            onClick = {
                sending = true; error = null
                scope.launch {
                    try { repository.create(question, candidates, chosen.toList()); onCreated() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (cause: Exception) { cause.rethrowIfCancelled(); error = cause.userMessage("Couldn't start the poll.") }
                    finally { sending = false }
                }
            },
        ) { Text(if (sending) "Sending…" else "Send poll") }
    }

    pickingMovieFor?.let { draft -> MoviePickerDialog(movies, onPick = { draft.title = it; pickingMovieFor = null }, onDismiss = { pickingMovieFor = null }) }
    pickingDateFor?.let { draft ->
        val state = rememberDatePickerState(initialSelectedDateMillis = (draft.date ?: LocalDate.now().plusDays(1)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDateFor = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { draft.date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDateFor = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDateFor = null }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
    pickingTimeFor?.let { draft ->
        val state = rememberTimePickerState(initialHour = draft.time?.hour ?: 19, initialMinute = draft.time?.minute ?: 0, is24Hour = false)
        AlertDialog(
            onDismissRequest = { pickingTimeFor = null },
            confirmButton = { TextButton(onClick = { draft.time = LocalTime.of(state.hour, state.minute); pickingTimeFor = null }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickingTimeFor = null }) { Text("Cancel") } },
            text = { TimePicker(state = state) },
        )
    }
}

@Composable
private fun MoviePickerDialog(movies: List<LibraryTitle>, onPick: (LibraryTitle) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = remember(movies, query) {
        movies.filter { it.type == MediaType.MOVIE && it.name.contains(query.trim(), ignoreCase = true) }.take(30)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a movie") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Search your movies") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (matches.isEmpty()) Text("No movies match.")
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(matches, key = { it.id }) { movie ->
                        Text(
                            listOfNotNull(movie.name, movie.year?.toString()).joinToString(" · "),
                            modifier = Modifier.fillMaxWidth().clickable { onPick(movie) }.padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
