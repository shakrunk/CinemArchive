package work.kumarfamilynet.cinemarchive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import work.kumarfamilynet.cinemarchive.core.model.AppCommand
import work.kumarfamilynet.cinemarchive.core.model.rankCommands

/** Ctrl/Cmd+K is usable from any focused child, without consuming ordinary text input. */
internal fun KeyEvent.opensGlobalSearch(): Boolean =
    type == KeyEventType.KeyDown && key == Key.K && (isCtrlPressed || isMetaPressed)

@Composable
internal fun GlobalSearchDialog(
    scopeKey: String,
    commands: List<AppCommand>,
    onDismiss: () -> Unit,
    onSelect: (AppCommand) -> Unit,
    title: String = "Search and actions",
    placeholder: String = "Search titles or actions",
) {
    key(scopeKey) {
        var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
        var activeId by rememberSaveable { mutableStateOf<String?>(null) }
        val results = remember(commands, query.text) { rankCommands(commands, query.text) }
        val current by rememberUpdatedState(commands)
        val selected = results.firstOrNull { it.id == activeId } ?: results.firstOrNull()
        LaunchedEffect(selected?.id) { activeId = selected?.id }
        val focus = remember { FocusRequester() }
        val listState = rememberLazyListState()
        LaunchedEffect(selected?.id, results) {
            val index = results.indexOfFirst { it.id == selected?.id }
            if (index >= 0) listState.animateScrollToItem(index)
        }
        fun choose(command: AppCommand?) {
            current.firstOrNull { it.id == command?.id }?.let(onSelect)
        }
        fun step(delta: Int) {
            if (results.isNotEmpty()) {
                val index = results.indexOfFirst { it.id == selected?.id }.coerceAtLeast(0)
                activeId = results[(index + delta + results.size) % results.size].id
            }
        }
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier
                .padding(20.dp).widthIn(max = 560.dp).fillMaxWidth().imePadding()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false
                    else when (event.key) {
                        Key.Escape -> { onDismiss(); true }
                        Key.DirectionDown -> if (query.composition == null) { step(1); true } else false
                        Key.DirectionUp -> if (query.composition == null) { step(-1); true } else false
                        Key.Enter, Key.NumPadEnter -> if (query.composition == null) { choose(selected); true } else false
                        else -> false
                    }
                }) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                    OutlinedTextField(query, { query = it; activeId = null },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        label = { Text(placeholder) }, singleLine = true,
                        trailingIcon = { if (query.text.isNotEmpty()) TextButton(onClick = {
                            query = TextFieldValue("", TextRange.Zero); activeId = null
                        }) { Text("Clear") } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { if (query.composition == null) choose(selected) }))
                    if (results.isEmpty()) Text("No results", Modifier.padding(vertical = 20.dp))
                    LazyColumn(Modifier.heightIn(max = 420.dp), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(results, key = { it.id }) { command ->
                            Surface(color = if (command.id == selected?.id) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth().semantics { this.selected = command.id == selected?.id }
                                    .clickable { choose(command) }) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(command.label)
                                    if (command.hint.isNotBlank()) Text(command.hint, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
    }
}


@Composable
internal fun OwnerGlobalSearch(
    scopeKey: String,
    titles: List<work.kumarfamilynet.cinemarchive.core.model.LibraryTitle>,
    onDismiss: () -> Unit,
    onCommand: (String) -> Unit,
    onTitle: (String) -> Unit,
    onSchedule: (String) -> Unit,
) {
    key(scopeKey) {
        var choosingMovie by rememberSaveable { mutableStateOf(false) }
        val current by rememberUpdatedState(titles)
        val commands = if (choosingMovie) titles.filter { it.type == work.kumarfamilynet.cinemarchive.core.model.MediaType.MOVIE }
            .map { work.kumarfamilynet.cinemarchive.core.model.titleCommand(it.id, it.name, it.year, it.director, false, it.genres) }
        else work.kumarfamilynet.cinemarchive.core.model.ownerCommands(titles)
        GlobalSearchDialog(scopeKey + if (choosingMovie) ":movies" else ":commands", commands,
            onDismiss = { if (choosingMovie) choosingMovie = false else onDismiss() },
            title = if (choosingMovie) "Which movie are your tickets for?" else "Search and actions",
            placeholder = if (choosingMovie) "Search your movies" else "Search titles or actions",
            onSelect = { command ->
                when {
                    command.id == "tickets" -> choosingMovie = true
                    command.id.startsWith("title:") -> {
                        val id = command.id.removePrefix("title:")
                        current.firstOrNull { it.id == id && (!choosingMovie || it.type == work.kumarfamilynet.cinemarchive.core.model.MediaType.MOVIE) }?.let {
                            if (choosingMovie) onSchedule(id) else onTitle(id)
                        }
                    }
                    else -> onCommand(command.id)
                }
            })
    }
}

