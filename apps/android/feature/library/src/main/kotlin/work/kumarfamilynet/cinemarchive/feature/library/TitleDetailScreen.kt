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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocalMovies
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import work.kumarfamilynet.cinemarchive.core.designsystem.AddToListSheet
import work.kumarfamilynet.cinemarchive.core.designsystem.ChoiceOption
import work.kumarfamilynet.cinemarchive.core.designsystem.DraggableStarRating
import work.kumarfamilynet.cinemarchive.core.designsystem.ListMembershipOption
import work.kumarfamilynet.cinemarchive.core.designsystem.PostShowSheet
import work.kumarfamilynet.cinemarchive.core.designsystem.PosterSurface
import work.kumarfamilynet.cinemarchive.core.designsystem.ReadingWidthColumn
import work.kumarfamilynet.cinemarchive.core.designsystem.SegmentedGroup
import work.kumarfamilynet.cinemarchive.core.designsystem.tintForKey
import work.kumarfamilynet.cinemarchive.core.model.CinemaFormat
import work.kumarfamilynet.cinemarchive.core.model.CinemaOuting
import work.kumarfamilynet.cinemarchive.core.model.CinemaOutingRules
import work.kumarfamilynet.cinemarchive.core.model.EpisodeCast
import work.kumarfamilynet.cinemarchive.core.model.EpisodeCastMember
import work.kumarfamilynet.cinemarchive.core.model.EpisodeDetail
import work.kumarfamilynet.cinemarchive.core.model.EpisodeLogDraft
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.ScheduledEpisode
import work.kumarfamilynet.cinemarchive.core.model.SeatAssignment
import work.kumarfamilynet.cinemarchive.core.model.SeasonDetail
import work.kumarfamilynet.cinemarchive.core.model.TitleDetail
import work.kumarfamilynet.cinemarchive.core.model.LibraryPerson
import work.kumarfamilynet.cinemarchive.core.model.Viewing
import work.kumarfamilynet.cinemarchive.core.model.ViewingDraft
import work.kumarfamilynet.cinemarchive.core.model.isUnaired
import work.kumarfamilynet.cinemarchive.core.model.mainSeasons
import work.kumarfamilynet.cinemarchive.core.model.nextScheduledEpisode
import work.kumarfamilynet.cinemarchive.core.model.orderedForDisplay
import work.kumarfamilynet.cinemarchive.core.model.seasonShortLabel
import work.kumarfamilynet.cinemarchive.data.LibraryRepository
import work.kumarfamilynet.cinemarchive.data.ListsRepository
import work.kumarfamilynet.cinemarchive.data.OutingsRepository
import coil.compose.AsyncImage

