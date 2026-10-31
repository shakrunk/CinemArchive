package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.model.GroupReactionActions
import work.kumarfamilynet.cinemarchive.core.model.OutingReaction
import work.kumarfamilynet.cinemarchive.core.model.ReactionRules

/**
 * How it went, for the whole group (#207): everyone's rating and note on a shared outing, and the
 * caller's own to add, change or take back. Sharing is a deliberate button press and is separate from
 * the private viewing log, so [initialRating] and [initialNote] only prefill the fields; nothing is
 * shared until "Share with the group".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupReactionsSection(
    actions: GroupReactionActions,
    modifier: Modifier = Modifier,
    initialRating: Double? = null,
    initialNote: String? = null,
) {
    val scope = rememberCoroutineScope()
    var reactions by remember(actions) { mutableStateOf<List<OutingReaction>?>(null) }
    var error by remember(actions) { mutableStateOf<String?>(null) }
    var busy by remember(actions) { mutableStateOf(false) }
    var rating by rememberSaveable { mutableStateOf(initialRating ?: 0.0) }
    var note by rememberSaveable { mutableStateOf(initialNote.orEmpty()) }
    var prefilledFromMine by remember(actions) { mutableStateOf(false) }

    LaunchedEffect(actions) {
        try { reactions = actions.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { reactions = emptyList(); error = "Couldn't load the group's reactions. Check your connection." }
    }
    val mine = reactions?.firstOrNull { it.isMe }
    // Once, show what was already shared rather than the prefill from the private log.
    LaunchedEffect(mine) {
        if (mine != null && !prefilledFromMine) {
            prefilledFromMine = true
            rating = mine.rating ?: 0.0
            note = mine.note.orEmpty()
        }
    }

    fun run(failure: String, work: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { work(); reactions = actions.load() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) { error = cause.message?.takeIf { it.length < 120 } ?: failure }
            finally { busy = false }
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("HOW IT WENT FOR THE GROUP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        val list = reactions
        if (list == null) CircularProgressIndicator()
        else {
            val others = list.filterNot { it.isMe }
            if (others.isEmpty()) Text("Nobody else has shared yet.", style = MaterialTheme.typography.bodySmall)
            others.forEach { reaction ->
                Column {
                    Text(
                        listOfNotNull(reaction.name, reaction.rating?.let { "★ %.1f".format(it) }).joinToString(" · "),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    reaction.note?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        Text("Your reaction", style = MaterialTheme.typography.titleSmall)
        Text(
            "Shared only with the people on this outing. It is separate from your own rating and notes.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DraggableStarRating(rating, { rating = it })
        OutlinedTextField(
            note, { note = it.take(ReactionRules.MAX_NOTE) }, label = { Text("A note for the group") },
            enabled = !busy, minLines = 2, modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy && reactions != null && ReactionRules.canShare(rating.takeIf { it > 0 }, note),
                onClick = { run("Couldn't share your reaction.") { actions.share(rating.takeIf { it > 0 }, note) } },
            ) { Text(if (busy) "Sharing…" else if (mine != null) "Update" else "Share with the group") }
            if (mine != null) TextButton(
                enabled = !busy,
                onClick = { run("Couldn't take your reaction back.") { actions.withdraw(); rating = 0.0; note = ""; prefilledFromMine = false } },
            ) { Text("Take mine back") }
            if (rating > 0) TextButton(enabled = !busy, onClick = { rating = 0.0 }) { Text("Clear rating") }
        }
    }
}

/**
 * The panel for an outing that may or may not be shared: [resolve] finds the shared outing (a network
 * call) and returns its actions, or null when the outing was never shared, in which case nothing shows.
 */
@Composable
fun ResolvedGroupReactions(
    resolve: suspend () -> GroupReactionActions?,
    modifier: Modifier = Modifier,
    initialRating: Double? = null,
    initialNote: String? = null,
) {
    var actions by remember { mutableStateOf<GroupReactionActions?>(null) }
    LaunchedEffect(Unit) {
        actions = try { resolve() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    }
    actions?.let { GroupReactionsSection(it, modifier, initialRating, initialNote) }
}
