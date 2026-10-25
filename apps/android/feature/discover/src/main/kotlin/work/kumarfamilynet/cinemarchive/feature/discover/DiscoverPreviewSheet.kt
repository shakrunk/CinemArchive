package work.kumarfamilynet.cinemarchive.feature.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.CatalogExtrasSection
import work.kumarfamilynet.cinemarchive.core.designsystem.PosterSurface
import work.kumarfamilynet.cinemarchive.core.designsystem.tintForKey
import work.kumarfamilynet.cinemarchive.core.model.CatalogExtrasKey
import work.kumarfamilynet.cinemarchive.core.model.CatalogLoad
import work.kumarfamilynet.cinemarchive.core.model.MediaDetails
import work.kumarfamilynet.cinemarchive.core.model.MediaEpisode
import work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.catalogWatchRegion
import work.kumarfamilynet.cinemarchive.core.model.isSpecials
import work.kumarfamilynet.cinemarchive.core.designsystem.LocalSpoilerProtection
import work.kumarfamilynet.cinemarchive.core.model.isEpisodeSpoilerHidden
import work.kumarfamilynet.cinemarchive.data.CatalogExtrasController
import work.kumarfamilynet.cinemarchive.data.CatalogExtrasSource
import work.kumarfamilynet.cinemarchive.data.DiscoverRepository
import work.kumarfamilynet.cinemarchive.data.rethrowIfCancelled

/**
 * Loads everything the Discover preview shows beyond the search hit: full details (cast,
 * critic scores, seasons with episode synopses) plus trailers and watch providers, the same
 * extras the Library title detail shows. Nothing here is persisted.
 */
class DiscoverPreviewViewModel(
    private val repository: DiscoverRepository,
    private val result: MediaSearchResult,
    extrasSource: CatalogExtrasSource?,
    private val region: String,
) : ViewModel() {
    private val mutableDetails = MutableStateFlow<CatalogLoad<MediaDetails>>(CatalogLoad.Loading)
    val details: StateFlow<CatalogLoad<MediaDetails>> = mutableDetails.asStateFlow()
    private val mutableEpisodes = MutableStateFlow<Map<Int, List<MediaEpisode>>>(emptyMap())
    val fetchedEpisodes: StateFlow<Map<Int, List<MediaEpisode>>> = mutableEpisodes.asStateFlow()
    val extras = extrasSource?.let { CatalogExtrasController(it, viewModelScope) }

    init {
        extras?.select(CatalogExtrasKey(result.tmdbId, result.type, region))
        loadDetails()
    }

    fun loadDetails() {
        mutableDetails.value = CatalogLoad.Loading
        viewModelScope.launch {
            mutableDetails.value = try {
                CatalogLoad.Ready(repository.fetchDetails(result))
            } catch (cause: Exception) {
                cause.rethrowIfCancelled()
                CatalogLoad.Failed
            }
        }
    }

    /** Fallback for a season whose episode list the details call could not fill. */
    fun loadSeason(seasonNumber: Int) {
        if (seasonNumber in mutableEpisodes.value) return
        mutableEpisodes.update { it + (seasonNumber to emptyList()) }
        viewModelScope.launch {
            val episodes = repository.fetchSeasonEpisodes(result.tmdbId, seasonNumber)
            mutableEpisodes.update { it + (seasonNumber to episodes) }
        }
    }
}

private class DiscoverPreviewViewModelFactory(
    private val repository: DiscoverRepository,
    private val result: MediaSearchResult,
    private val extrasSource: CatalogExtrasSource?,
    private val region: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        DiscoverPreviewViewModel(repository, result, extrasSource, region) as T
}