class TitleDetailViewModel(
    private val repository: LibraryRepository,
    private val outingsRepository: OutingsRepository,
    private val listsRepository: ListsRepository,
    private val titleId: String,
    catalogExtrasSource: work.kumarfamilynet.cinemarchive.data.CatalogExtrasSource? = null,
) : ViewModel() {
    val catalogExtras = catalogExtrasSource?.let { CatalogExtrasController(it, viewModelScope) }
    private val _titleEditError = MutableStateFlow<String?>(null)
    val titleEditError = _titleEditError.asStateFlow()
    private fun editTitle(block: suspend () -> Unit) {
        viewModelScope.launch {
            _titleEditError.value = null
            try { block() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { _titleEditError.value = "Couldn't save this title change. Try again." }
        }
    }
    suspend fun saveTags(tags: List<String>) = repository.updateTitleTags(titleId, tags)
    val uiState = repository.observeTitleDetail(titleId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Every list, each flagged with whether this title is currently a member — what
     *  [AddToListSheet] needs to render its checkbox rows. */
    val listOptions = kotlinx.coroutines.flow.combine(
        listsRepository.observeLists(),
        listsRepository.observeListIdsForTitle(titleId),
    ) { lists, memberIds ->
        lists.map { ListMembershipOption(it.id, it.name, memberIds.contains(it.id)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val venueSuggestions = outingsRepository.observeVenueSuggestions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val companionSuggestions = outingsRepository.observeCompanionSuggestions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val venueNotes = outingsRepository.observeVenueNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // Per-episode cast, keyed by episode id. A present key with a null value means the fetch is
    // in flight; a result is kept for the screen's lifetime so re-expanding doesn't refetch.
    private val _episodeCast = MutableStateFlow<Map<String, EpisodeCast?>>(emptyMap())
    val episodeCast: StateFlow<Map<String, EpisodeCast?>> = _episodeCast.asStateFlow()

    init {
        // Fire-and-forget, same as the web app's drawer-open effect: fills in episode
        // synopsis/stills for whichever seasons are missing them, independent of whether this
        // title was ever opened on the web app. See LibraryRepository.backfillEpisodeMetadata.
        viewModelScope.launch { repository.backfillEpisodeMetadata(titleId) }
    }

    suspend fun saveEpisodeLog(episodeId: String, draft: EpisodeLogDraft) = repository.saveEpisodeLog(episodeId, draft)

    suspend fun deleteEpisodeWatch(episodeId: String, eventId: String) = repository.deleteEpisodeWatchEvent(episodeId, eventId)

    /** Loads an episode's cast the first time its cast section is opened — see
     *  LibraryRepository.fetchEpisodeCast. */
    fun onLoadEpisodeCast(episodeId: String, seasonNumber: Int, episodeNumber: Int) {
        if (episodeId in _episodeCast.value) return
        _episodeCast.update { it + (episodeId to null) }
        viewModelScope.launch {
            val cast = repository.fetchEpisodeCast(titleId, seasonNumber, episodeNumber)
            _episodeCast.update { it + (episodeId to cast) }
        }
    }

    suspend fun saveViewing(draft: ViewingDraft, isNew: Boolean) = repository.saveViewing(titleId, draft, isNew)

    suspend fun prepareViewing(viewingId: String?) = repository.prepareViewingEdit(titleId, viewingId)
    suspend fun deleteViewing(draft: ViewingDraft) = repository.deleteViewing(titleId, draft)

    fun onChangeStatus(status: LibraryStatus) {
        editTitle { repository.updateTitleStatus(titleId, status, Instant.now().toString()) }
    }

    /** "I want to see this in theaters" (GitHub #205). */
    fun onToggleTheaterInterest(interested: Boolean) {
        editTitle { repository.setTheaterInterest(titleId, interested) }
    }

    fun onRateTitle(rating: Double) {
        editTitle { repository.updateTitleRating(titleId, rating, Instant.now().toString()) }
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

    /** Toggles this title's membership in one list — mirrors [AddToListSheet]'s checkbox rows
     *  1:1, so the caller doesn't need to know current membership state itself. */
    fun onToggleListMembership(listId: String) {
        viewModelScope.launch {
            val isMember = listOptions.value.find { it.listId == listId }?.isMember ?: false
            if (isMember) listsRepository.removeTitleFromList(listId, titleId) else listsRepository.addTitleToList(listId, titleId)
        }
    }

    fun onCreateAndAddToList(name: String) {
        viewModelScope.launch {
            val id = listsRepository.createList(name, description = null)
            listsRepository.addTitleToList(id, titleId)
        }
    }

    /** "Remove from library" — a hard delete with no undo, see [LibraryRepository.removeTitle].
     *  [onRemoved] closes the detail screen once the local write lands, since [uiState] would
     *  otherwise just start emitting null for a title that no longer exists. */
    fun onRemoveTitle(onRemoved: () -> Unit) {
        viewModelScope.launch {
            repository.removeTitle(titleId)
            onRemoved()
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TitleDetailRoute(
    repository: LibraryRepository,
    outingsRepository: OutingsRepository,
    listsRepository: ListsRepository,
    titleId: String,
    onBack: () -> Unit,
    onViewTicket: ((CinemaOuting, String) -> Unit)? = null,
    onRequestNotificationPermission: () -> Unit = {},
    socialContent: (@Composable (TitleDetail) -> Unit)? = null,
    onRecommendTitle: ((String) -> Unit)? = null,
    onShareOutingPlans: ((String) -> Unit)? = null,
    onBrowsePerson: ((LibraryPerson) -> Unit)? = null,
    onRefreshCredits: (suspend () -> Boolean)? = null,
    catalogExtrasSource: work.kumarfamilynet.cinemarchive.data.CatalogExtrasSource? = null,
    titleMetadataRecovery: work.kumarfamilynet.cinemarchive.data.TitleMetadataRecoverySource? = null,
) {
    val ticketOutings by remember(outingsRepository, titleId) { outingsRepository.observeOutingsForTitle(titleId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val viewModel: TitleDetailViewModel =
        viewModel(key = titleId, factory = TitleDetailViewModelFactory(repository, outingsRepository, listsRepository, titleId, catalogExtrasSource))
    val detail by viewModel.uiState.collectAsStateWithLifecycle()
    val titleEditError by viewModel.titleEditError.collectAsStateWithLifecycle()
    val languageTag = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].toLanguageTag()
    val catalogKey = detail?.let { current -> current.tmdbId?.takeIf { it > 0 }?.let {
            work.kumarfamilynet.cinemarchive.core.model.CatalogExtrasKey(it, current.type,
                work.kumarfamilynet.cinemarchive.core.model.catalogWatchRegion(languageTag))
        }
    }
    androidx.compose.runtime.LaunchedEffect(catalogKey) {
        viewModel.catalogExtras?.select(catalogKey)
    }
    val catalogExtrasState = viewModel.catalogExtras?.state?.collectAsStateWithLifecycle()?.value?.takeIf { it.key == catalogKey }
    val venueSuggestions by viewModel.venueSuggestions.collectAsStateWithLifecycle()
    val companionSuggestions by viewModel.companionSuggestions.collectAsStateWithLifecycle()
    val venueNotes by viewModel.venueNotes.collectAsStateWithLifecycle()
    val listOptions by viewModel.listOptions.collectAsStateWithLifecycle()
    val episodeCast by viewModel.episodeCast.collectAsStateWithLifecycle()
    var showAddToListSheet by rememberSaveable { mutableStateOf(false) }
    var editVenue by rememberSaveable { mutableStateOf<String?>(null) }
    editVenue?.let { venue -> outingsRepository.moviegoingPreferences?.let { preferences ->
        VenueNoteEditor(preferences, venue) { editVenue = null }
    } }
    if (showAddToListSheet && detail != null) {
        AddToListSheet(
            titleName = detail!!.title,
            lists = listOptions,
            onToggle = viewModel::onToggleListMembership,
            onCreateList = viewModel::onCreateAndAddToList,
            onDismiss = { showAddToListSheet = false },
        )
    }
    TitleDetailScreen(
        detail,
        onBack,
        onSaveEpisodeLog = viewModel::saveEpisodeLog,
        onDeleteEpisodeWatch = viewModel::deleteEpisodeWatch,
        onSaveViewing = viewModel::saveViewing,
        onDeleteViewing = viewModel::deleteViewing,
        onPrepareViewing = viewModel::prepareViewing,
        viewingOwnerId = repository.viewingOwnerId,
        viewingTitleId = titleId,
        onChangeStatus = viewModel::onChangeStatus,
        onToggleTheaterInterest = viewModel::onToggleTheaterInterest,
        onRateTitle = viewModel::onRateTitle,
        onScheduleOuting = viewModel::onScheduleOuting,
        onEditOuting = viewModel::onEditOuting,
        onCancelOuting = viewModel::onCancelOuting,
        onViewTicket = onViewTicket,
        ticketOutings = ticketOutings,
        onRatePostShow = viewModel::onRatePostShow,
        onSaveFollowUpNotes = viewModel::onSaveFollowUpNotes,
        onDidntMakeIt = viewModel::onDidntMakeIt,
        onRequestNotificationPermission = onRequestNotificationPermission,
        venueSuggestions = venueSuggestions,
        companionSuggestions = companionSuggestions,
        venueNotes = venueNotes,
        onEditVenueNote = { editVenue = it },
        onRemoveTitle = { viewModel.onRemoveTitle(onRemoved = onBack) },
        listOptions = listOptions,
        onOpenAddToList = { showAddToListSheet = true },
        episodeCast = episodeCast,
        onLoadEpisodeCast = viewModel::onLoadEpisodeCast,
        socialContent = socialContent,
        onRecommendTitle = onRecommendTitle,
        onShareOutingPlans = onShareOutingPlans,
        onBrowsePerson = onBrowsePerson,
        onRefreshCredits = onRefreshCredits,
        catalogExtrasState = catalogExtrasState,
        onRetryVideos = { viewModel.catalogExtras?.retryVideos() },
        onRetryProviders = { viewModel.catalogExtras?.retryProviders() },
        onSaveTags = viewModel::saveTags,
        titleEditError = titleEditError,
        titleMetadataRecovery = titleMetadataRecovery,
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TitleDetailScreen(
    detail: TitleDetail?,
    onBack: () -> Unit,
    onSaveEpisodeLog: suspend (String, EpisodeLogDraft) -> Unit = { _, _ -> },
    onDeleteEpisodeWatch: suspend (String, String) -> Unit = { _, _ -> },
    onSaveViewing: suspend (ViewingDraft, Boolean) -> Unit = { _, _ -> },
    onDeleteViewing: suspend (ViewingDraft) -> Unit = {},
    onChangeStatus: (LibraryStatus) -> Unit = {},
    onToggleTheaterInterest: (Boolean) -> Unit = {},
    onRateTitle: (Double) -> Unit = {},
    onScheduleOuting: (Instant, Int, Int, String?, List<String>, CinemaFormat?, Double?, SeatAssignment, String?, String?) -> Unit = { _, _, _, _, _, _, _, _, _, _ -> },
    onEditOuting: (String, Instant, Int, Int, String?, List<String>, CinemaFormat?, Double?, SeatAssignment, String?, String?) -> Unit = { _, _, _, _, _, _, _, _, _, _, _ -> },
    onCancelOuting: (String) -> Unit = {},
    onViewTicket: ((CinemaOuting, String) -> Unit)? = null,
    ticketOutings: List<CinemaOuting> = emptyList(),
    onRatePostShow: (String, Double) -> Unit = { _, _ -> },
    onSaveFollowUpNotes: (String, String) -> Unit = { _, _ -> },
    onDidntMakeIt: (String) -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    venueSuggestions: List<String> = emptyList(),
    companionSuggestions: List<String> = emptyList(),
    venueNotes: Map<String, String> = emptyMap(),
    onEditVenueNote: ((String) -> Unit)? = null,
    onRemoveTitle: () -> Unit = {},
    listOptions: List<ListMembershipOption> = emptyList(),
    onOpenAddToList: () -> Unit = {},
    episodeCast: Map<String, EpisodeCast?> = emptyMap(),
    onLoadEpisodeCast: (String, Int, Int) -> Unit = { _, _, _ -> },
    socialContent: (@Composable (TitleDetail) -> Unit)? = null,
    onRecommendTitle: ((String) -> Unit)? = null,
    onShareOutingPlans: ((String) -> Unit)? = null,
    onBrowsePerson: ((LibraryPerson) -> Unit)? = null,
    onRefreshCredits: (suspend () -> Boolean)? = null,
    catalogExtrasState: CatalogExtrasState? = null,
    onRetryVideos: () -> Unit = {},
    onRetryProviders: () -> Unit = {},
    onSaveTags: (suspend (List<String>) -> Unit)? = null,
    titleEditError: String? = null,
    titleMetadataRecovery: work.kumarfamilynet.cinemarchive.data.TitleMetadataRecoverySource? = null,
    onPrepareViewing: (suspend (String?) -> ViewingDraft)? = null,
    viewingOwnerId: String? = null,
    viewingTitleId: String? = detail?.id,
) {
    var showScheduleSheet by rememberSaveable { mutableStateOf(false) }
    var editingOuting by remember { mutableStateOf<CinemaOuting?>(null) }
    var postShowViewing by remember { mutableStateOf<Viewing?>(null) }
    var editingViewingState by rememberSaveable(viewingTitleId, viewingOwnerId) { mutableStateOf<String?>(null) }
    var deletingViewingState by rememberSaveable(viewingTitleId, viewingOwnerId) { mutableStateOf<String?>(null) }
    val editingViewing = restoreViewingEditor(editingViewingState, viewingOwnerId, viewingTitleId)
    val deletingViewing = restoreViewingEditor(deletingViewingState, viewingOwnerId, viewingTitleId)?.draft
    var preparingViewing by remember { mutableStateOf(false) }
    var viewingOpenError by remember { mutableStateOf<String?>(null) }
    val currentHistoryOwner by androidx.compose.runtime.rememberUpdatedState(viewingOwnerId)
    val currentHistoryTitle by androidx.compose.runtime.rememberUpdatedState(viewingTitleId)
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val historyScope = rememberCoroutineScope()
    fun openViewing(viewing: Viewing?, deleting: Boolean = false) {
        val openingTitle = detail?.id ?: return
        val openingOwner = viewingOwnerId
        preparingViewing = true
        viewingOpenError = null
        historyScope.launch {
            try {
                val draft = onPrepareViewing?.invoke(viewing?.id) ?: viewing?.let {
                    ViewingDraft(it.id, it.date?.take(10), it.rating, it.notes, it.venue, it.companions)
                } ?: ViewingDraft(java.util.UUID.randomUUID().toString(), java.time.LocalDate.now().toString(), null, null, null)
                if (currentHistoryOwner != openingOwner || currentHistoryTitle != openingTitle) return@launch
                val saved = saveViewingEditor(openingOwner, openingTitle, draft, viewing == null)
                if (deleting) { deletingViewingState = saved; deleteError = null } else editingViewingState = saved
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (currentHistoryOwner == openingOwner) viewingOpenError = e.message ?: "Couldn't open this viewing." }
            finally { preparingViewing = false }
        }
    }
    var showRemoveConfirm by rememberSaveable { mutableStateOf(false) }
    // Keyed on the title id (not just rememberSaveable) so navigating from one series' detail
    // screen straight to another's doesn't carry over a season number that may not exist there.
    var selectedSeasonNumber by rememberSaveable(detail?.id) {
        mutableStateOf(detail?.seasons?.orderedForDisplay()?.firstOrNull()?.seasonNumber ?: 1)
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

                    if (onViewTicket != null) ticketOutings.forEach { outing ->
                        TextButton(onClick = { onViewTicket(outing, detail.title) }) {
                            Text("Ticket · ${outing.venue ?: "Cinema"} · ${outing.showtime.take(10)}")
                        }
                    }

                    detail.scheduledOuting?.let { outing ->
                        ScheduledOutingBanner(
                            outing = outing,
                            onShare = onShareOutingPlans?.let { share -> { share(outing.id) } },
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
                        // Lighter-weight than scheduling an outing (GitHub #205) — flags intent
                        // to catch this one in a theater before a showtime is even picked. Up
                        // Next surfaces a prompt for this once the release date passes.
                        FilterChip(
                            selected = detail.interestedInTheaters,
                            onClick = { onToggleTheaterInterest(!detail.interestedInTheaters) },
                            label = { Text(if (detail.interestedInTheaters) "Want to see in theaters" else "Want to see in theaters?") },
                            leadingIcon = { Icon(Icons.Filled.LocalMovies, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
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
                        "LISTS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 20.dp)) {
                        val memberOf = listOptions.filter { it.isMember }
                        if (memberOf.isEmpty()) {
                            Text(
                                "Not in any lists yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                                memberOf.take(3).forEach { option ->
                                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                        Text(
                                            option.name,
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                        TextButton(onClick = onOpenAddToList) { Text(if (listOptions.any { it.isMember }) "Edit" else "Add to list") }
                    }

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
                        // Series progress covers the main seasons only — see Specials.kt.
                        val seriesSeasons = detail.seasons.mainSeasons()
                        val totalEpisodes = seriesSeasons.sumOf { it.episodeCount }
                        val watchedEpisodes = seriesSeasons.sumOf { it.episodesWatched }
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

            item(key = "catalog-details") {
                ReadingWidthColumn(modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                    CatalogDetailsSection(detail, showTags = onSaveTags == null)
                }
            }
            if (onSaveTags != null || titleEditError != null || titleMetadataRecovery != null) {
                item(key = "title-metadata-edits") {
                    ReadingWidthColumn(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            onSaveTags?.let { TitleTagsEditor(detail.id, detail.tags, it) }
                            titleEditError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            titleMetadataRecovery?.let { TitleMetadataRecoveryPanel(it, detail.id) }
                        }
                    }
                }
            }
            if (catalogExtrasState?.key != null) {
                item(key = "catalog-extras") {
                    ReadingWidthColumn(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                        CatalogExtrasSection(catalogExtrasState, onRetryVideos, onRetryProviders)
                    }
                }
            }

            if (detail.cast.isNotEmpty() || detail.crew.isNotEmpty() || onRefreshCredits != null) {
                item(key = "title-credits") {
                    ReadingWidthColumn(modifier = Modifier.padding(horizontal = 22.dp)) {
                        PersonCreditsSection("Cast", detail.cast, onBrowsePerson)
                        PersonCreditsSection("Crew", detail.crew, onBrowsePerson)
                        onRefreshCredits?.let { CreditRefreshControl(detail.id, it) }
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
                detail.seasons.nextScheduledEpisode()?.let { next ->
                    item {
                        ReadingWidthColumn {
                            NextEpisodeBanner(
                                next,
                                onClick = { selectedSeasonNumber = next.season.seasonNumber },
                                modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 10.dp),
                            )
                        }
                    }
                }
                item {
                    ReadingWidthColumn {
                        SeasonSelector(
                            seasons = detail.seasons.orderedForDisplay(),
                            selectedSeasonNumber = selectedSeasonNumber,
                            onSelect = { selectedSeasonNumber = it },
                        )
                    }
                }
                item { Box(modifier = Modifier.height(14.dp)) }

                val selectedSeason = detail.seasons.firstOrNull { it.seasonNumber == selectedSeasonNumber }
                    ?: detail.seasons.orderedForDisplay().first()
                if (selectedSeason.cast.isNotEmpty()) item(key = "season-credits") {
                    ReadingWidthColumn(modifier = Modifier.padding(horizontal = 22.dp)) {
                        PersonCreditsSection("Season cast", selectedSeason.cast, onBrowsePerson)
                    }
                }
                items(selectedSeason.episodes, key = EpisodeDetail::id) { episode ->
                    ReadingWidthColumn {
                        EpisodeRow(
                            episode,
                            onSaveEpisodeLog,
                            onDeleteEpisodeWatch,
                            cast = episodeCast[episode.id],
                            onBrowsePerson = onBrowsePerson,
                            onShowCast = { onLoadEpisodeCast(episode.id, selectedSeason.seasonNumber, episode.episodeNumber) },
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
                        TextButton(enabled = !preparingViewing, onClick = { openViewing(null) }) { Text("Log a viewing") }
                    }
                }
            }
            items(detail.viewings, key = Viewing::id) { viewing ->
                ReadingWidthColumn {
                    ViewingRow(
                        viewing,
                        onRateClick = { postShowViewing = viewing },
                        onEditClick = { if (!preparingViewing) openViewing(viewing) },
                        onDeleteClick = { if (!preparingViewing) openViewing(viewing, deleting = true) },
                        modifier = Modifier.padding(horizontal = 22.dp),
                    )
                }
            }
            if (socialContent != null) {
                item(key = "social") { ReadingWidthColumn { socialContent(detail) } }
            }
            item {
                ReadingWidthColumn {
                    TextButton(
                        onClick = { showRemoveConfirm = true },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            "Remove from library",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
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
            onEditVenueNote = onEditVenueNote,
            onManageTicket = onViewTicket?.let { open -> { outing ->
                showScheduleSheet = false; editingOuting = null
                open(outing, detail?.title ?: "Cinema outing")
            } },
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
            onRecommend = if (detail?.tmdbId != null && onRecommendTitle != null) ({ onRecommendTitle(detail.id) }) else null,
        )
    }

    viewingOpenError?.let { message ->
        AlertDialog(onDismissRequest = { viewingOpenError = null }, title = { Text("Viewing unavailable") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { viewingOpenError = null }) { Text("Close") } })
    }
    if (editingViewing != null) {
        androidx.compose.runtime.key(editingViewingState) {
            ViewingEditorSheet(editingViewing.draft, editingViewing.isNew, onSaveViewing, onDismiss = { editingViewingState = null })
        }
    }
    deletingViewing?.let { viewing ->
        AlertDialog(
            onDismissRequest = { if (!deleting) deletingViewingState = null },
            title = { Text("Delete this viewing?") },
            text = { Text(deleteError ?: "Remove the viewing from ${viewing.date ?: "before joining"}? Other viewings stay in your history.") },
            dismissButton = { TextButton(enabled = !deleting, onClick = { deletingViewingState = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    deleting = true
                    deleteError = null
                    historyScope.launch {
                        try {
                            onDeleteViewing(viewing)
                            deletingViewingState = null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            deleteError = e.message ?: "Couldn't delete viewing. Try again."
                        } finally { deleting = false }
                    }
                }) { Text(if (deleting) "Deleting…" else "Delete viewing", color = MaterialTheme.colorScheme.error) }
            },
        )
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove from library forever?") },
            text = { Text("This deletes ${detail?.title ?: "this title"} and its watch history. There's no undo.") },
            confirmButton = {
                TextButton(onClick = { showRemoveConfirm = false; onRemoveTitle() }) {
                    Text("Delete forever", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

private fun metaLine(detail: TitleDetail): String = listOfNotNull(
    detail.year?.toString(),
    detail.director ?: detail.network,
    if (detail.type == MediaType.TV) {
        // Specials aren't counted as a season.
        val seasonCount = detail.seasons.mainSeasons().size
        "$seasonCount season${if (seasonCount == 1) "" else "s"}"
    } else {
        detail.runtime?.let { "$it min" }
    },
).joinToString(" · ")

@Composable
private fun ScheduledOutingBanner(outing: CinemaOuting, onEdit: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier, onShare: (() -> Unit)? = null) {
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
            onShare?.let { TextButton(onClick = it) { Text("Share plans") } }
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

/** Next scheduled (not yet aired) episode — mirrors the web drawer's "Next episode" callout in
 *  `TVSeriesSection`. Tapping it switches to that episode's season. */
@Composable
private fun NextEpisodeBanner(next: ScheduledEpisode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val episode = next.episode
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
            Icon(
                Icons.Filled.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    "Next episode",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${seasonShortLabel(next.season.seasonNumber)} E${episode.episodeNumber}" +
                        (episode.episodeName?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "Airs ${formatAirDate(episode.airDate!!)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Parses the plain YYYY-MM-DD as a local date (not an instant) so no timezone shift can push
 *  it a day off — same format as the web app's `fmtReleaseDate` ("Oct 2, 2026"). */
private fun formatAirDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)) }
        .getOrDefault(iso)

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
                label = { Text("${seasonShortLabel(season.seasonNumber)} · $pct%") },
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
    onSaveEpisodeLog: suspend (String, EpisodeLogDraft) -> Unit,
    onDeleteEpisodeWatch: suspend (String, String) -> Unit,
    cast: EpisodeCast?,
    onShowCast: () -> Unit,
    onBrowsePerson: ((LibraryPerson) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val watched = episode.watchCount > 0
    var castExpanded by rememberSaveable(episode.id) { mutableStateOf(false) }
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
                    val unaired = episode.isUnaired()
                    val airLabel = episode.airDate?.let { if (unaired) "Airs ${formatAirDate(it)}" else it }
                    val meta = listOfNotNull(airLabel, episode.runtime?.let { "$it min" }).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(
                            meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (unaired) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
            PersonCreditsSection("Episode crew", episode.crew, onBrowsePerson)
            EpisodeHistoryPanel(episode, onSaveEpisodeLog, onDeleteEpisodeWatch)
            TextButton(
                onClick = {
                    castExpanded = !castExpanded
                    if (castExpanded) onShowCast()
                },
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(if (castExpanded) "Hide cast" else "Cast")
                Icon(
                    if (castExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 2.dp).size(18.dp),
                )
            }
            if (castExpanded) {
                EpisodeCastSection(cast, onBrowsePerson, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** The expanded cast of one episode: series regulars, then guest stars, each a horizontal row
 *  of headshots — the web app's `EpisodeCastSection`. A null [cast] means it's still loading. */
@Composable
private fun EpisodeCastSection(cast: EpisodeCast?, onBrowsePerson: ((LibraryPerson) -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            cast == null -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.padding(start = 12.dp).size(20.dp),
            )
            cast.isEmpty -> Text(
                "No cast listed for this episode.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            else -> {
                if (cast.cast.isNotEmpty()) EpisodeCastRow("Episode cast", cast.cast, onBrowsePerson)
                if (cast.guestStars.isNotEmpty()) EpisodeCastRow("Guest stars", cast.guestStars, onBrowsePerson)
            }
        }
    }
}

@Composable
private fun EpisodeCastRow(label: String, members: List<EpisodeCastMember>, onBrowsePerson: ((LibraryPerson) -> Unit)?) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(members, key = EpisodeCastMember::tmdbPersonId) { member ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(72.dp).clickable(enabled = onBrowsePerson != null) { onBrowsePerson?.invoke(LibraryPerson(member.tmdbPersonId, member.name)) },
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(tintForKey(member.tmdbPersonId.toString())),
                    ) {
                        Text(
                            member.name.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                        member.profileUrl?.let { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = member.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Text(
                        member.name,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    member.characterName?.let { character ->
                        Text(
                            character,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewingRow(viewing: Viewing, onRateClick: () -> Unit, onEditClick: () -> Unit, onDeleteClick: () -> Unit, modifier: Modifier = Modifier) {
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
        viewing.rating?.let { Text("$it / 5", style = MaterialTheme.typography.bodyMedium) }
        if (stub.isNotBlank()) {
            Text(stub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (viewing.outingId != null && viewing.rating == null) {
            TextButton(onClick = onRateClick, modifier = Modifier.padding(top = 2.dp)) { Text("How was it?") }
        }
        Row {
            TextButton(onClick = onEditClick) { Text("Edit viewing") }
            TextButton(onClick = onDeleteClick) { Text("Delete viewing", color = MaterialTheme.colorScheme.error) }
        }
    }
}

private class TitleDetailViewModelFactory(
    private val repository: LibraryRepository,
    private val outingsRepository: OutingsRepository,
    private val listsRepository: ListsRepository,
    private val titleId: String,
    private val catalogExtrasSource: work.kumarfamilynet.cinemarchive.data.CatalogExtrasSource?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TitleDetailViewModel(repository, outingsRepository, listsRepository, titleId, catalogExtrasSource) as T
}
