package work.kumarfamilynet.cinemarchive.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import work.kumarfamilynet.cinemarchive.core.designsystem.ChoiceOption
import work.kumarfamilynet.cinemarchive.core.designsystem.DraggableStarRating
import work.kumarfamilynet.cinemarchive.core.designsystem.PosterSurface
import work.kumarfamilynet.cinemarchive.core.designsystem.SegmentedGroup
import work.kumarfamilynet.cinemarchive.core.designsystem.tintForKey
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaDetails
import work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.data.DiscoverRepository
import work.kumarfamilynet.cinemarchive.data.LibraryRepository

/**
 * The real "Add a title" flow, reached from the FAB or from an Add tap on a Discover result:
 * search TMDB, pick a result, log how you're tracking it, and write it to the library.
 *
 * Two steps rather than one, matching the web app's `AddTitleWorkflow`: the search step can't
 * show runtime, genres, seasons or critic scores (TMDB's search endpoint doesn't return them),
 * so committing to a title has to come before the form that describes it.
 *
 * [onAdded] receives the new local title id — the caller opens its detail screen, so the add
 * ends on the thing that was just created rather than back where it started.
 */
@Composable
fun AddTitleOverlayRoute(
    discoverRepository: DiscoverRepository,
    libraryRepository: LibraryRepository,
    onClose: () -> Unit,
    onAdded: (String) -> Unit,
    onOpenTitle: (String) -> Unit,
    preselected: MediaSearchResult? = null,
) {
    val viewModel: AddTitleViewModel = viewModel(
        factory = AddTitleViewModelFactory(discoverRepository, libraryRepository, preselected),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 12.dp),
        ) {
            IconButton(
                onClick = { if (state.step == AddTitleStep.LOG && preselected == null) viewModel.backToSearch() else onClose() },
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = if (state.step == AddTitleStep.LOG && preselected == null) "Back to search" else "Close",
                )
            }
            Text(
                if (state.step == AddTitleStep.SEARCH) "Add a title" else "Log it",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        when (state.step) {
            AddTitleStep.SEARCH -> SearchStep(
                state = state,
                onQueryChange = viewModel::onQueryChange,
                onRetry = viewModel::retrySearch,
                onSelect = viewModel::select,
            )
            AddTitleStep.LOG -> LogStep(
                state = state,
                onStatusChange = viewModel::onStatusChange,
                onRatingChange = viewModel::onRatingChange,
                onNotesChange = viewModel::onNotesChange,
                onRetry = viewModel::retryDetails,
                onSave = { viewModel.save(onAdded) },
                onOpenExisting = onOpenTitle,
            )
        }
    }
}

