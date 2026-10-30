package work.kumarfamilynet.cinemarchive.feature.library

import work.kumarfamilynet.cinemarchive.data.rethrowIfCancelled

import work.kumarfamilynet.cinemarchive.data.userMessage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
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
import work.kumarfamilynet.cinemarchive.core.designsystem.savePostShow
import work.kumarfamilynet.cinemarchive.core.designsystem.restorePostShow
import work.kumarfamilynet.cinemarchive.core.model.PostShowOpening
import kotlinx.coroutines.isActive
import work.kumarfamilynet.cinemarchive.core.designsystem.LocalSpoilerProtection
import work.kumarfamilynet.cinemarchive.core.designsystem.PosterSurface
import work.kumarfamilynet.cinemarchive.core.designsystem.ReadingWidthColumn
import work.kumarfamilynet.cinemarchive.core.designsystem.SegmentedGroup
import work.kumarfamilynet.cinemarchive.core.designsystem.tintForKey
import work.kumarfamilynet.cinemarchive.core.model.CinemaFormat
import work.kumarfamilynet.cinemarchive.core.model.CinemaOuting
import work.kumarfamilynet.cinemarchive.core.model.CinemaOutingRules
import work.kumarfamilynet.cinemarchive.core.designsystem.CatalogExtrasSection
import work.kumarfamilynet.cinemarchive.core.model.CatalogExtrasState
import work.kumarfamilynet.cinemarchive.data.CatalogExtrasController
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
import work.kumarfamilynet.cinemarchive.core.model.isEpisodeSpoilerHidden
import work.kumarfamilynet.cinemarchive.core.model.newcomersFirst
import work.kumarfamilynet.cinemarchive.core.model.Viewing
import work.kumarfamilynet.cinemarchive.core.model.ViewingDraft
import work.kumarfamilynet.cinemarchive.core.model.isUnaired
import work.kumarfamilynet.cinemarchive.core.model.mainSeasons
import work.kumarfamilynet.cinemarchive.core.model.nextScheduledEpisode
import work.kumarfamilynet.cinemarchive.core.model.orderedForDisplay
import work.kumarfamilynet.cinemarchive.core.model.seasonLabel
import work.kumarfamilynet.cinemarchive.core.model.seasonShortLabel
import work.kumarfamilynet.cinemarchive.data.LibraryRepository
import work.kumarfamilynet.cinemarchive.data.ListsRepository
import work.kumarfamilynet.cinemarchive.data.OutingsRepository
import coil.compose.AsyncImage
import work.kumarfamilynet.cinemarchive.core.designsystem.ThemeTraitsDefaults
import work.kumarfamilynet.cinemarchive.core.designsystem.animatedListItem
import work.kumarfamilynet.cinemarchive.core.designsystem.horizontalEdgeFade
import androidx.compose.foundation.lazy.rememberLazyListState

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
            catch (cause: Exception) { cause.rethrowIfCancelled(); _titleEditError.value = "Couldn't save this title change. Try again." }
        }
    }
    suspend fun saveTags(tags: List<String>) = repository.updateTitleTags(titleId, tags)
    suspend fun prepareSources() = repository.prepareTitleSources(titleId)
    suspend fun saveSources(opening: String, values: work.kumarfamilynet.cinemarchive.data.TitleSourcesValues) =
        repository.saveTitleSources(titleId, opening, values)
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

    suspend fun prepareSchedule(outingId: String?): String = outingsRepository.prepareOutingSchedule(titleId, outingId)

    suspend fun saveSchedule(
        opening: String, showtime: Instant, previewsMinutes: Int, runtimeMinutes: Int,
        venue: String?, companions: List<String>, format: CinemaFormat?, ticketPrice: Double?,
        seating: SeatAssignment, bookingRef: String?, notes: String?,
    ) { outingsRepository.saveOutingSchedule(opening, showtime, previewsMinutes, runtimeMinutes,
        venue, companions, format, ticketPrice, seating, bookingRef, notes) }

    fun onCancelOuting(outingId: String) {
        viewModelScope.launch { outingsRepository.cancelOuting(outingId) }
    }


    suspend fun preparePostShow(viewingId: String): PostShowOpening = repository.preparePostShow(titleId, viewingId)

    suspend fun savePostShow(opening: PostShowOpening, rating: Double?, notes: String) = repository.savePostShow(opening, rating, notes)

    suspend fun revertPostShow(opening: PostShowOpening) = outingsRepository.revertPostShow(opening)

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
    /** Resolves the group-reactions actions for one of the user's outings, or null when never shared (#207). */
    groupReactions: (suspend (String) -> work.kumarfamilynet.cinemarchive.core.model.GroupReactionActions?)? = null,
    /** Opens an outing's keepsake photos (#208); null hides the entry point. */
    onOpenKeepsakes: ((CinemaOuting, String) -> Unit)? = null,
    onRequestNotificationPermission: () -> Unit = {},
    socialContent: (@Composable (TitleDetail) -> Unit)? = null,
    onRecommendTitle: ((String) -> Unit)? = null,
    onShareOutingPlans: ((String) -> Unit)? = null,
    onBrowsePerson: ((LibraryPerson) -> Unit)? = null,
    onRefreshCredits: (suspend () -> Boolean)? = null,
    catalogExtrasSource: work.kumarfamilynet.cinemarchive.data.CatalogExtrasSource? = null,
    titleMetadataRecovery: work.kumarfamilynet.cinemarchive.data.TitleMetadataRecoverySource? = null,
    moviegoingPrefs: work.kumarfamilynet.cinemarchive.data.MoviegoingPrefsRepository? = null,
    initialSchedule: Boolean = false,
    schedulePrefill: work.kumarfamilynet.cinemarchive.core.model.OutingSchedulePrefill? = null,
    initialPostShow: Boolean = false,
    onInitialPostShowConsumed: () -> Unit = {},
    onInitialScheduleConsumed: () -> Unit = {},
    onScheduled: () -> Unit = {},
    noirPins: work.kumarfamilynet.cinemarchive.data.TitlePinsRepository? = null,
    onNoirMode: (String?) -> Unit = {},
    // Called when this title earns a secret palette (Spider-Noir B&W, The Matrix red pill).
    onUnlockPalette: (work.kumarfamilynet.cinemarchive.core.model.ArchivePalette) -> Unit = {},
) {
    val ticketOutings by remember(outingsRepository, titleId) { outingsRepository.observeOutingsForTitle(titleId) }.collectAsStateWithLifecycle<List<CinemaOuting>?>(initialValue = null)
    val viewModel: TitleDetailViewModel =
        viewModel(key = titleId, factory = TitleDetailViewModelFactory(repository, outingsRepository, listsRepository, titleId, catalogExtrasSource))
    val detail by viewModel.uiState.collectAsStateWithLifecycle()
    var noirLoggedMode by rememberSaveable(titleId) { mutableStateOf<String?>(null) }
    val titleEditError by viewModel.titleEditError.collectAsStateWithLifecycle()
    val languageTag = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].toLanguageTag()
    val noirBwEarned = detail?.let { "bw" in work.kumarfamilynet.cinemarchive.core.model.noirProgress(it).earned } == true
    androidx.compose.runtime.LaunchedEffect(noirBwEarned) {
        if (noirBwEarned) onUnlockPalette(work.kumarfamilynet.cinemarchive.core.model.ArchivePalette.NOIR)
    }
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
    val outingTemplates by remember(moviegoingPrefs) { moviegoingPrefs?.observeTemplates() ?: kotlinx.coroutines.flow.flowOf(emptyList()) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
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
        onSaveEpisodeLog = { episodeId, draft ->
            viewModel.saveEpisodeLog(episodeId, draft)
            if (detail?.tmdbId == work.kumarfamilynet.cinemarchive.core.model.SPIDER_NOIR_TMDB_ID && draft.colorMode != null) {
                noirLoggedMode = "${draft.watchEventId}|${draft.colorMode}"
                // The lazy header may be offscreen; the owner/title-fenced root callback updates immediately.
                onNoirMode(draft.colorMode)
            }
        },
        noirContent = { current ->
            noirPins?.let { source -> NoirDetailControls(current, source, noirLoggedMode, onNoirMode) }
            MatrixPillControls(current) { onUnlockPalette(work.kumarfamilynet.cinemarchive.core.model.ArchivePalette.MATRIX) }
        },
        onDeleteEpisodeWatch = viewModel::deleteEpisodeWatch,
        onSaveViewing = viewModel::saveViewing,
        onDeleteViewing = viewModel::deleteViewing,
        onPrepareViewing = viewModel::prepareViewing,
        viewingOwnerId = repository.viewingOwnerId,
        viewingTitleId = titleId,
        onChangeStatus = viewModel::onChangeStatus,
        onToggleTheaterInterest = viewModel::onToggleTheaterInterest,
        onRateTitle = viewModel::onRateTitle,
        onPrepareSchedule = viewModel::prepareSchedule,
        onSaveSchedule = { opening, time, previews, runtime, venue, companions, format, price, seating, booking, notes ->
            viewModel.saveSchedule(opening, time, previews, runtime, venue, companions, format, price, seating, booking, notes)
            onScheduled()
        },
        initialSchedule = initialSchedule,
        schedulePrefill = schedulePrefill,
        initialPostShow = initialPostShow,
        onInitialPostShowConsumed = onInitialPostShowConsumed,
        ticketOutingsLoaded = ticketOutings != null,
        onInitialScheduleConsumed = onInitialScheduleConsumed,
        onCancelOuting = viewModel::onCancelOuting,
        onViewTicket = onViewTicket,
        groupReactions = groupReactions,
        onOpenKeepsakes = onOpenKeepsakes,
        ticketOutings = ticketOutings.orEmpty(),
        onPreparePostShow = viewModel::preparePostShow,
        onSavePostShow = viewModel::savePostShow,
        onRevertPostShow = viewModel::revertPostShow,
        onRequestNotificationPermission = onRequestNotificationPermission,
        venueSuggestions = venueSuggestions,
        companionSuggestions = companionSuggestions,
        venueNotes = venueNotes,
        onEditVenueNote = { editVenue = it },
        outingTemplates = outingTemplates,
        onSaveTemplate = moviegoingPrefs?.let { prefs -> { template -> prefs.saveTemplate(template) } },
        onDeleteTemplate = moviegoingPrefs?.let { prefs -> { id -> prefs.deleteTemplate(id) } },
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
        titleSourcesContent = { TitleSourcesEditor(titleId, viewModel::prepareSources, viewModel::saveSources) },
        titleEditError = titleEditError,
        titleMetadataRecovery = titleMetadataRecovery,
        episodeBulkContent = repository.episodeBulkRepository?.let { source -> { current, season ->
            androidx.compose.runtime.key(repository.viewingOwnerId, current.id) {
                val pending by remember(source, current.id) { source.pending(current.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
                PrePlatformWatchControl(current, season, checkNotNull(repository.viewingOwnerId), pending,
                    prepare = { source.prepare(current.id, it) }, save = source::save, retry = source::retry,
                    compare = source::compare, applyReviewed = source::applyReviewed, discard = source::discard)
            }
        } },
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
    onPrepareSchedule: (suspend (String?) -> String)? = null,
    onSaveSchedule: suspend (String, Instant, Int, Int, String?, List<String>, CinemaFormat?, Double?, SeatAssignment, String?, String?) -> Unit = { _, _, _, _, _, _, _, _, _, _, _ -> error("Ticket scheduling unavailable") },
    onCancelOuting: (String) -> Unit = {},
    onViewTicket: ((CinemaOuting, String) -> Unit)? = null,
    groupReactions: (suspend (String) -> work.kumarfamilynet.cinemarchive.core.model.GroupReactionActions?)? = null,
    onOpenKeepsakes: ((CinemaOuting, String) -> Unit)? = null,
    ticketOutings: List<CinemaOuting> = emptyList(),
    onPreparePostShow: (suspend (String) -> PostShowOpening)? = null,
    onSavePostShow: suspend (PostShowOpening, Double?, String) -> Unit = { _, _, _ -> error("Post-show saving unavailable") },
    onRevertPostShow: suspend (PostShowOpening) -> Unit = { error("Post-show reversal unavailable") },
    onRequestNotificationPermission: () -> Unit = {},
    venueSuggestions: List<String> = emptyList(),
    companionSuggestions: List<String> = emptyList(),
    venueNotes: Map<String, String> = emptyMap(),
    onEditVenueNote: ((String) -> Unit)? = null,
    outingTemplates: List<work.kumarfamilynet.cinemarchive.core.model.OutingTemplate> = emptyList(),
    onSaveTemplate: (suspend (work.kumarfamilynet.cinemarchive.core.model.OutingTemplate) -> Unit)? = null,
    onDeleteTemplate: (suspend (String) -> Unit)? = null,
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
    titleSourcesContent: (@Composable () -> Unit)? = null,
    titleEditError: String? = null,
    titleMetadataRecovery: work.kumarfamilynet.cinemarchive.data.TitleMetadataRecoverySource? = null,
    onPrepareViewing: (suspend (String?) -> ViewingDraft)? = null,
    viewingOwnerId: String? = null,
    viewingTitleId: String? = detail?.id,
    episodeBulkContent: (@Composable (TitleDetail, Int) -> Unit)? = null,
    noirContent: (@Composable (TitleDetail) -> Unit)? = null,
    initialSchedule: Boolean = false,
    schedulePrefill: work.kumarfamilynet.cinemarchive.core.model.OutingSchedulePrefill? = null,
    initialPostShow: Boolean = false,
    onInitialPostShowConsumed: () -> Unit = {},
    ticketOutingsLoaded: Boolean = true,
    onInitialScheduleConsumed: () -> Unit = {},
) {
    var scheduleOpening by rememberSaveable(viewingOwnerId, viewingTitleId) { mutableStateOf<String?>(null) }
    var preparingSchedule by remember { mutableStateOf(false) }
    // The "Opening…" dialogs can be cancelled; the preparation job is cancelled with them.
    var scheduleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var postShowJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var scheduleError by remember { mutableStateOf<String?>(null) }
    var scheduleTarget by remember { mutableStateOf<String?>(null) }
    var schedulePrefillForOpening by remember { mutableStateOf(schedulePrefill) }
    val scheduleOriginal = scheduleOpening?.let { runCatching {
        work.kumarfamilynet.cinemarchive.data.capturedScheduleInitial(it, viewingOwnerId, viewingTitleId)
    } }
    var postShowState by rememberSaveable(viewingOwnerId, viewingTitleId) { mutableStateOf<String?>(null) }
    val postShow = restorePostShow(postShowState, viewingOwnerId, viewingTitleId)
    var preparingPostShow by remember { mutableStateOf(false) }
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
    fun openSchedule(outingId: String?, prefill: work.kumarfamilynet.cinemarchive.core.model.OutingSchedulePrefill? = null) {
        if (preparingSchedule) return
        val openingOwner = viewingOwnerId
        val openingTitle = viewingTitleId
        scheduleTarget = outingId
        schedulePrefillForOpening = prefill
        preparingSchedule = true
        scheduleError = null
        scheduleJob = historyScope.launch {
            try {
                val opening = checkNotNull(onPrepareSchedule) { "Ticket scheduling is unavailable." }(outingId)
                if (currentHistoryOwner == openingOwner && currentHistoryTitle == openingTitle) scheduleOpening = opening
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { failure.rethrowIfCancelled();
                if (currentHistoryOwner == openingOwner && currentHistoryTitle == openingTitle)
                    scheduleError = failure.userMessage("Please try again.")
            } finally { preparingSchedule = false }
        }
    }
    androidx.compose.runtime.LaunchedEffect(initialSchedule, detail?.id, viewingOwnerId) {
        if (initialSchedule && detail?.type == MediaType.MOVIE) {
            onInitialScheduleConsumed()
            if (scheduleOpening == null) openSchedule(null, schedulePrefill)
        }
    }
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
            catch (e: Exception) { e.rethrowIfCancelled(); if (currentHistoryOwner == openingOwner) viewingOpenError = e.userMessage("Couldn't open this viewing.") }
            finally { preparingViewing = false }
        }
    }
    fun openPostShow(viewing: Viewing) {
        val current = detail ?: return
        if (preparingPostShow) return
        val owner = viewingOwnerId
        preparingPostShow = true
        viewingOpenError = null
        postShowJob = historyScope.launch {
            try {
                val opening = checkNotNull(onPreparePostShow) { "Post-show editing is unavailable." }(viewing.id)
                if (!isActive) return@launch
                if (currentHistoryOwner == owner && currentHistoryTitle == current.id) {
                    postShowState = savePostShow(owner, current.id, current.title, opening)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (cause: Exception) { cause.rethrowIfCancelled();
                if (currentHistoryOwner == owner && currentHistoryTitle == current.id) viewingOpenError = "Couldn't open this viewing. Try again."
            } finally { preparingPostShow = false }
        }
    }
    androidx.compose.runtime.LaunchedEffect(initialPostShow, detail, ticketOutings, ticketOutingsLoaded) {
        val current = detail ?: return@LaunchedEffect
        if (initialPostShow && ticketOutingsLoaded) {
            onInitialPostShowConsumed()
            val pending = work.kumarfamilynet.cinemarchive.core.model.CinemaOutingRules.pendingFollowUp(
                ticketOutings, current.viewings.associateBy { it.id }, Instant.now(),
            ).maxByOrNull { Instant.parse(it.endsAt) }
            current.viewings.firstOrNull { it.id == pending?.completedViewingId }?.let(::openPostShow)
        }
    }
    var showRemoveConfirm by rememberSaveable { mutableStateOf(false) }
    // Keyed on the title id (not just rememberSaveable) so navigating from one series' detail
    // screen straight to another's doesn't carry over a season number that may not exist there.
    var selectedSeasonNumber by rememberSaveable(detail?.id) {
        mutableStateOf(detail?.seasons?.orderedForDisplay()?.firstOrNull()?.seasonNumber ?: 1)
    }

    if (scheduleOpening != null) {
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

                    noirContent?.invoke(detail)

                    if (onViewTicket != null) ticketOutings.forEach { outing ->
                        TextButton(onClick = { onViewTicket(outing, detail.title) }) {
                            Icon(Icons.Filled.ConfirmationNumber, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                "View ticket · ${outing.venue ?: "Cinema"} · ${formatShowtimeDate(outing.showtime)}",
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }

                    if (onOpenKeepsakes != null) ticketOutings.forEach { outing ->
                        TextButton(onClick = { onOpenKeepsakes(outing, detail.title) }) {
                            Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                "Photos · ${outing.venue ?: "Cinema"} · ${formatShowtimeDate(outing.showtime)}",
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }

                    detail.scheduledOuting?.let { outing ->
                        ScheduledOutingBanner(
                            outing = outing,
                            onShare = onShareOutingPlans?.let { share -> { share(outing.id) } },
                            onEdit = { openSchedule(outing.id) },
                            onCancel = { onCancelOuting(outing.id) },
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }

                    if (detail.type == MediaType.MOVIE && detail.scheduledOuting == null) {
                        TextButton(onClick = { openSchedule(null) }, enabled = !preparingSchedule, modifier = Modifier.padding(bottom = 4.dp)) {
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
                    CatalogDetailsSection(detail)
                }
            }
            if (titleEditError != null || titleMetadataRecovery != null) {
                item(key = "title-metadata-edits") {
                    ReadingWidthColumn(Modifier.padding(horizontal = 22.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

            if (detail.cast.isNotEmpty() || detail.crew.isNotEmpty()) {
                item(key = "title-credits") {
                    ReadingWidthColumn(modifier = Modifier.padding(horizontal = 22.dp)) {
                        PersonCreditsSection("Cast", detail.cast, onBrowsePerson)
                        PersonCreditsSection("Crew", detail.crew, onBrowsePerson)
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
                // Collapsed by default: it mostly repeats the series cast above, so the people new
                // to this season lead when it opens.
                if (selectedSeason.cast.isNotEmpty()) item(key = "season-credits") {
                    var seasonCastExpanded by rememberSaveable { mutableStateOf(false) }
                    ReadingWidthColumn(modifier = Modifier.padding(horizontal = 18.dp)) {
                        CollapsibleCastToggle(
                            label = "${seasonLabel(selectedSeason.seasonNumber)} cast · ${selectedSeason.cast.distinctBy { it.tmdbPersonId }.size}",
                            expanded = seasonCastExpanded,
                            onToggle = { seasonCastExpanded = !seasonCastExpanded },
                        )
                        if (seasonCastExpanded) {
                            val ordered = remember(selectedSeason.cast, detail.cast) { selectedSeason.cast.newcomersFirst(detail.cast) }
                            Box(Modifier.padding(horizontal = 4.dp)) {
                                PersonCreditsSection("Season cast", ordered, onBrowsePerson)
                            }
                        }
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
                            isSpiderNoir = detail.tmdbId == 220102,
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
                ReadingWidthColumn(Modifier.animatedListItem(this)) {
                    ViewingRow(
                        viewing,
                        onRateClick = { openPostShow(viewing) },
                        onEditClick = { if (!preparingViewing) openViewing(viewing) },
                        onDeleteClick = { if (!preparingViewing) openViewing(viewing, deleting = true) },
                        modifier = Modifier.padding(horizontal = 22.dp),
                    )
                }
            }
            if (socialContent != null) {
                item(key = "social") { ReadingWidthColumn { socialContent(detail) } }
            }
            item(key = "manage-title") {
                // Maintenance and rarely-used edits live behind one collapsed card instead of
                // being scattered between the title's content sections.
                ReadingWidthColumn(modifier = Modifier.padding(22.dp, 16.dp, 22.dp, 0.dp)) {
                    ManageTitleSection(detail.id) {
                        titleSourcesContent?.invoke()
                        onSaveTags?.let { TitleTagsEditor(detail.id, detail.tags, it) }
                        onRefreshCredits?.let { CreditRefreshControl(detail.id, it) }
                        if (detail.seasons.isNotEmpty()) episodeBulkContent?.let { content ->
                            val season = detail.seasons.firstOrNull { it.seasonNumber == selectedSeasonNumber }
                                ?: detail.seasons.orderedForDisplay().first()
                            content(detail, season.seasonNumber)
                        }
                        TextButton(onClick = { showRemoveConfirm = true }) {
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
            }
            item { Box(modifier = Modifier.height(28.dp)) }
        }
    }

    if (preparingSchedule) AlertDialog(onDismissRequest = { scheduleJob?.cancel() }, title = { Text("Opening tickets…") },
        text = { CircularProgressIndicator() }, confirmButton = { TextButton(onClick = { scheduleJob?.cancel() }) { Text("Cancel") } })
    if (scheduleError != null || scheduleOriginal?.isFailure == true) AlertDialog(
        onDismissRequest = { scheduleError = null; if (scheduleOriginal?.isFailure == true) scheduleOpening = null },
        title = { Text("Couldn't open tickets") },
        text = { Text(scheduleError ?: "This saved form belongs to another session. Close it and reopen the outing.") },
        confirmButton = { if (scheduleError != null) TextButton(onClick = { openSchedule(scheduleTarget) }) { Text("Retry") } },
        dismissButton = { TextButton(onClick = { scheduleError = null; if (scheduleOriginal?.isFailure == true) scheduleOpening = null }) { Text("Close") } },
    )
    if (scheduleOpening != null && scheduleOriginal?.isSuccess == true) {
        val opening = checkNotNull(scheduleOpening)
        val openingOwner = viewingOwnerId
        val openingTitle = viewingTitleId
        OutingScheduleSheet(
            defaultRuntimeMinutes = detail?.runtime,
            initial = scheduleOriginal.getOrNull(),
            prefill = schedulePrefillForOpening,
            onDismiss = { if (currentHistoryOwner == openingOwner && currentHistoryTitle == openingTitle) scheduleOpening = null },
            onSave = { showtime, previews, runtime, venue, companions, format, price, seating, bookingRef, notes ->
                onSaveSchedule(opening, showtime, previews, runtime, venue, companions, format, price, seating, bookingRef, notes)
            },
            venueSuggestions = venueSuggestions,
            companionSuggestions = companionSuggestions,
            venueNotes = venueNotes,
            onEditVenueNote = onEditVenueNote,
            templates = outingTemplates,
            onSaveTemplate = onSaveTemplate,
            onDeleteTemplate = onDeleteTemplate,
            onManageTicket = onViewTicket?.let { open -> { outing ->
                scheduleOpening = null
                open(outing, detail?.title ?: "Cinema outing")
            } },
        )
    }

    postShow?.let { captured ->
        androidx.compose.runtime.key(viewingOwnerId, captured.titleId, captured.opening.viewing.id) {
            PostShowSheet(
                titleName = captured.titleName,
                opening = captured.opening,
                onSave = onSavePostShow,
                onRevert = onRevertPostShow,
                onDismiss = { postShowState = null },
                onRecommend = if (detail?.tmdbId != null && onRecommendTitle != null) ({ onRecommendTitle(captured.titleId) }) else null,
                groupContent = groupReactions?.let { resolve ->
                    captured.followUpOutingId?.let { outingId ->
                        { rating, note ->
                            work.kumarfamilynet.cinemarchive.core.designsystem.ResolvedGroupReactions(
                                resolve = { resolve(outingId) }, initialRating = rating, initialNote = note,
                            )
                        }
                    }
                },
            )
        }
    }
    if (preparingPostShow) AlertDialog(onDismissRequest = { postShowJob?.cancel() }, title = { Text("Opening viewing…") },
        text = { CircularProgressIndicator() }, confirmButton = { TextButton(onClick = { postShowJob?.cancel() }) { Text("Cancel") } })

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
                        } catch (e: Exception) { e.rethrowIfCancelled();
                            deleteError = e.userMessage("Couldn't delete viewing. Try again.")
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

/** Collapsed-by-default card for the title's maintenance actions: watch link and physical
 *  collection, tags, metadata refresh, earlier-watch backfill and removal. */
@Composable
private fun ManageTitleSection(titleId: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(titleId) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = if (expanded) "Collapse" else "Expand") { expanded = !expanded }
                    .padding(16.dp),
            ) {
                Icon(Icons.Filled.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Manage title", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tags, watch link, collection, metadata & removal",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            if (expanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun DetailHero(detail: TitleDetail, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        Box(modifier = Modifier.fillMaxSize().background(ThemeTraitsDefaults.decorativeTint(tintForKey(detail.id))))
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
    val spoilerHidden = isEpisodeSpoilerHidden(episode.watchCount > 0, LocalSpoilerProtection.current)
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
                        (episode.episodeName?.takeUnless { spoilerHidden }?.let { " · $it" } ?: ""),
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

/** A showtime is a full instant, so it is shown in the device's zone (where the theater is). */
private fun formatShowtimeDate(iso: String): String =
    runCatching {
        Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
    }.getOrDefault(iso.take(10))

/** Horizontally scrollable season tabs — the same underlying "pick one of N" interaction as the
 *  web app's season pills/dropdown (`TVSeriesSection`), but a single scrollable-chip idiom
 *  regardless of season count instead of switching UI shape past three seasons. */
@Composable
private fun SeasonSelector(
    seasons: List<SeasonDetail>,
    selectedSeasonNumber: Int,
    onSelect: (Int) -> Unit,
) {
    val rowState = rememberLazyListState()
    LazyRow(
        state = rowState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 22.dp),
        modifier = Modifier.horizontalEdgeFade(rowState),
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
    isSpiderNoir: Boolean,
    onShowCast: () -> Unit,
    onBrowsePerson: ((LibraryPerson) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val watched = episode.watchCount > 0
    var castExpanded by rememberSaveable(episode.id) { mutableStateOf(false) }
    var synopsisExpanded by rememberSaveable(episode.id) { mutableStateOf(false) }
    // A one-off reveal for this episode, like the web panel's Reveal.
    var revealed by rememberSaveable(episode.id) { mutableStateOf(false) }
    val spoilerHidden = !revealed && isEpisodeSpoilerHidden(watched, LocalSpoilerProtection.current)
    val hasSpoilers = episode.episodeName != null || episode.stillUrl != null || !episode.synopsis.isNullOrBlank()

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PosterSurface(
                    tint = tintForKey(episode.id),
                    imageUrl = episode.stillUrl.takeUnless { spoilerHidden },
                    modifier = Modifier.width(128.dp),
                    aspectRatio = 16f / 9f,
                    cornerRadius = 10.dp,
                ) {
                    if (spoilerHidden) {
                        Icon(
                            Icons.Filled.VisibilityOff,
                            contentDescription = "Hidden to avoid spoilers",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.align(Alignment.Center).size(20.dp),
                        )
                    }
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
                        "E${episode.episodeNumber}" + (episode.episodeName?.takeUnless { spoilerHidden }?.let { " · $it" } ?: ""),
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
            if (spoilerHidden && hasSpoilers) {
                TextButton(
                    onClick = { revealed = true },
                    contentPadding = PaddingValues(horizontal = 0.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        "Hidden to avoid spoilers · Reveal",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            episode.synopsis?.takeIf { it.isNotBlank() && !spoilerHidden }?.let { synopsis ->
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
            EpisodeHistoryPanel(episode, onSaveEpisodeLog, onDeleteEpisodeWatch, isSpiderNoir) {
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
            }
            if (castExpanded) {
                EpisodeCastSection(cast, onBrowsePerson, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** The expanded cast of one episode: guest stars, then the series regulars behind a toggle, each a
 *  horizontal row of headshots — the web app's `EpisodeCastSection`. A null [cast] means it's
 *  still loading. */
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
                // Guest stars are what's new about this episode, so they lead; the regulars mostly
                // repeat the series and season cast and sit behind their own toggle.
                if (cast.guestStars.isNotEmpty()) EpisodeCastRow("Guest stars", cast.guestStars, onBrowsePerson)
                if (cast.cast.isNotEmpty()) {
                    var regularsExpanded by rememberSaveable { mutableStateOf(false) }
                    CollapsibleCastToggle(
                        label = "${cast.cast.size} series regular${if (cast.cast.size == 1) "" else "s"} in this episode",
                        expanded = regularsExpanded,
                        onToggle = { regularsExpanded = !regularsExpanded },
                    )
                    if (regularsExpanded) EpisodeCastRow("Series regulars", cast.cast, onBrowsePerson)
                }
            }
        }
    }
}

/** A compact text toggle that shows or hides a cast list kept out of view by default. */
@Composable
private fun CollapsibleCastToggle(label: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 4.dp), modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            modifier = Modifier.padding(start = 2.dp).size(18.dp),
        )
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
        val rowState = rememberLazyListState()
        LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.horizontalEdgeFade(rowState)) {
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
                            .background(ThemeTraitsDefaults.decorativeTint(tintForKey(member.tmdbPersonId.toString()))),
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
