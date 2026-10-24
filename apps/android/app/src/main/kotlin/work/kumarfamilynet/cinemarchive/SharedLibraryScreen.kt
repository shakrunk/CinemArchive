package work.kumarfamilynet.cinemarchive

import work.kumarfamilynet.cinemarchive.data.rethrowIfCancelled

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.data.*
import work.kumarfamilynet.cinemarchive.core.model.LibraryFilters
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
    LaunchedEffect(token, attempt) {
        snapshot = null
        error = null
        try {
            snapshot = buildSharedLibrarySnapshot(context, repository.fetchSharedLibrary(token))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) { failure.rethrowIfCancelled();
            // Never display backend bodies (which may echo credentials) in the viewer.
            error = if (failure is SharedLinkUnavailableException) failure.message
                else "The shared archive could not be loaded. Check your connection and retry."
        }
    }
    ArchiveViewer(token, snapshot, error, onRetry = { attempt++ }, onClose = onClose)
}

/** Shared rendering only: social content is supplied by the authenticated friend route. */
@Composable
internal fun ArchiveViewer(
    scopeKey: String,
    snapshot: SharedLibrarySnapshot?,
    error: String?,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    heading: String = "Shared archive",
    displayName: String = "This archive",
    emptyMessage: String = "No titles are shared by this link.",
    socialContent: (@Composable (SharedLibraryTitle) -> Unit)? = null,
) {
    var selectedId by remember(scopeKey) { mutableStateOf<String?>(null) }
    var ledger by remember(scopeKey) { mutableStateOf(false) }
    var expanded by remember(scopeKey) { mutableStateOf(emptySet<String>()) }
    var filters by remember(scopeKey) { mutableStateOf(LibraryFilters()) }
    var search by remember(scopeKey) { mutableStateOf(false) }
    val searchFocus = remember(scopeKey) { FocusRequester() }
    if (search) {
        val commands = listOf(
            work.kumarfamilynet.cinemarchive.core.model.AppCommand("library", "Go to the Library", keywords = "collection posters"),
            work.kumarfamilynet.cinemarchive.core.model.AppCommand("ledger", "Go to the Ledger", keywords = "stats dashboard"),
        ) + snapshot?.library?.titles.orEmpty().map { title ->
            work.kumarfamilynet.cinemarchive.core.model.titleCommand(title.id, title.title, title.year,
                title.graph().optString("director").takeIf { it.isNotBlank() && it != "null" }, title.mediaType == "tv", title.genres)
        }
        GlobalSearchDialog(scopeKey, commands, onDismiss = { search = false }, onSelect = { command ->
            search = false
            when (command.id) {
                "library" -> { selectedId = null; ledger = false }
                "ledger" -> { selectedId = null; ledger = true }
                else -> snapshot?.library?.titles?.firstOrNull { "title:${it.id}" == command.id }?.let { selectedId = it.id }
            }
        })
    }
    val back = { if (selectedId != null) selectedId = null else onClose() }
    BackHandler(onBack = back)
    // Opaque: this renders as an overlay, so without it the owner's own Library bleeds through.
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().onPreviewKeyEvent {
        if (it.opensGlobalSearch()) { search = true; true } else false
    }.focusRequester(searchFocus).focusable()) {
        LaunchedEffect(scopeKey) { searchFocus.requestFocus() }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) {
                if (selectedId == null) Icon(Icons.Filled.Close, contentDescription = "Close")
                else Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(heading, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp))
            IconButton(onClick = { search = true }, enabled = snapshot != null) { Icon(Icons.Outlined.Search, "Search archive") }
            ReadOnlyBadge()
        }
        val current = snapshot
        val selected = current?.library?.titles?.firstOrNull { it.id == selectedId }
        when {
            error != null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(error!!)
                Button(onClick = onRetry) { Text("Retry") }
            }
            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            selected != null -> SharedTitleDetail(selected, socialContent)
            else -> {
                PrimaryTabRow(selectedTabIndex = if (ledger) 1 else 0) {
                    Tab(selected = !ledger, onClick = { ledger = false }, text = { Text("Library (${current.library.titles.size})") })
                    Tab(selected = ledger, onClick = { ledger = true }, text = { Text("The Ledger") })
                }
                if (ledger) LedgerScreen(
                    uiState = LedgerUiState(current.stats, current.boards), layout = current.layout,
                    readOnly = true, viewedDisplayName = displayName, expandedWidgets = expanded,
                    onToggleExpanded = { id -> expanded = if (id in expanded) expanded - id else expanded + id },
                    onTitleClick = { selectedId = it },
                ) else key(scopeKey) {
                    ArchiveLibraryList(current.library.titles, filters, { filters = it }, emptyMessage,
                        onSelect = { selectedId = it })
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyBadge() {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
            Text("Read only", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** Uses the full graph, including independent episode logs and credits absent from owner Room.
 * Laid out like the owner's title detail (hero, cards, history), with no write controls.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SharedTitleDetail(title: SharedLibraryTitle, socialContent: (@Composable (SharedLibraryTitle) -> Unit)?) {
    val row = remember(title) { title.graph() }
    val uriHandler = LocalUriHandler.current
    LazyColumn(contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(96.dp, 144.dp)) {
                    AsyncImage(title.posterUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title.title, style = MaterialTheme.typography.headlineSmall)
                    val meta = listOfNotNull(title.year?.toString(), row.text("content_rating"), row.text("runtime")?.let { "$it min" })
                    if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SharedPill(statusLabel(title.status), MaterialTheme.colorScheme.primaryContainer)
                        title.rating?.let { SharedPill("★ ${formatRating(it)}", MaterialTheme.colorScheme.tertiaryContainer) }
                        if (row.optBoolean("in_home_collection")) SharedPill("In collection", MaterialTheme.colorScheme.surfaceContainerHigh)
                    }
                }
            }
        }
        if (title.genres.isNotEmpty()) item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                title.genres.forEach { SharedPill(it, MaterialTheme.colorScheme.surfaceContainerHigh) }
            }
        }
        row.text("synopsis")?.let { synopsis -> item {
            SharedCard("Synopsis") { Text(synopsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } }
        row.text("notes")?.let { notes -> item { SharedCard("Notes") { Text(notes, style = MaterialTheme.typography.bodyMedium) } } }
        val details = listOfNotNull(
            row.text("director")?.let { "Director" to it },
            row.text("network")?.let { "Network" to it },
            row.strings("studios").takeIf { it.isNotEmpty() }?.let { "Studios" to it.joinToString(", ") },
            row.text("collection_name")?.let { "Collection" to it },
            row.text("original_language")?.let { "Language" to it },
            row.text("release_date")?.let { "Released" to formatSharedDate(it) },
            listOfNotNull(row.text("imdb_rating")?.let { "IMDb $it/10" }, row.text("rt_score")?.let { "RT $it%" },
                row.text("metacritic_score")?.let { "Metacritic $it" }).takeIf { it.isNotEmpty() }?.let { "Ratings" to it.joinToString(" · ") },
            row.text("awards_count")?.let { "Awards" to it },
            listOfNotNull(row.text("bechdel_outcome"), row.text("bechdel_score")).takeIf { it.isNotEmpty() }?.let { "Bechdel test" to it.joinToString(" · ") },
        )
        val tags = row.strings("tags").filter { it.isNotBlank() }
        val copies = row.rows("physical_media").map {
            listOfNotNull(it.text("format"), it.text("edition"), it.text("notes")).joinToString(" · ")
        }.filter { it.isNotBlank() }
        val watchUrl = row.text("custom_watch_url")?.takeIf(::isPublicWebUrl)
        if (details.isNotEmpty() || tags.isNotEmpty() || copies.isNotEmpty() || watchUrl != null) item {
            SharedCard("Details") {
                details.forEach { (label, value) -> SharedDetailRow(label, value) }
                if (copies.isNotEmpty()) SharedDetailRow("Physical media", copies.joinToString("\n"))
                if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { SharedPill(it, MaterialTheme.colorScheme.secondaryContainer) }
                }
                watchUrl?.let { url -> OutlinedButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text("Where to watch") } }
            }
        }
        val cast = row.rows("title_cast")
        val crew = row.rows("title_crew")
        if (cast.isNotEmpty() || crew.isNotEmpty()) item {
            SharedCard("Cast & crew") { SharedCredits(cast); SharedCredits(crew) }
        }
        if (socialContent != null) item(key = "discussion") { socialContent(title) }
        if (row.rows("viewings").isNotEmpty()) item { Text("Viewing history", style = MaterialTheme.typography.titleMedium) }
        items(row.rows("viewings"), key = { "viewing:" + it.getString("id") }) { viewing ->
            SharedLogCard(listOfNotNull(viewing.text("viewed_at")?.let(::formatSharedDate) ?: "Date unknown",
                viewing.text("rating")?.let { "★ $it" }, viewing.text("venue")).joinToString(" · "),
                listOfNotNull(viewing.text("notes"), viewing.companions().takeIf { it.isNotBlank() }?.let { "with $it" }).joinToString("\n"))
        }
        val seasons = row.rows("seasons").associateBy { it.getInt("season_number") }
        val episodes = row.rows("episodes").groupBy { it.getInt("season_number") }
        (seasons.keys + episodes.keys).sorted().forEach { number ->
            item(key = "season:$number") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (number == 0) "Specials" else "Season $number", style = MaterialTheme.typography.titleMedium)
                    seasons[number]?.let { season ->
                        val seasonEpisodes = episodes[number].orEmpty()
                        val watched = if (seasonEpisodes.isEmpty()) season.optInt("episodes_watched")
                            else seasonEpisodes.count { it.rows("episode_watch_events").isNotEmpty() }
                        val total = season.optInt("episode_count")
                        Text("$watched of $total episodes watched", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (total > 0) LinearProgressIndicator(progress = { watched.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                        val seasonCast = season.rows("season_cast")
                        if (seasonCast.isNotEmpty()) SharedCredits(seasonCast)
                    }
                }
            }
            items(episodes[number].orEmpty().sortedBy { it.getInt("episode_number") },
                key = { "episode:" + it.getString("id") }) { episode ->
                SharedEpisodeCard(episode)
            }
        }
    }
}

