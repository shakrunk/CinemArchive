package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.ChoiceOption
import work.kumarfamilynet.cinemarchive.core.designsystem.DraggableStarRating
import work.kumarfamilynet.cinemarchive.core.designsystem.PostShowSheet
import work.kumarfamilynet.cinemarchive.core.designsystem.PosterSurface
import work.kumarfamilynet.cinemarchive.core.designsystem.ReadingWidthColumn
import work.kumarfamilynet.cinemarchive.core.designsystem.SegmentedGroup
import work.kumarfamilynet.cinemarchive.core.designsystem.tintForKey
import work.kumarfamilynet.cinemarchive.core.model.CinemaFormat
import work.kumarfamilynet.cinemarchive.core.model.CinemaOuting
import work.kumarfamilynet.cinemarchive.core.model.CinemaOutingRules
import work.kumarfamilynet.cinemarchive.core.model.EpisodeDetail
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.SeatAssignment
import work.kumarfamilynet.cinemarchive.core.model.SeasonDetail
import work.kumarfamilynet.cinemarchive.core.model.TitleDetail
import work.kumarfamilynet.cinemarchive.core.model.Viewing
import work.kumarfamilynet.cinemarchive.data.LibraryRepository
import work.kumarfamilynet.cinemarchive.data.OutingsRepository
import coil.compose.AsyncImage

class TitleDetailViewModel(
    private val repository: LibraryRepository,
    private val outingsRepository: OutingsRepository,
    private val titleId: String,
) : ViewModel() {
    val uiState = repository.observeTitleDetail(titleId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val venueSuggestions = outingsRepository.observeVenueSuggestions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val companionSuggestions = outingsRepository.observeCompanionSuggestions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val venueNotes = outingsRepository.observeVenueNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        // Fire-and-forget, same as the web app's drawer-open effect: fills in episode
        // synopsis/stills for whichever seasons are missing them, independent of whether this
        // title was ever opened on the web app. See LibraryRepository.backfillEpisodeMetadata.
        viewModelScope.launch { repository.backfillEpisodeMetadata(titleId) }
    }

    /** Optimistic local write + queued remote push — see LibraryRepository.logEpisodeWatched. */
    fun onMarkWatched(episodeId: String) {
        viewModelScope.launch { repository.logEpisodeWatched(episodeId, LocalDate.now().toString()) }
    }

    fun onRateEpisode(episodeId: String, rating: Double) {
        viewModelScope.launch { repository.logEpisodeRating(episodeId, rating, Instant.now().toString()) }
    }

    fun onSubmitReview(episodeId: String, reviewText: String) {
        viewModelScope.launch { repository.logEpisodeReview(episodeId, reviewText, Instant.now().toString()) }
    }

    fun onLogViewing() {
        viewModelScope.launch { repository.logViewing(titleId, LocalDate.now().toString()) }
    }

    fun onChangeStatus(status: LibraryStatus) {
        viewModelScope.launch { repository.updateTitleStatus(titleId, status, Instant.now().toString()) }
    }

    fun onRateTitle(rating: Double) {
        viewModelScope.launch { repository.updateTitleRating(titleId, rating, Instant.now().toString()) }
    }

    fun onScheduleOuting(
        showtime: Instant,
        previewsMinutes: Int,
        runtimeMinutes: Int,
        venue: String?,
        companions: List<String>,
        format: CinemaFormat?,
        ticketPrice: Double?,
        seating: SeatAssignment,
        bookingRef: String?,
        notes: String?,
    ) {
        viewModelScope.launch {
            outingsRepository.scheduleOuting(titleId, showtime, previewsMinutes, runtimeMinutes, venue, companions, format, ticketPrice, seating, bookingRef, notes)
        }
    }

    fun onEditOuting(
        outingId: String,
        showtime: Instant,
        previewsMinutes: Int,
        runtimeMinutes: Int,
        venue: String?,
        companions: List<String>,
        format: CinemaFormat?,
        ticketPrice: Double?,
        seating: SeatAssignment,
        bookingRef: String?,
        notes: String?,
    ) {
        viewModelScope.launch {
            outingsRepository.updateOuting(outingId, showtime, previewsMinutes, runtimeMinutes, venue, companions, format, ticketPrice, seating, bookingRef, notes)
        }
    }

    fun onCancelOuting(outingId: String) {
        viewModelScope.launch { outingsRepository.cancelOuting(outingId) }
    }

    fun onRatePostShow(viewingId: String, rating: Double) {
        viewModelScope.launch { repository.rateViewing(viewingId, titleId, rating) }
    }

    fun onSaveFollowUpNotes(viewingId: String, notes: String) {
        viewModelScope.launch { repository.updateViewingNotes(viewingId, notes) }
    }

    fun onDidntMakeIt(outingId: String) {
        viewModelScope.launch { outingsRepository.revertCompletion(outingId) }
    }

    fun onSaveVenueNotes(venue: String, notes: String) {
        viewModelScope.launch { outingsRepository.saveVenueNotes(venue, notes) }
    }
}