@Composable
private fun SearchStep(
    state: AddTitleUiState,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onSelect: (MediaSearchResult) -> Unit,
) {
    SearchField(query = state.query, onQueryChange = onQueryChange)

    when {
        state.isSearching && state.results.isEmpty() -> CenteredNotice { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
        state.searchError != null -> CenteredNotice {
            Text("Couldn't reach TMDB", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                state.searchError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Button(onClick = onRetry) { Text("Retry") }
        }
        state.isEmptyResult -> CenteredNotice {
            Text("Nothing found", style = MaterialTheme.typography.titleMedium)
            Text(
                "No movies or series match \"${state.query}\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        state.query.isBlank() -> CenteredNotice {
            Text(
                "Search TMDB for any movie or series to add it to your library.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        else -> LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp)) {
            items(state.results, key = { "${it.type}-${it.tmdbId}" }) { result ->
                SearchResultRow(result = result, onClick = { onSelect(result) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = 14.dp)
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp),
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.padding(start = 10.dp).fillMaxWidth(),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        "Search TMDB…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            },
        )
    }
}

@Composable
private fun SearchResultRow(result: MediaSearchResult, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.background) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        ) {
            PosterSurface(
                tint = tintForKey(result.tmdbId.toString()),
                imageUrl = result.posterUrl,
                modifier = Modifier.size(width = 44.dp, height = 60.dp),
                aspectRatio = 44f / 60f,
                cornerRadius = 10.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(result.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    result.metaLine(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LogStep(
    state: AddTitleUiState,
    onStatusChange: (LibraryStatus) -> Unit,
    onRatingChange: (Double) -> Unit,
    onNotesChange: (String) -> Unit,
    onRetry: () -> Unit,
    onSave: () -> Unit,
    onOpenExisting: (String) -> Unit,
) {
    val selected = state.selected ?: return
    when {
        state.isLoadingDetails -> CenteredNotice {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(
                "Fetching ${selected.title}…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        state.details == null -> CenteredNotice {
            Text("Couldn't load this title", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                state.detailsError.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Button(onClick = onRetry) { Text("Retry") }
        }
        else -> LogForm(
            details = state.details,
            state = state,
            onStatusChange = onStatusChange,
            onRatingChange = onRatingChange,
            onNotesChange = onNotesChange,
            onSave = onSave,
            onOpenExisting = onOpenExisting,
        )
    }
}

@Composable
private fun LogForm(
    details: MediaDetails,
    state: AddTitleUiState,
    onStatusChange: (LibraryStatus) -> Unit,
    onRatingChange: (Double) -> Unit,
    onNotesChange: (String) -> Unit,
    onSave: () -> Unit,
    onOpenExisting: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
            PosterSurface(
                tint = tintForKey(details.tmdbId.toString()),
                imageUrl = details.posterUrl,
                modifier = Modifier.size(width = 78.dp, height = 108.dp),
                aspectRatio = 78f / 108f,
                cornerRadius = 12.dp,
            )
            Column(modifier = Modifier.padding(start = 14.dp).align(Alignment.CenterVertically)) {
                Text(details.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    details.metaLine(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (details.genres.isNotEmpty()) {
                    Text(
                        details.genres.take(3).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        details.synopsis?.let { synopsis ->
            Text(
                synopsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 20.dp),
            )
        }

        // The duplicate guard LibraryRepository.addTitle enforces, surfaced before the user
        // fills in a form that would be discarded — the existing row wins either way.
        state.alreadyOwnedTitleId?.let { existingId ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Already in your library", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Adding it again won't create a second copy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    OutlinedButton(onClick = { onOpenExisting(existingId) }) { Text("Open it") }
                }
            }
        }

        SectionLabel("Status")
        SegmentedGroup(
            options = listOf(
                ChoiceOption(LibraryStatus.WATCHLIST, "Watchlist"),
                ChoiceOption(LibraryStatus.WATCHING, "Watching"),
                ChoiceOption(LibraryStatus.WATCHED, "Watched"),
                ChoiceOption(LibraryStatus.DROPPED, "Dropped"),
            ),
            selected = state.form.status,
            onSelect = onStatusChange,
            modifier = Modifier.padding(bottom = 20.dp),
        )

        SectionLabel("Your rating")
        DraggableStarRating(
            rating = state.form.rating,
            onRatingChange = onRatingChange,
            modifier = Modifier.padding(bottom = 20.dp),
        )

        SectionLabel("Notes")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(14.dp)
                .padding(bottom = 4.dp),
        ) {
            BasicTextField(
                value = state.form.notes,
                onValueChange = onNotesChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (state.form.notes.isEmpty()) {
                        Text(
                            "Why you're tracking it, who recommended it…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                },
            )
        }

        state.saveError?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        Button(
            onClick = onSave,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 32.dp),
        ) {
            Text(if (state.isSaving) "Adding…" else "Add to Library")
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** Shared frame for the step-level loading/error/empty states, so all of them sit in the same
 *  place on screen instead of each inventing its own padding. */
@Composable
private fun CenteredNotice(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

private fun MediaSearchResult.metaLine(): String =
    listOfNotNull(year?.toString(), if (type == MediaType.TV) "TV Series" else "Movie").joinToString(" · ")

private fun MediaDetails.metaLine(): String = listOfNotNull(
    year?.toString(),
    if (type == MediaType.TV) "TV Series" else "Movie",
    contentRating,
    when {
        type == MediaType.TV && seasons.isNotEmpty() ->
            "${seasons.size} season${if (seasons.size == 1) "" else "s"}"
        runtime != null -> "${runtime}m"
        else -> null
    },
).joinToString(" · ")