/**
 * Preview of a title that is not in the library yet. Mirrors the information the Library title
 * detail shows — trailers, where to watch, cast, critic scores and, for series, the season and
 * episode guide — so deciding what to add does not require adding it first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoverPreviewSheet(
    repository: DiscoverRepository,
    extrasSource: CatalogExtrasSource?,
    result: MediaSearchResult,
    isAdded: Boolean,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val region = catalogWatchRegion(LocalConfiguration.current.locales[0].toLanguageTag())
    val viewModel: DiscoverPreviewViewModel = viewModel(
        key = "discover-preview-${result.type}-${result.tmdbId}",
        factory = DiscoverPreviewViewModelFactory(repository, result, extrasSource, region),
    )
    val details by viewModel.details.collectAsStateWithLifecycle()
    val fetchedEpisodes by viewModel.fetchedEpisodes.collectAsStateWithLifecycle()
    val extrasState = viewModel.extras?.state?.collectAsStateWithLifecycle()?.value

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val loaded = (details as? CatalogLoad.Ready)?.value
            Row(modifier = Modifier.fillMaxWidth()) {
                PosterSurface(
                    tint = tintForKey(result.tmdbId.toString()),
                    imageUrl = loaded?.posterUrl ?: result.posterUrl,
                    modifier = Modifier.size(width = 72.dp, height = 100.dp),
                    aspectRatio = 72f / 100f,
                    cornerRadius = 12.dp,
                )
                Column(modifier = Modifier.padding(start = 14.dp).align(Alignment.CenterVertically)) {
                    Text(result.title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        previewMetaLine(result, loaded),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    (loaded?.director?.takeIf { it.isNotBlank() } ?: loaded?.network?.takeIf { it.isNotBlank() })?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (isAdded) {
                TextButton(onClick = onDismiss, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Added") }
            } else {
                Button(onClick = { onAdd(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Add to library") }
            }

            if (loaded != null && loaded.genres.isNotEmpty()) {
                Text(loaded.genres.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            PreviewSection("Synopsis") {
                Text(
                    loaded?.synopsis ?: result.synopsis ?: "No synopsis available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (details) {
                CatalogLoad.Loading -> Text("Loading details…", style = MaterialTheme.typography.bodyMedium)
                CatalogLoad.Failed -> {
                    Text("Details couldn't load.")
                    TextButton(onClick = viewModel::loadDetails) { Text("Retry details") }
                }
                is CatalogLoad.Ready -> Unit
            }

            // Trailers and watch providers come first, as on the web preview.
            if (extrasState?.key != null) {
                CatalogExtrasSection(
                    state = extrasState,
                    onRetryVideos = { viewModel.extras?.retryVideos() },
                    onRetryProviders = { viewModel.extras?.retryProviders() },
                )
            }

            if (loaded != null) {
                PreviewScores(loaded)
                if (loaded.cast.isNotEmpty()) {
                    PreviewSection("Cast") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            loaded.cast.sortedBy { it.order }.take(12).forEach { credit ->
                                Text(
                                    credit.name + (credit.characterName?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (loaded.type == MediaType.TV && loaded.seasons.isNotEmpty()) {
                    PreviewEpisodes(loaded, fetchedEpisodes, viewModel::loadSeason)
                }
                if (loaded.collectionName != null) {
                    PreviewSection("Franchise") {
                        Text(loaded.collectionName!!.removeSuffix(" Collection"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun PreviewScores(details: MediaDetails) {
    val scores = listOfNotNull(
        details.imdbRating?.let { "IMDb $it" },
        details.rtScore?.let { "Rotten Tomatoes $it%" },
        details.metacriticScore?.let { "Metacritic $it" },
        details.awardsCount?.takeIf { it > 0 }?.let { "$it awards" },
    )
    if (scores.isEmpty()) return
    PreviewSection("Critic scores") {
        Text(scores.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewEpisodes(
    details: MediaDetails,
    fetched: Map<Int, List<MediaEpisode>>,
    onLoadSeason: (Int) -> Unit,
) {
    val seasons = details.seasons.sortedWith(compareBy({ it.isSpecials }, { it.seasonNumber }))
    var selected by rememberSaveable { mutableStateOf(seasons.first().seasonNumber) }
    val season = seasons.firstOrNull { it.seasonNumber == selected } ?: seasons.first()
    val episodes = season.episodes.ifEmpty { fetched[season.seasonNumber].orEmpty() }
    LaunchedEffect(season.seasonNumber, season.episodes.isEmpty()) {
        if (season.episodes.isEmpty()) onLoadSeason(season.seasonNumber)
    }
    PreviewSection("Episodes") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            seasons.forEach { s ->
                FilterChip(
                    selected = s.seasonNumber == season.seasonNumber,
                    onClick = { selected = s.seasonNumber },
                    label = { Text(if (s.isSpecials) "Specials" else "S${s.seasonNumber}") },
                )
            }
        }
        if (episodes.isEmpty()) {
            Text(
                "${season.episodeCount} episodes",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        episodes.forEach { episode -> PreviewEpisodeRow(episode, season.seasonNumber) }
    }
}

@Composable
private fun PreviewEpisodeRow(episode: MediaEpisode, seasonNumber: Int) {
    var expanded by rememberSaveable("$seasonNumber-${episode.episodeNumber}") { mutableStateOf(false) }
    // Nothing here is in the library yet, so nothing has been watched.
    val spoilerHidden = isEpisodeSpoilerHidden(watched = false, LocalSpoilerProtection.current)
    Surface(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            episode.stillUrl?.takeUnless { spoilerHidden }?.let {
                PosterSurface(
                    tint = tintForKey("$seasonNumber-${episode.episodeNumber}"),
                    imageUrl = it,
                    modifier = Modifier.width(96.dp),
                    aspectRatio = 16f / 9f,
                    cornerRadius = 8.dp,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "E${episode.episodeNumber} · ${episode.name?.takeUnless { spoilerHidden } ?: "Episode ${episode.episodeNumber}"}",
                    style = MaterialTheme.typography.titleSmall,
                )
                val meta = listOfNotNull(episode.airDate, episode.runtime?.let { "${it}m" }).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                episode.synopsis?.takeIf { it.isNotBlank() && !spoilerHidden }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun previewMetaLine(result: MediaSearchResult, details: MediaDetails?): String {
    val seasonCount = details?.seasons?.count { !it.isSpecials } ?: 0
    return listOfNotNull(
        (details?.year ?: result.year)?.toString(),
        if (result.type == MediaType.TV) "TV Series" else "Movie",
        details?.contentRating,
        when {
            result.type == MediaType.TV && seasonCount > 0 -> "$seasonCount season${if (seasonCount == 1) "" else "s"}"
            details?.runtime != null -> "${details.runtime}m"
            else -> null
        },
    ).joinToString(" · ")
}