@Composable
fun TitleDetailRoute(
    repository: LibraryRepository,
    outingsRepository: OutingsRepository,
    titleId: String,
    onBack: () -> Unit,
    onRequestNotificationPermission: () -> Unit = {},
) {
    val viewModel: TitleDetailViewModel =
        viewModel(key = titleId, factory = TitleDetailViewModelFactory(repository, outingsRepository, titleId))
    val detail by viewModel.uiState.collectAsStateWithLifecycle()
    val venueSuggestions by viewModel.venueSuggestions.collectAsStateWithLifecycle()
    val companionSuggestions by viewModel.companionSuggestions.collectAsStateWithLifecycle()
    val venueNotes by viewModel.venueNotes.collectAsStateWithLifecycle()
    TitleDetailScreen(
        detail,
        onBack,
        onMarkWatched = viewModel::onMarkWatched,
        onRateEpisode = viewModel::onRateEpisode,
        onSubmitReview = viewModel::onSubmitReview,
        onLogViewing = viewModel::onLogViewing,
        onChangeStatus = viewModel::onChangeStatus,
        onRateTitle = viewModel::onRateTitle,
        onScheduleOuting = viewModel::onScheduleOuting,
        onEditOuting = viewModel::onEditOuting,
        onCancelOuting = viewModel::onCancelOuting,
        onRatePostShow = viewModel::onRatePostShow,
        onSaveFollowUpNotes = viewModel::onSaveFollowUpNotes,
        onDidntMakeIt = viewModel::onDidntMakeIt,
        onRequestNotificationPermission = onRequestNotificationPermission,
        venueSuggestions = venueSuggestions,
        companionSuggestions = companionSuggestions,
        venueNotes = venueNotes,
        onSaveVenueNotes = viewModel::onSaveVenueNotes,
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TitleDetailScreen(
    detail: TitleDetail?,
    onBack: () -> Unit,
    onMarkWatched: (String) -> Unit = {},
    onRateEpisode: (String, Double) -> Unit = { _, _ -> },
    onSubmitReview: (String, String) -> Unit = { _, _ -> },
    onLogViewing: () -> Unit = {},
    onChangeStatus: (LibraryStatus) -> Unit = {},
    onRateTitle: (Double) -> Unit = {},
    onScheduleOuting: (Instant, Int, Int, String?, List<String>, CinemaFormat?, Double?, SeatAssignment, String?, String?) -> Unit = { _, _, _, _, _, _, _, _, _, _ -> },
    onEditOuting: (String, Instant, Int, Int, String?, List<String>, CinemaFormat?, Double?, SeatAssignment, String?, String?) -> Unit = { _, _, _, _, _, _, _, _, _, _, _ -> },
    onCancelOuting: (String) -> Unit = {},
    onRatePostShow: (String, Double) -> Unit = { _, _ -> },
    onSaveFollowUpNotes: (String, String) -> Unit = { _, _ -> },
    onDidntMakeIt: (String) -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    venueSuggestions: List<String> = emptyList(),
    companionSuggestions: List<String> = emptyList(),
    venueNotes: Map<String, String> = emptyMap(),
    onSaveVenueNotes: (String, String) -> Unit = { _, _ -> },
) {
    var showScheduleSheet by rememberSaveable { mutableStateOf(false) }
    var editingOuting by remember { mutableStateOf<CinemaOuting?>(null) }
    var postShowViewing by remember { mutableStateOf<Viewing?>(null) }
    // Keyed on the title id (not just rememberSaveable) so navigating from one series' detail
    // screen straight to another's doesn't carry over a season number that may not exist there.
    var selectedSeasonNumber by rememberSaveable(detail?.id) {
        mutableStateOf(detail?.seasons?.firstOrNull()?.seasonNumber ?: 0)
    }

    if (showScheduleSheet || editingOuting != null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onRequestNotificationPermission() }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (detail == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { DetailHero(detail, onBack) }
            item {
                ReadingWidthColumn(modifier = Modifier.padding(22.dp, 0.dp, 22.dp, 28.dp)) {
                    Text(detail.title, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        metaLine(detail),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                    )

                    detail.scheduledOuting?.let { outing ->
                        ScheduledOutingBanner(
                            outing = outing,
                            onEdit = { editingOuting = outing },
                            onCancel = { onCancelOuting(outing.id) },
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }

                    if (detail.type == MediaType.MOVIE && detail.scheduledOuting == null) {
                        TextButton(onClick = { editingOuting = null; showScheduleSheet = true }, modifier = Modifier.padding(bottom = 4.dp)) {
                            Icon(Icons.Filled.ConfirmationNumber, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                if (detail.status == LibraryStatus.WATCHED) "Plan a cinema trip" else "I've got tickets",
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }

                    if (detail.genres.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 20.dp)) {
                            detail.genres.forEach { genre ->
                                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                    Text(
                                        genre,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        "YOUR STATUS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    SegmentedGroup(
                        options = listOf(
                            ChoiceOption(LibraryStatus.WATCHLIST, "Watchlist"),
                            ChoiceOption(LibraryStatus.WATCHING, "Watching"),
                            ChoiceOption(LibraryStatus.WATCHED, "Watched"),
                            ChoiceOption(LibraryStatus.DROPPED, "Dropped"),
                        ),
                        selected = detail.status,
                        onSelect = onChangeStatus,
                        modifier = Modifier.padding(bottom = 20.dp),
                    )

                    Text(
                        "YOUR RATING",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    DraggableStarRating(
                        rating = detail.rating ?: 0.0,
                        onRatingChange = onRateTitle,
                        modifier = Modifier.padding(bottom = 22.dp),
                    )

                    Text("Synopsis", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                    detail.synopsis?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 22.dp),
                        )
                    }

                    if (detail.seasons.isNotEmpty()) {
                        val totalEpisodes = detail.seasons.sumOf { it.episodeCount }
                        val watchedEpisodes = detail.seasons.sumOf { it.episodesWatched }
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "$watchedEpisodes / $totalEpisodes episodes watched",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(bottom = 10.dp),
                                )
                                val pct = if (totalEpisodes > 0) watchedEpisodes.toFloat() / totalEpisodes else 0f
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth(pct)
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(MaterialTheme.colorScheme.primary),
                                    ) {}
                                }
                            }
                        }
                    }
                }
            }

            if (detail.seasons.isNotEmpty()) {
                item {
                    ReadingWidthColumn {
                        Text(
                            "Seasons",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                        )
                    }
                }
                item {
                    ReadingWidthColumn {
                        SeasonSelector(
                            seasons = detail.seasons,
                            selectedSeasonNumber = selectedSeasonNumber,
                            onSelect = { selectedSeasonNumber = it },
                        )
                    }
                }
                item { Box(modifier = Modifier.height(14.dp)) }

                val selectedSeason = detail.seasons.firstOrNull { it.seasonNumber == selectedSeasonNumber }
                    ?: detail.seasons.first()
                items(selectedSeason.episodes, key = EpisodeDetail::id) { episode ->
                    ReadingWidthColumn {
                        EpisodeRow(
                            episode,
                            onMarkWatched,
                            onRateEpisode,
                            onSubmitReview,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            item {
                ReadingWidthColumn {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(22.dp, 16.dp, 22.dp, 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Viewing history", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onLogViewing) { Text("Log a viewing") }
                    }
                }
            }
            items(detail.viewings, key = Viewing::id) { viewing ->
                ReadingWidthColumn {
                    ViewingRow(
                        viewing,
                        onRateClick = { postShowViewing = viewing },
                        modifier = Modifier.padding(horizontal = 22.dp),
                    )
                }
            }
            item { Box(modifier = Modifier.height(28.dp)) }
        }
    }

    if (showScheduleSheet || editingOuting != null) {
        OutingScheduleSheet(
            defaultRuntimeMinutes = detail?.runtime,
            initial = editingOuting,
            onDismiss = { showScheduleSheet = false; editingOuting = null },
            onSave = { showtime, previews, runtime, venue, companions, format, price, seating, bookingRef, notes ->
                val outing = editingOuting
                if (outing != null) {
                    onEditOuting(outing.id, showtime, previews, runtime, venue, companions, format, price, seating, bookingRef, notes)
                } else {
                    onScheduleOuting(showtime, previews, runtime, venue, companions, format, price, seating, bookingRef, notes)
                }
            },
            venueSuggestions = venueSuggestions,
            companionSuggestions = companionSuggestions,
            venueNotes = venueNotes,
            onSaveVenueNotes = onSaveVenueNotes,
        )
    }

    postShowViewing?.let { viewing ->
        PostShowSheet(
            titleName = detail?.title ?: "",
            venue = viewing.venue,
            companions = viewing.companions,
            initialRating = viewing.rating ?: 0.0,
            initialNotes = viewing.notes ?: "",
            onRate = { onRatePostShow(viewing.id, it) },
            onSaveNotes = { onSaveFollowUpNotes(viewing.id, it) },
            onDidntMakeIt = {
                viewing.outingId?.let(onDidntMakeIt)
                postShowViewing = null
            },
            onDismiss = { postShowViewing = null },
        )
    }
}

private fun metaLine(detail: TitleDetail): String = listOfNotNull(
    detail.year?.toString(),
    detail.director ?: detail.network,
    if (detail.type == MediaType.TV) {
        "${detail.seasons.size} season${if (detail.seasons.size == 1) "" else "s"}"
    } else {
        detail.runtime?.let { "$it min" }
    },
).joinToString(" · ")

@Composable
private fun ScheduledOutingBanner(outing: CinemaOuting, onEdit: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val now = remember { Instant.now() }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ConfirmationNumber, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                Text(
                    CinemaOutingRules.countdownLabel(outing, now),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            val details = listOfNotNull(outing.venue, outing.companions.takeIf { it.isNotEmpty() }?.let { "with ${it.joinToString(" & ")}" })
                .joinToString(" · ")
            if (details.isNotBlank()) {
                Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(top = 2.dp))
            }
            Row(modifier = Modifier.padding(top = 6.dp)) {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onCancel) { Text("Cancel outing") }
            }
        }
    }
}

