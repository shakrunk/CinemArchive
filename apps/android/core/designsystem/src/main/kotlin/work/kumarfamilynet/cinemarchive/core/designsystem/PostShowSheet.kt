package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.PostShowOpening
import work.kumarfamilynet.cinemarchive.core.model.ViewingDraft

/** Captured evidence and labels survive recreation without consulting a newer owner or row. */
data class SavedPostShow(val titleId: String, val titleName: String, val opening: PostShowOpening, val followUpOutingId: String?)

fun savePostShow(owner: String?, titleId: String, titleName: String, opening: PostShowOpening, followUpOutingId: String? = null): String {
    val viewing = opening.viewing
    return JSONObject().put("owner", owner ?: JSONObject.NULL).put("titleId", titleId).put("titleName", titleName)
        .put("id", viewing.id).put("date", viewing.date ?: JSONObject.NULL).put("rating", viewing.rating ?: JSONObject.NULL)
        .put("notes", viewing.notes ?: JSONObject.NULL).put("venue", viewing.venue ?: JSONObject.NULL)
        .put("companions", JSONArray(viewing.companions)).put("opening", viewing.openingContext ?: JSONObject.NULL)
        .put("reversal", opening.reversalContext ?: JSONObject.NULL).put("outingId", followUpOutingId ?: JSONObject.NULL).toString()
}

fun restorePostShow(raw: String?, owner: String?, expectedTitleId: String? = null): SavedPostShow? = runCatching {
    if (raw == null) return null
    val json = JSONObject(raw)
    fun text(key: String) = if (json.isNull(key)) null else json.getString(key)
    require(text("owner") == owner)
    val titleId = json.getString("titleId")
    require(expectedTitleId == null || expectedTitleId == titleId)
    SavedPostShow(titleId, json.getString("titleName"), PostShowOpening(ViewingDraft(json.getString("id"), text("date"),
        if (json.isNull("rating")) null else json.getDouble("rating"), text("notes"), text("venue"),
        json.getJSONArray("companions").let { array -> (0 until array.length()).map(array::getString) }, text("opening")), text("reversal")), text("outingId"))
}.getOrNull()

/** Changes stay local until Save; an uncertain attempt retains its exact action and values. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostShowSheet(
    titleName: String,
    opening: PostShowOpening,
    onSave: suspend (PostShowOpening, Double?, String) -> Unit,
    onRevert: suspend (PostShowOpening) -> Unit,
    onDismiss: () -> Unit,
    onReverted: () -> Unit = onDismiss,
    onRecommend: (() -> Unit)? = null,
) {
    val viewing = opening.viewing
    var notes by rememberSaveable(viewing.id) { mutableStateOf(viewing.notes.orEmpty()) }
    var rating by rememberSaveable(viewing.id) { mutableStateOf(viewing.rating ?: 0.0) }
    var attempted by rememberSaveable(viewing.id) { mutableStateOf<String?>(null) }
    var attemptedRating by rememberSaveable(viewing.id) { mutableStateOf(0.0) }
    var attemptedNotes by rememberSaveable(viewing.id) { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var error by rememberSaveable(viewing.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val editable = attempted == null && !pending
    val canRevert = opening.reversalContext != null && viewing.rating == null
    fun submit(action: String) {
        if (pending || (attempted != null && attempted != action)) return
        if (attempted == null) {
            attemptedRating = rating
            attemptedNotes = notes
            attempted = action
        }
        pending = true
        error = null
        scope.launch {
            try {
                if (action == "save") onSave(opening, attemptedRating.takeIf { it > 0 }, attemptedNotes) else onRevert(opening)
                if (!isActive) return@launch
                if (action == "save") onDismiss() else onReverted()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                error = if (action == "save") "Couldn't confirm this save. Retry the same change or review pending changes in Profile."
                    else "Couldn't confirm this reversal. Retry the same change or review pending changes in Profile."
            } finally { pending = false }
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !pending || it != SheetValue.Hidden })
    ModalBottomSheet(onDismissRequest = { if (!pending) onDismiss() }, sheetState = sheetState, sheetGesturesEnabled = !pending) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp, 0.dp, 20.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("$titleName just let out", style = MaterialTheme.typography.titleLarge)
            val subtitle = listOfNotNull(viewing.venue, viewing.companions.takeIf { it.isNotEmpty() }?.let { "with ${it.joinToString(" & ")}" }).joinToString(" · ")
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            Text("HOW WAS IT?", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (editable) DraggableStarRating(rating, { rating = it }, modifier = Modifier.testTag("post-show-rating"))
            else Text(if (rating == 0.0) "Unrated" else "$rating / 5")
            TextButton(onClick = { rating = 0.0 }, enabled = editable && rating > 0) { Text("Clear rating") }
            OutlinedTextField(notes, { notes = it }, label = { Text("Quick note") }, enabled = editable,
                modifier = Modifier.fillMaxWidth(), minLines = 2)
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            else if (attempted != null && !pending) Text("A previous attempt may already be queued. Retry checks the same change.")
            onRecommend?.let { recommend -> TextButton(onClick = recommend, enabled = !pending) { Text("Recommend to a friend") } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canRevert && (attempted == null || attempted == "revert")) TextButton(onClick = { submit("revert") }, enabled = !pending) {
                    Text(if (pending && attempted == "revert") "Reversing…" else if (attempted == "revert") "Retry reversal" else "Didn't make it")
                }
                TextButton(onClick = onDismiss, enabled = !pending) { Text("Close") }
                if (attempted == null || attempted == "save") Button(onClick = { submit("save") }, enabled = !pending) {
                    Text(if (pending) "Saving…" else if (attempted == "save") "Retry save" else "Save")
                }
            }
        }
    }
}