/** One episode, collapsed to its name and synopsis; its watch/rating/review history opens on demand. */
@Composable private fun SharedEpisodeCard(episode: JSONObject) {
    var expanded by remember(episode.optString("id")) { mutableStateOf(false) }
    val watches = episode.rows("episode_watch_events")
    val ratings = episode.rows("episode_ratings")
    val reviews = episode.rows("episode_reviews")
    val crew = episode.rows("episode_crew")
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("E${episode.getInt("episode_number")} · ${episode.text("episode_name") ?: "Episode"}",
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (watches.isNotEmpty()) Icon(Icons.Filled.CheckCircle, contentDescription = "Watched",
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            val meta = listOfNotNull(episode.text("air_date")?.let(::formatSharedDate), episode.text("runtime")?.let { "$it min" })
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            episode.text("synopsis")?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            }
            if (watches.isNotEmpty() || ratings.isNotEmpty() || reviews.isNotEmpty() || crew.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (expanded) "Hide history" else "Show history")
                }
            }
            if (expanded) {
                watches.forEach {
                    SharedLogCard("Watched ${it.text("watched_at")?.let(::formatSharedDate) ?: "before CinemArchive"}",
                        listOfNotNull(colorModeLabel(it.text("color_mode")), it.text("notes")).joinToString(" · "))
                }
                ratings.forEach { SharedLogCard("★ ${it.text("rating")}", it.text("rated_at")?.let(::formatSharedDate).orEmpty()) }
                reviews.forEach {
                    SharedLogCard("Review · ${it.text("reviewed_at")?.let(::formatSharedDate).orEmpty()}",
                        listOfNotNull(colorModeLabel(it.text("color_mode")), it.text("review_text")).joinToString("\n"))
                }
                SharedCredits(crew)
            }
        }
    }
}