@Composable
private fun DetailHero(detail: TitleDetail, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        Box(modifier = Modifier.fillMaxSize().background(tintForKey(detail.id)))
        detail.backdropUrl?.let { url ->
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.15f), MaterialTheme.colorScheme.background),
                    ),
                ),
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(16.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.4f)),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = Color.White)
        }
    }
}

/** Horizontally scrollable season tabs — the same underlying "pick one of N" interaction as the
 *  web app's season pills/dropdown (`TVSeriesSection`), but a single scrollable-chip idiom
 *  regardless of season count instead of switching UI shape past three seasons. */
@Composable
private fun SeasonSelector(
    seasons: List<SeasonDetail>,
    selectedSeasonNumber: Int,
    onSelect: (Int) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 22.dp),
    ) {
        items(seasons, key = SeasonDetail::id) { season ->
            val pct = if (season.episodeCount > 0) season.episodesWatched * 100 / season.episodeCount else 0
            FilterChip(
                selected = season.seasonNumber == selectedSeasonNumber,
                onClick = { onSelect(season.seasonNumber) },
                label = { Text("S${season.seasonNumber} · $pct%") },
            )
        }
    }
}

/** One episode of the selected season: a 16:9 still (falling back to a tinted placeholder,
 *  same pattern as [PosterSurface]'s poster usage elsewhere), name/air-date/runtime, a
 *  tap-to-expand synopsis, and the mark-watched/star-rating/review actions unchanged from
 *  before this screen grew thumbnails. */
