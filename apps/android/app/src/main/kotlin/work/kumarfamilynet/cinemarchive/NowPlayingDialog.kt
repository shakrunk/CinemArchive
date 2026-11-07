package work.kumarfamilynet.cinemarchive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult
import work.kumarfamilynet.cinemarchive.core.model.MoviegoingRegion

/**
 * "What's playing in theaters" for a country (#202). TMDB lists titles for a region but has no
 * showtimes, so picking one only starts the schedule flow: the user still enters the time. The
 * region is a synced owner preference ([savedRegion]); until one is saved the device locale's
 * country is used.
 */
@Composable
internal fun NowPlayingDialog(
    savedRegion: String?,
    load: suspend (region: String) -> List<MediaSearchResult>,
    onSaveRegion: suspend (String) -> Unit,
    onPick: (MediaSearchResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val effective = MoviegoingRegion.effective(savedRegion)
    var draft by rememberSaveable(effective) { mutableStateOf(effective) }
    var region by rememberSaveable(effective) { mutableStateOf(effective) }
    var movies by remember { mutableStateOf<List<MediaSearchResult>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val draftRegion = MoviegoingRegion.normalize(draft)

    LaunchedEffect(region, reload) {
        movies = null
        error = null
        try {
            movies = load(region)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "Couldn't load what's playing. Check your connection and try again."
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(20.dp).widthIn(max = 560.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Now playing", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Text(
                    "Movies in theaters for a country. Showtimes aren't listed, so you'll enter the time next.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(2) },
                        label = { Text("Country code") },
                        singleLine = true,
                        isError = draft.isNotEmpty() && draftRegion == null,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = draftRegion != null && draftRegion != region,
                        onClick = {
                            val next = draftRegion ?: return@Button
                            region = next
                            scope.launch { runCatching { onSaveRegion(next) } }
                        },
                    ) { Text("Use") }
                }
                val list = movies
                when {
                    error != null -> {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { reload++ }) { Text("Try again") }
                    }
                    list == null -> Text("Loading…")
                    list.isEmpty() -> Text("Nothing is listed for $region right now.")
                    else -> LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(list, key = { it.tmdbId }) { movie ->
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Schedule ${movie.title}") { onPick(movie) },
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(movie.title)
                                    movie.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
