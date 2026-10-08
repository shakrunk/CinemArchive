package work.kumarfamilynet.cinemarchive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.data.*
import work.kumarfamilynet.cinemarchive.feature.ledger.LedgerScreen
import work.kumarfamilynet.cinemarchive.feature.ledger.LedgerUiState

/** Pasted links remain usable signed-out even when Android hasn't associated the web domain. */
@Composable
fun SharedLinkPromptButton(onOpen: (String) -> Unit) {
    var showing by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    TextButton(onClick = { showing = true }, modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
        Text("Open a shared archive")
    }
    if (showing) AlertDialog(
        onDismissRequest = { showing = false; link = "" },
        title = { Text("Open shared archive") },
        text = { OutlinedTextField(link, { link = it }, label = { Text("Share link") }, singleLine = true) },
        confirmButton = { TextButton(enabled = SharingRules.parseToken(link) != null, onClick = {
            SharingRules.parseToken(link)?.let(onOpen)
            showing = false
            link = ""
        }) { Text("Open") } },
        dismissButton = { TextButton(onClick = { showing = false; link = "" }) { Text("Cancel") } },
    )
}

/** Anonymous, memory-only viewer. No owner repository or mutation callback enters this UI. */
@Composable
fun SharedLibraryRoute(token: String, repository: SharingRepository, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var snapshot by remember(token) { mutableStateOf<SharedLibrarySnapshot?>(null) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var attempt by remember(token) { mutableIntStateOf(0) }
    var selectedId by remember(token) { mutableStateOf<String?>(null) }
    var ledger by remember(token) { mutableStateOf(false) }
    var expanded by remember(token) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(token, attempt) {
        snapshot = null
        error = null
        try {
            snapshot = buildSharedLibrarySnapshot(context, repository.fetchSharedLibrary(token))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Never display backend bodies (which may echo credentials) in the viewer.
            error = if (failure is SharedLinkUnavailableException) failure.message
                else "The shared archive could not be loaded. Check your connection and retry."
        }
    }
    val back = { if (selectedId != null) selectedId = null else onClose() }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text(if (selectedId == null) "Close" else "Back") }
            Text("Shared archive", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text("Read only", style = MaterialTheme.typography.labelMedium)
        }
        val current = snapshot
        val selected = current?.library?.titles?.firstOrNull { it.id == selectedId }
        when {
            error != null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(error!!)
                Button(onClick = { attempt++ }) { Text("Retry") }
            }
            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            selected != null -> SharedTitleDetail(selected)
            else -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    FilterChip(selected = !ledger, onClick = { ledger = false }, label = { Text("Library (${current.library.titles.size})") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = ledger, onClick = { ledger = true }, label = { Text("The Ledger") })
                }
                if (ledger) LedgerScreen(
                    uiState = LedgerUiState(current.stats, current.boards), layout = current.layout,
                    readOnly = true, viewedDisplayName = "This archive", expandedWidgets = expanded,
                    onToggleExpanded = { id -> expanded = if (id in expanded) expanded - id else expanded + id },
                    onTitleClick = { selectedId = it },
                ) else SharedTitleList(current.library.titles, onSelect = { selectedId = it })
            }
        }
    }
}

