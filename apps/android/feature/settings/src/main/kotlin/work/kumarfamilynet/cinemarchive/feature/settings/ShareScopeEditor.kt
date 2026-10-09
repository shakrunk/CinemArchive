package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.data.ShareScope
import work.kumarfamilynet.cinemarchive.data.ShareScopeTarget
import work.kumarfamilynet.cinemarchive.data.SharingRepository

interface ShareScopeEditorSource {
    fun isActive(): Boolean
    suspend fun load(target: ShareScopeTarget): ShareScope?
    suspend fun save(target: ShareScopeTarget, value: ShareScope?)
}

/** The repository is already bound to the runtime's fenced session, never a global account. */
class RepositoryShareScopeSource(
    private val repository: SharingRepository,
    private val active: () -> Boolean = { true },
) : ShareScopeEditorSource {
    override fun isActive() = active()
    override suspend fun load(target: ShareScopeTarget): ShareScope? {
        check(isActive())
        val value = repository.getShareScope(target)
        currentCoroutineContext().ensureActive()
        check(isActive())
        return value
    }
    override suspend fun save(target: ShareScopeTarget, value: ShareScope?) {
        check(isActive())
        repository.setShareScope(target, value)
        currentCoroutineContext().ensureActive()
        check(isActive())
    }
}

data class ShareScopeEditorState(
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val custom: Boolean = false,
    val genres: Set<String> = emptySet(),
    val statuses: Set<String> = emptySet(),
    val error: String? = null,
    val saved: Boolean = false,
) {
    // Same as web: no selections on a dimension means unrestricted, never deny-all [].
    fun value(): ShareScope? = if (!custom) null else ShareScope(
        genres.toList().takeIf { it.isNotEmpty() }, statuses.toList().takeIf { it.isNotEmpty() },
    )
}

class ShareScopeEditorController(
    private val source: ShareScopeEditorSource,
    private val target: ShareScopeTarget,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(ShareScopeEditorState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var disposed = false
    private fun active() = !disposed && source.isActive()

    init { reload() }

    fun reload() {
        if (!active() || state.value.saving) return
        job?.cancel()
        mutable.value = ShareScopeEditorState()
        job = scope.launch {
            try {
                val value = source.load(target)
                currentCoroutineContext().ensureActive()
                if (active()) mutable.value = ShareScopeEditorState(loading = false, loaded = true,
                    custom = value != null, genres = value?.allowedGenres.orEmpty().toSet(),
                    statuses = value?.allowedStatuses.orEmpty().toSet())
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (active()) mutable.value = state.value.copy(loading = false,
                    error = "Access could not be loaded. Check your connection and retry.")
            }
        }
    }

    private fun edit(change: (ShareScopeEditorState) -> ShareScopeEditorState) {
        if (active() && state.value.loaded && !state.value.saving && !state.value.saved)
            mutable.value = change(state.value).copy(error = null)
    }
    fun setCustom(custom: Boolean) = edit { it.copy(custom = custom) }
    fun toggleGenre(genre: String) = edit { it.copy(genres = if (genre in it.genres) it.genres - genre else it.genres + genre) }
    fun toggleStatus(status: String) = edit { it.copy(statuses = if (status in it.statuses) it.statuses - status else it.statuses + status) }

    fun save() {
        if (!active() || !state.value.loaded || state.value.saving || state.value.saved) return
        val value = state.value.value()
        mutable.value = state.value.copy(saving = true, error = null)
        job = scope.launch {
            try {
                source.save(target, value)
                currentCoroutineContext().ensureActive()
                if (active()) mutable.value = state.value.copy(saving = false, saved = true)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (active()) mutable.value = state.value.copy(saving = false,
                    error = "Access could not be saved. Your selections are kept; retry when connected.")
            }
        }
    }

    fun dispose() { disposed = true; job?.cancel() }
}

@Composable
fun ShareScopeEditorDialog(
    source: ShareScopeEditorSource,
    target: ShareScopeTarget,
    label: String,
    availableGenres: List<String>,
    onClose: () -> Unit,
) {
    key(source, target) {
        val scope = rememberCoroutineScope()
        val controller = remember { ShareScopeEditorController(source, target, scope) }
        val state by controller.state.collectAsState()
        val close by rememberUpdatedState(onClose)
        DisposableEffect(controller) { onDispose { controller.dispose() } }
        LaunchedEffect(state.saved) { if (state.saved && source.isActive()) close() }
        if (source.isActive()) AlertDialog(
            onDismissRequest = { if (!state.saving) { controller.dispose(); close() } },
            title = { Text("Edit access for $label") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.loading) Text("Loading access…")
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (!state.loading && !state.loaded) TextButton(onClick = controller::reload) { Text("Retry load") }
                    if (state.loaded) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!state.custom, { controller.setCustom(false) }, enabled = !state.saving, label = { Text("Full library") })
                            FilterChip(state.custom, { controller.setCustom(true) }, enabled = !state.saving, label = { Text("Custom") })
                        }
                        if (state.custom) {
                            Text("Leave a section empty to allow everything in it. Combining selections narrows to titles matching both.")
                            val genres = (availableGenres + state.genres).distinct().sorted()
                            if (genres.isNotEmpty()) {
                                Text("Genres", style = MaterialTheme.typography.labelLarge)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    genres.forEach { genre -> FilterChip(genre in state.genres, { controller.toggleGenre(genre) },
                                        enabled = !state.saving, label = { Text(genre) }) }
                                }
                            }
                            Text("Watch status", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("watched", "watchlist", "watching", "dropped").forEach { status ->
                                    FilterChip(status in state.statuses, { controller.toggleStatus(status) }, enabled = !state.saving,
                                        label = { Text(status.replaceFirstChar { it.uppercase() }) })
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = controller::save, enabled = state.loaded && !state.saving) {
                Text(if (state.saving) "Saving…" else "Save")
            } },
            dismissButton = { TextButton(enabled = !state.saving, onClick = { controller.dispose(); close() }) { Text("Cancel") } },
        )
    }
}