@Composable private fun SharedCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
@Composable private fun SharedDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.65f))
    }
}
@Composable private fun SharedPill(label: String, color: androidx.compose.ui.graphics.Color) {
    Surface(shape = MaterialTheme.shapes.small, color = color) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}
@Composable private fun SharedCredits(people: List<JSONObject>) {
    people.forEach {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(it.text("name") ?: "Unknown", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.5f))
            Text((it.text("character_name") ?: it.text("job")).orEmpty(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.5f))
        }
    }
}
@Composable private fun SharedLogCard(heading: String, detail: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(heading, style = MaterialTheme.typography.titleSmall)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
private fun statusLabel(status: String): String = when (status) {
    "watchlist" -> "Watchlist"
    "watching" -> "Watching"
    "watched" -> "Watched"
    "dropped" -> "Dropped"
    else -> status.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
}
private fun colorModeLabel(mode: String?): String? = when (mode) { "bw" -> "B&W"; "color" -> "Color"; else -> null }
private fun formatRating(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
/** Graph dates arrive as plain YYYY-MM-DD or as full instants; instants are shown in the local zone. */
private fun formatSharedDate(value: String): String {
    val format = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US)
    return runCatching { java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault()).format(format) }
        .recoverCatching { java.time.LocalDate.parse(value.take(10)).format(format) }
        .getOrDefault(value)
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