@Composable
private fun SharedTitleList(titles: List<SharedLibraryTitle>, onSelect: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val shown = remember(titles, query, status) {
        titles.filter { (status == null || it.status == status) &&
            (it.title.contains(query, true) || it.genres.any { genre -> genre.contains(query, true) }) }
            .sortedBy { it.title.lowercase() }
    }
    Column {
        OutlinedTextField(query, { query = it }, label = { Text("Search titles or genres") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf<String?>(null) + titles.map { it.status }.distinct().sorted()).forEach { choice ->
                FilterChip(selected = choice == status, onClick = { status = choice },
                    label = { Text(choice?.replace('_', ' ') ?: "All") })
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (shown.isEmpty()) item { Text(if (titles.isEmpty()) "No titles are shared by this link." else "No matching titles.") }
            items(shown, key = { it.id }) { title ->
                Surface(onClick = { onSelect(title.id) }, shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(title.posterUrl, contentDescription = null, modifier = Modifier.size(48.dp, 72.dp))
                        Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title.title, style = MaterialTheme.typography.titleMedium)
                            Text(listOfNotNull(title.year?.toString(), title.mediaType.uppercase(),
                                title.rating?.let { "$it / 5" }).joinToString(" · "))
                            Text(title.status.replace('_', ' '), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

/** Uses the full graph, including independent episode logs and credits absent from owner Room.
 * Content scrolls through the regular lazy surface, with no write controls.
 */
@Composable
private fun SharedTitleDetail(title: SharedLibraryTitle) {
    val row = remember(title) { title.graph() }
    val uriHandler = LocalUriHandler.current
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AsyncImage(title.posterUrl, null, modifier = Modifier.size(96.dp, 144.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title.title, style = MaterialTheme.typography.headlineMedium)
                    Text(listOfNotNull(title.year?.toString(), row.text("content_rating"),
                        row.text("runtime")?.let { "$it min" }).joinToString(" · "))
                    Text(title.status.replace('_', ' '))
                    title.rating?.let { Text("Rating: $it / 5") }
                    Text(title.genres.joinToString(" · "))
                }
            }
        }
        item {
            SharedTextSection("Synopsis", row.text("synopsis"))
            SharedTextSection("Notes", row.text("notes"))
            SharedTextSection("Director", row.text("director"))
            SharedTextSection("Network", row.text("network"))
            SharedTextSection("Collection", row.text("collection_name"))
            SharedTextSection("Tags", row.strings("tags").joinToString(", "))
            SharedTextSection("Studios", row.strings("studios").joinToString(", "))
            SharedTextSection("Language", row.text("original_language"))
            SharedTextSection("Release date", row.text("release_date"))
            SharedTextSection("Ratings", listOfNotNull(row.text("imdb_rating")?.let { "IMDb $it / 10" },
                row.text("rt_score")?.let { "Rotten Tomatoes $it%" },
                row.text("metacritic_score")?.let { "Metacritic $it / 100" }).joinToString(" · "))
            SharedTextSection("Awards", row.text("awards_count"))
            SharedTextSection("Bechdel test", listOfNotNull(row.text("bechdel_outcome"), row.text("bechdel_score")).joinToString(" · "))
            if (row.optBoolean("in_home_collection")) Text("In home collection")
            SharedTextSection("Physical media", row.rows("physical_media").joinToString("\n") {
                listOfNotNull(it.text("format"), it.text("edition"), it.text("notes")).joinToString(" · ")
            })
            row.text("custom_watch_url")?.takeIf(::isPublicWebUrl)?.let { url ->
                TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text("Where to watch") }
            }
        }
        item { SharedCredits("Cast", row.rows("title_cast")); SharedCredits("Crew", row.rows("title_crew")) }
        if (row.rows("viewings").isNotEmpty()) item { Text("Viewing history", style = MaterialTheme.typography.titleLarge) }
        items(row.rows("viewings"), key = { "viewing:" + it.getString("id") }) { viewing ->
            SharedLogCard(listOfNotNull(viewing.text("viewed_at") ?: "Date unknown",
                viewing.text("rating")?.let { "$it / 5" }, viewing.text("venue")).joinToString(" · "),
                listOfNotNull(viewing.text("notes"), viewing.companions().takeIf { it.isNotBlank() }).joinToString("\n"))
        }
        val seasons = row.rows("seasons").associateBy { it.getInt("season_number") }
        val episodes = row.rows("episodes").groupBy { it.getInt("season_number") }
        (seasons.keys + episodes.keys).sorted().forEach { number ->
            item(key = "season:$number") {
                Text("Season $number", style = MaterialTheme.typography.titleLarge)
                seasons[number]?.let { season ->
                    val seasonEpisodes = episodes[number].orEmpty()
                    val watched = if (seasonEpisodes.isEmpty()) season.optInt("episodes_watched")
                        else seasonEpisodes.count { it.rows("episode_watch_events").isNotEmpty() }
                    Text("$watched of ${season.optInt("episode_count")} episodes watched")
                    SharedCredits("Season cast", season.rows("season_cast"))
                }
            }
            items(episodes[number].orEmpty().sortedBy { it.getInt("episode_number") },
                key = { "episode:" + it.getString("id") }) { episode ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${episode.getInt("episode_number")}. ${episode.text("episode_name") ?: "Episode"}", style = MaterialTheme.typography.titleMedium)
                    SharedTextSection("Aired", episode.text("air_date"))
                    SharedTextSection("Runtime", episode.text("runtime")?.let { "$it min" })
                    SharedTextSection("Synopsis", episode.text("synopsis"))
                    SharedCredits("Episode crew", episode.rows("episode_crew"))
                    episode.rows("episode_watch_events").forEach {
                        SharedLogCard("Watched: ${it.text("watched_at") ?: "Date unknown"}",
                            listOfNotNull(it.text("notes"), it.text("color_mode")).joinToString(" · "))
                    }
                    episode.rows("episode_ratings").forEach {
                        SharedLogCard("Rating: ${it.text("rating")} / 5", it.text("rated_at").orEmpty())
                    }
                    episode.rows("episode_reviews").forEach {
                        SharedLogCard("Review: ${it.text("reviewed_at").orEmpty()}",
                            listOfNotNull(it.text("review_text"), it.text("color_mode")).joinToString("\n"))
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable private fun SharedTextSection(label: String, value: String?) {
    if (!value.isNullOrBlank()) Column(Modifier.padding(bottom = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value)
    }
}
@Composable private fun SharedCredits(label: String, people: List<JSONObject>) {
    SharedTextSection(label, people.joinToString("\n") {
        listOfNotNull(it.text("name"), it.text("character_name") ?: it.text("job")).joinToString(" — ")
    })
}
@Composable private fun SharedLogCard(heading: String, detail: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(heading, style = MaterialTheme.typography.titleSmall)
            if (detail.isNotBlank()) Text(detail)
        }
    }
}
private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
private fun JSONObject.rows(key: String): List<JSONObject> = optJSONArray(key)?.let { a ->
    (0 until a.length()).map(a::getJSONObject)
}.orEmpty()
private fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { a ->
    (0 until a.length()).map(a::getString)
}.orEmpty()
private fun JSONObject.companions(): String = optJSONArray("companions")?.let { a ->
    (0 until a.length()).mapNotNull { index -> when (val value = a.get(index)) {
        is JSONObject -> value.text("name")
        is String -> value
        else -> null
    } }.joinToString(", ")
}.orEmpty()
private fun isPublicWebUrl(value: String): Boolean = runCatching {
    val uri = java.net.URI(value)
    uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
}.getOrDefault(false)