@Composable
private fun EpisodeRow(
    episode: EpisodeDetail,
    onMarkWatched: (String) -> Unit,
    onRateEpisode: (String, Double) -> Unit,
    onSubmitReview: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val watched = episode.watchCount > 0
    var reviewExpanded by rememberSaveable(episode.id) { mutableStateOf(false) }
    var reviewText by rememberSaveable(episode.id) { mutableStateOf("") }
    var synopsisExpanded by rememberSaveable(episode.id) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PosterSurface(
                    tint = tintForKey(episode.id),
                    imageUrl = episode.stillUrl,
                    modifier = Modifier.width(128.dp),
                    aspectRatio = 16f / 9f,
                    cornerRadius = 10.dp,
                ) {
                    if (watched) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Watched",
                            tint = Color.White,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(18.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "E${episode.episodeNumber}" + (episode.episodeName?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(episode.airDate, episode.runtime?.let { "$it min" }).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(
                            meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (watched && episode.watchCount > 1) {
                        Text(
                            "Watched ${episode.watchCount}×",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            episode.synopsis?.takeIf { it.isNotBlank() }?.let { synopsis ->
                Text(
                    synopsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (synopsisExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clickable { synopsisExpanded = !synopsisExpanded },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                if (!watched) {
                    TextButton(onClick = { onMarkWatched(episode.id) }) {
                        Text("Mark watched")
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                for (star in 1..5) {
                    val filled = star <= (episode.latestRating ?: 0.0)
                    Icon(
                        if (filled) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "Rate $star star${if (star == 1) "" else "s"}",
                        tint = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { onRateEpisode(episode.id, star.toDouble()) }
                            .padding(2.dp)
                            .size(18.dp),
                    )
                }
                TextButton(onClick = { reviewExpanded = !reviewExpanded }) { Text("Review") }
            }
            if (reviewExpanded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = reviewText,
                        onValueChange = { reviewText = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Your review") },
                    )
                    TextButton(
                        onClick = {
                            onSubmitReview(episode.id, reviewText)
                            reviewText = ""
                            reviewExpanded = false
                        },
                        enabled = reviewText.isNotBlank(),
                    ) { Text("Submit") }
                }
            }
        }
    }
}

@Composable
private fun ViewingRow(viewing: Viewing, onRateClick: () -> Unit, modifier: Modifier = Modifier) {
    // Ticket stub — degrades gracefully when only one of venue/companions is present (web
    // plan §13's polish checklist), and only shows for outing-linked viewings.
    val stub = listOfNotNull(
        viewing.venue?.let { "at $it" },
        viewing.companions.takeIf { it.isNotEmpty() }?.let { "with ${it.joinToString(" & ")}" },
    ).joinToString(" · ")
    val isTicketStub = viewing.outingId != null && stub.isNotBlank()

    Column(modifier = modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(viewing.date ?: "Before joining", style = MaterialTheme.typography.bodyMedium)
            if (isTicketStub) {
                Icon(
                    Icons.Filled.ConfirmationNumber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        viewing.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (isTicketStub) {
            Text(stub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (viewing.outingId != null && viewing.rating == null) {
            TextButton(onClick = onRateClick, modifier = Modifier.padding(top = 2.dp)) { Text("How was it?") }
        }
    }
}

private class TitleDetailViewModelFactory(
    private val repository: LibraryRepository,
    private val outingsRepository: OutingsRepository,
    private val titleId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TitleDetailViewModel(repository, outingsRepository, titleId) as T
}
