package work.kumarfamilynet.cinemarchive.feature.ledger

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.BarChartCanvas
import work.kumarfamilynet.cinemarchive.core.designsystem.ChartDatum
import work.kumarfamilynet.cinemarchive.core.designsystem.DailyHeatmapGrid
import work.kumarfamilynet.cinemarchive.core.designsystem.DmMonoFamily
import work.kumarfamilynet.cinemarchive.core.designsystem.HeatmapRow
import work.kumarfamilynet.cinemarchive.core.designsystem.LineChartCanvas
import work.kumarfamilynet.cinemarchive.core.model.LedgerBoard
import work.kumarfamilynet.cinemarchive.core.model.LedgerCategoryCount
import work.kumarfamilynet.cinemarchive.core.model.LedgerEncoreEntry
import work.kumarfamilynet.cinemarchive.core.model.LedgerLayoutRules
import work.kumarfamilynet.cinemarchive.core.model.LedgerMoviegoingStats
import work.kumarfamilynet.cinemarchive.core.model.LedgerPremiereRevivalBucket
import work.kumarfamilynet.cinemarchive.core.model.LedgerProgressEntry
import work.kumarfamilynet.cinemarchive.core.model.LedgerQuarterRating
import work.kumarfamilynet.cinemarchive.core.model.LedgerSettingKey
import work.kumarfamilynet.cinemarchive.core.model.LedgerStats
import work.kumarfamilynet.cinemarchive.core.model.LedgerStreaks
import work.kumarfamilynet.cinemarchive.core.model.LedgerVerdictEntry
import work.kumarfamilynet.cinemarchive.core.model.LedgerWatchlistEntry
import work.kumarfamilynet.cinemarchive.core.model.LedgerWidgetConfig
import work.kumarfamilynet.cinemarchive.core.model.LedgerWidgetId
import work.kumarfamilynet.cinemarchive.core.model.LedgerWidgetSettings
import work.kumarfamilynet.cinemarchive.core.model.LedgerWidgetWidth
import work.kumarfamilynet.cinemarchive.core.model.effectiveLedgerSettings
import work.kumarfamilynet.cinemarchive.core.model.honorsLedgerSetting
import work.kumarfamilynet.cinemarchive.data.LedgerLayoutRepository
import work.kumarfamilynet.cinemarchive.data.LedgerRepository

private val PANEL_LABELS: Map<LedgerWidgetId, String> = mapOf(
    LedgerWidgetId.RUNTIMES to "Feature Lengths",
    LedgerWidgetId.NETWORKS to "On the Air",
    LedgerWidgetId.DECADES to "By the Era",
    LedgerWidgetId.ATTRACTIONS to "Coming Attractions",
    LedgerWidgetId.ACTIVITY to "Time in the Dark",
    LedgerWidgetId.ENCORES to "Encore Performances",
    LedgerWidgetId.RUN to "The Run",
    LedgerWidgetId.RATINGS to "Critical Record",
    LedgerWidgetId.GENRES to "By the Genre",
    LedgerWidgetId.AUTEURS to "The Auteurs",
    LedgerWidgetId.ENSEMBLE to "The Ensemble",
    LedgerWidgetId.VERDICTS to "Second Opinions",
    LedgerWidgetId.LANGUAGES to "In Translation",
    LedgerWidgetId.WEEKDAYS to "Screening Nights",
    LedgerWidgetId.STREAKS to "The Marathon",
    LedgerWidgetId.TRAJECTORY to "Shifting Standards",
    LedgerWidgetId.REVIVALS to "Premieres & Revivals",
    LedgerWidgetId.TIMEWARP to "The Revival House",
    LedgerWidgetId.PROGRESS to "Still Rolling",
    LedgerWidgetId.MOVIEGOING to "At the Movies",
)

/**
 * All 20 Ledger widgets (docs/android-contracts/ledger.md §2), rendered from a
 * [LedgerWidgetConfig] list — the fixed default order on first launch, or whatever the user
 * has locally customized (edit mode: add/remove/move/resize/settings). Every chart primitive
 * ([BarChartCanvas]/[HeatmapRow]) is decorative and paired with a real, focusable list of the
 * same data — per ledger.md §5, Android must give every widget a genuine accessible
 * alternative rather than the web app's tooltip-only fallback on five widgets.
 *
 * The layout persists **locally only** (DataStore via [LedgerLayoutRepository]) — syncing it
 * to `user_prefs.ledger_layout` needs a real, authenticated `RemoteMutationWriter` (see
 * docs/android-implementation-status.md for what that needs). Every widget renders as a
 * fixed-400dp card (ledger.md §1) with internally scrolling content; at a `lg`+ window width
 * (>= 840dp, [LG_BREAKPOINT]) cards pack into a 12-column grid by `width` span
 * (sm/md/lg/full = 4/6/8/12, see [spanOf12]); below that, every card is full-width regardless
 * of its stored width, per ledger.md §1's "always full below lg". `topN`/`title` are applied
 * to a widget's rendered output as a post-hoc take(n)/header-override; `timeRange`/`scope` are
 * consumed further upstream, in [LedgerRepository.observeLedgerBoards]'s per-widget-instance
 * aggregation — each widget in [LedgerUiState.boards] is keyed by its own
 * [LedgerWidgetConfig.id] and already reflects that widget's own effective scope/time-range
 * filter (a panel that doesn't honor one of those knobs, per `PANEL_SETTING_KEYS`, always gets
 * the unfiltered "all" board regardless of what a synced widget's stored settings say).
 */
data class LedgerUiState(val stats: LedgerStats, val boards: Map<String, LedgerBoard>)

class LedgerViewModel(
    repository: LedgerRepository,
    private val layoutRepository: LedgerLayoutRepository,
) : ViewModel() {
    private val layoutFlow = layoutRepository.observeLayout()

    val uiState = combine(repository.observeLedgerStats(), repository.observeLedgerBoards(layoutFlow), ::LedgerUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val layout = layoutFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val editModeFlow = MutableStateFlow(false)
    val editMode: StateFlow<Boolean> = editModeFlow

    fun setEditMode(enabled: Boolean) {
        editModeFlow.value = enabled
    }

    fun updateLayout(widgets: List<LedgerWidgetConfig>) {
        viewModelScope.launch { layoutRepository.setLayout(widgets) }
    }
}

@Composable
fun LedgerRoute(repository: LedgerRepository, layoutRepository: LedgerLayoutRepository, onOpenProfile: () -> Unit) {
    val viewModel: LedgerViewModel = viewModel(factory = LedgerViewModelFactory(repository, layoutRepository))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val editMode by viewModel.editMode.collectAsStateWithLifecycle()
    LedgerScreen(
        uiState = uiState,
        layout = layout,
        editMode = editMode,
        onToggleEditMode = { viewModel.setEditMode(!editMode) },
        onLayoutChange = viewModel::updateLayout,
        onOpenProfile = onOpenProfile,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(
    uiState: LedgerUiState?,
    layout: List<LedgerWidgetConfig>?,
    editMode: Boolean = false,
    onToggleEditMode: () -> Unit = {},
    onLayoutChange: (List<LedgerWidgetConfig>) -> Unit = {},
    onOpenProfile: () -> Unit = {},
    // Android has no friend/shared viewer mode yet (see LedgerWidgets.kt's kdoc on the same
    // gap), so this is always null today — threaded through now so the eventual Friends/
    // Sharing work only needs to supply a real value, not rewire this call chain. See
    // docs/superpowers/plans/2026-07-23-android-ledger-parity.md §8.
    viewedDisplayName: String? = null,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(20.dp, 20.dp, 20.dp, 0.dp),
        ) {
            Text("THE NUMBERS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Surface(
                onClick = onOpenProfile,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
                    Text("C", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) {
            Text("The Ledger", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(vertical = 2.dp))
            TextButton(onClick = onToggleEditMode) {
                Icon(
                    if (editMode) Icons.Filled.Check else Icons.Filled.Edit,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(if (editMode) "Done" else "Edit")
            }
        }

        if (uiState == null || layout == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            return@Column
        }

        if (editMode) {
            LedgerEditModeContent(
                modifier = Modifier.fillMaxSize(),
                layout = layout,
                onLayoutChange = onLayoutChange,
            )
        } else {
            LedgerBoardContent(
                modifier = Modifier.fillMaxSize(),
                uiState = uiState,
                layout = layout,
                viewedDisplayName = viewedDisplayName,
            )
        }
    }
}

/** Material's "expanded" window-size-class threshold — the `lg`+ breakpoint ledger.md §1
 *  keys `width` off. Below this, every widget card is full-width regardless of its stored
 *  `width` ("always full below lg"). */
private val LG_BREAKPOINT = 840.dp
private const val CARD_HEIGHT_DP = 400

private fun LedgerWidgetWidth.spanOf12(): Int = when (this) {
    LedgerWidgetWidth.SM -> 4
    LedgerWidgetWidth.MD -> 6
    LedgerWidgetWidth.LG -> 8
    LedgerWidgetWidth.FULL -> 12
}

/** Greedily packs widgets into 12-column rows in board order, wrapping to a new row once a
 *  widget's span would overflow the current one. */
private fun packRows(layout: List<LedgerWidgetConfig>): List<List<LedgerWidgetConfig>> {
    val rows = mutableListOf<MutableList<LedgerWidgetConfig>>()
    var current = mutableListOf<LedgerWidgetConfig>()
    var span = 0
    for (widget in layout) {
        val widgetSpan = widget.width.spanOf12()
        if (span + widgetSpan > 12 && current.isNotEmpty()) {
            rows += current
            current = mutableListOf()
            span = 0
        }
        current += widget
        span += widgetSpan
    }
    if (current.isNotEmpty()) rows += current
    return rows
}

@Composable
private fun LedgerBoardContent(
    modifier: Modifier,
    uiState: LedgerUiState,
    layout: List<LedgerWidgetConfig>,
    viewedDisplayName: String? = null,
) {
    val (stats, boards) = uiState
    BoxWithConstraints(modifier = modifier) {
        val isGrid = maxWidth >= LG_BREAKPOINT
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeroCopy(stats, viewedDisplayName)
                    val totalScreeningDays = stats.totalWatchedMovieMinutes / 60.0 / 24.0
                    val tiles = listOf(
                        StatTileData("Movies", stats.totalMovies.toString(), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer),
                        StatTileData("Series", stats.totalSeries.toString(), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer),
                        StatTileData("Screenings", stats.totalViewings.toString(), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer),
                        StatTileData("Hours logged", (stats.totalWatchedMovieMinutes / 60).toString() + "h", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer),
                        StatTileData("Days in the dark", "%.1fd".format(totalScreeningDays), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer),
                        StatTileData("Avg rating", stats.averageRating?.let { "%.1f".format(it) } ?: "—", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.primary),
                    )
                    tiles.chunked(2).forEach { rowTiles ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            rowTiles.forEach { tile -> StatTile(tile, modifier = Modifier.weight(1f)) }
                        }
                    }
                }
            }

            if (isGrid) {
                items(packRows(layout), key = { row -> row.joinToString("-") { it.id } }) { row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { config -> WidgetCard(config, boards[config.id], Modifier.weight(config.width.spanOf12().toFloat())) }
                        val usedSpan = row.sumOf { it.width.spanOf12() }
                        if (usedSpan < 12) Spacer(Modifier.weight((12 - usedSpan).toFloat()))
                    }
                }
            } else {
                items(layout, key = { it.id }) { config -> WidgetCard(config, boards[config.id], Modifier.fillMaxWidth()) }
            }
        }
    }
}

/** Caps at the panel's effective `topN` (its own override, else `PANEL_SETTING_KEYS`'s
 *  per-panel default) — but only for panels that actually honor `topN`; every other panel's
 *  list (fixed-size buckets like rating/weekday buckets, or ones with no natural "top" cut)
 *  renders in full regardless of what a stored widget's settings say, matching ledger.md §1's
 *  "silently ignore an inapplicable key" rule. */
private fun <T> List<T>.applyTopN(config: LedgerWidgetConfig): List<T> {
    if (!config.panel.honorsLedgerSetting(LedgerSettingKey.TOP_N)) return this
    return take(effectiveLedgerSettings(config.panel, config.settings).topN)
}

private fun headerFor(config: LedgerWidgetConfig, default: String): String = config.settings?.title ?: default

/** One widget, as a fixed [CARD_HEIGHT_DP]dp card with internally scrolling content
 *  (ledger.md §1: "Every widget renders at a fixed 400px card height... content scrolls/
 *  compresses internally"). [modifier] carries either `fillMaxWidth()` (single column) or a
 *  `weight()` (grid column span) from the caller. [board] is null for exactly one
 *  recomposition frame right after a widget is added in edit mode — [LedgerUiState.boards] is
 *  keyed off the same layout list but recomputed one flow step behind it; render nothing that
 *  frame rather than crash, it self-heals on the next emission. */
@Composable
private fun WidgetCard(config: LedgerWidgetConfig, board: LedgerBoard?, modifier: Modifier) {
    if (board == null) return
    Card(
        modifier = modifier.height(CARD_HEIGHT_DP.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WidgetContent(config, board)
        }
    }
}

@Composable
private fun WidgetContent(config: LedgerWidgetConfig, board: LedgerBoard) {
    val title = headerFor(config, PANEL_LABELS[config.panel] ?: config.panel.raw)
    when (config.panel) {
        LedgerWidgetId.RUNTIMES -> CategorySection(title, board.runtimeBuckets.applyTopN(config))
        LedgerWidgetId.NETWORKS -> CategorySection(title, board.networks.applyTopN(config))
        LedgerWidgetId.DECADES -> CategorySection(title, board.decades.applyTopN(config))
        LedgerWidgetId.ATTRACTIONS -> {
            SectionHeader(
                if (board.watchlistMovieMinutesOwed > 0) "$title — ${board.watchlistMovieMinutesOwed} movie minutes owed" else title,
            )
            val entries = board.watchlist.applyTopN(config)
            if (entries.isEmpty()) EmptyRow("Nothing on the watchlist.")
            else entries.forEach { WatchlistRow(it) }
        }
        LedgerWidgetId.ACTIVITY -> {
            SectionHeader(title)
            if (board.weeklyActivity.any { it.count > 0 }) {
                DailyHeatmapGrid(values = board.dailyActivity)
                board.weeklyActivity.filter { it.count > 0 }.applyTopN(config).forEach {
                    CategoryRow(LedgerCategoryCount("Week of ${it.weekLabel}", it.count))
                }
            } else EmptyRow("No dated viewings logged yet.")
        }
        LedgerWidgetId.ENCORES -> {
            SectionHeader(title)
            val entries = board.encores.applyTopN(config)
            if (entries.isEmpty()) EmptyRow("No title has been watched more than once yet.")
            else entries.forEach { EncoreRow(it) }
        }
        LedgerWidgetId.RUN -> {
            SectionHeader(title)
            if (board.monthlyRun.any { it.count > 0 }) {
                BarChartCanvas(data = board.monthlyRun.map { ChartDatum(it.monthLabel, it.count.toFloat()) })
            }
            board.monthlyRun.applyTopN(config).forEach { CategoryRow(LedgerCategoryCount(it.monthLabel, it.count)) }
        }
        LedgerWidgetId.RATINGS -> CategorySection(title, board.ratingBuckets.applyTopN(config))
        LedgerWidgetId.GENRES -> CategorySection(title, board.genres.applyTopN(config))
        LedgerWidgetId.AUTEURS -> CategorySection(title, board.auteurs.applyTopN(config))
        LedgerWidgetId.ENSEMBLE -> CategorySection(title, board.ensemble.applyTopN(config))
        LedgerWidgetId.VERDICTS -> {
            SectionHeader(title)
            val entries = board.verdicts.applyTopN(config)
            if (entries.isEmpty()) EmptyRow("No title has both your rating and an IMDb rating yet.")
            else entries.forEach { VerdictRow(it) }
        }
        LedgerWidgetId.LANGUAGES -> CategorySection(title, board.languages.applyTopN(config))
        LedgerWidgetId.WEEKDAYS -> {
            SectionHeader(title)
            BarChartCanvas(data = board.weekdays.map { ChartDatum(it.weekday, it.count.toFloat()) })
            board.weekdays.forEach { CategoryRow(LedgerCategoryCount(it.weekday, it.count)) }
        }
        LedgerWidgetId.STREAKS -> {
            SectionHeader(title)
            if (board.streaks.last30Nights.any { it }) {
                HeatmapRow(values = board.streaks.last30Nights.map { if (it) 1 else 0 })
            }
            StreakSummary(board.streaks)
        }
        LedgerWidgetId.TRAJECTORY -> {
            SectionHeader(title)
            val entries = board.trajectory.applyTopN(config)
            if (entries.isEmpty()) EmptyRow("No rated, dated titles yet.")
            else {
                LineChartCanvas(data = entries.map { ChartDatum(it.quarterLabel, it.averageRating.toFloat()) })
                entries.forEach { QuarterRow(it) }
            }
        }
        LedgerWidgetId.REVIVALS -> {
            SectionHeader(title)
            val entries = board.revivals.applyTopN(config)
            if (entries.isEmpty()) EmptyRow("No dated viewings logged yet.")
            else {
                LineChartCanvas(data = entries.map { ChartDatum(it.monthLabel, (it.premieres + it.revivals).toFloat()) })
                entries.forEach { RevivalRow(it) }
            }
        }
        LedgerWidgetId.TIMEWARP -> CategorySection(title, board.timewarp.applyTopN(config))
        LedgerWidgetId.PROGRESS -> {
            SectionHeader(title)
            val entries = board.stillRolling.applyTopN(config)
            if (entries.isEmpty()) EmptyRow("Nothing in progress.")
            else entries.forEach { ProgressRow(it) }
        }
        LedgerWidgetId.MOVIEGOING -> {
            SectionHeader(title)
            MoviegoingSection(board.moviegoing)
        }
    }
}

@Composable
private fun CategorySection(title: String, entries: List<LedgerCategoryCount>) {
    if (entries.isEmpty()) return
    SectionHeader(title)
    entries.forEach { CategoryRow(it) }
}

/**
 * Add/remove/move/resize/settings for the local layout — every action updates state
 * synchronously (matching ledger.md §4's "instant UI feedback" rule for the *local* half of
 * that write path; only the debounced remote upsert is out of reach here).
 */
@Composable
private fun LedgerEditModeContent(
    modifier: Modifier,
    layout: List<LedgerWidgetConfig>,
    onLayoutChange: (List<LedgerWidgetConfig>) -> Unit,
) {
    val presentPanels = layout.map { it.panel }.toSet()
    val availablePanels = LedgerWidgetId.entries.filter { it !in presentPanels }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        item { SectionHeader("On the board") }
        items(layout, key = { it.id }) { config ->
            EditableWidgetRow(
                config = config,
                canMoveUp = layout.first() != config,
                canMoveDown = layout.last() != config,
                onMoveUp = { onLayoutChange(layout.moved(config, -1)) },
                onMoveDown = { onLayoutChange(layout.moved(config, 1)) },
                onRemove = { onLayoutChange(layout.filterNot { it.id == config.id }) },
                onCycleWidth = { onLayoutChange(layout.map { if (it.id == config.id) it.copy(width = it.width.next()) else it }) },
                onSettingsChange = { settings ->
                    onLayoutChange(layout.map { if (it.id == config.id) it.copy(settings = settings) else it })
                },
            )
        }

        if (availablePanels.isNotEmpty()) {
            item { SectionHeader("Add a widget") }
            items(availablePanels, key = { "add-${it.raw}" }) { panel ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        val newWidget = LedgerLayoutRules.defaultLedgerWidgets().first { it.panel == panel }
                            .copy(id = "widget-${panel.raw}-${layout.size}")
                        onLayoutChange(layout + newWidget)
                    },
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(PANEL_LABELS[panel] ?: panel.raw, style = MaterialTheme.typography.bodyMedium)
                    Text("+ Add", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun List<LedgerWidgetConfig>.moved(config: LedgerWidgetConfig, delta: Int): List<LedgerWidgetConfig> {
    val index = indexOf(config)
    val target = (index + delta).coerceIn(0, size - 1)
    if (target == index) return this
    return toMutableList().apply {
        removeAt(index)
        add(target, config)
    }
}

private fun LedgerWidgetWidth.next(): LedgerWidgetWidth {
    val values = LedgerWidgetWidth.entries
    return values[(values.indexOf(this) + 1) % values.size]
}

@Composable
private fun EditableWidgetRow(
    config: LedgerWidgetConfig,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onCycleWidth: () -> Unit,
    onSettingsChange: (LedgerWidgetSettings?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(config.settings?.title ?: PANEL_LABELS[config.panel] ?: config.panel.raw, style = MaterialTheme.typography.bodyMedium)
            Row {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                }
                TextButton(onClick = onCycleWidth) { Text(config.width.raw) }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove widget")
                }
            }
        }
        OutlinedTextField(
            value = config.settings?.title ?: "",
            onValueChange = { newTitle ->
                val title = newTitle.take(60).ifBlank { null }
                onSettingsChange((config.settings ?: LedgerWidgetSettings()).copy(title = title))
            },
            label = { Text("Custom title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Top N: ", style = MaterialTheme.typography.bodySmall)
            val topN = config.settings?.topN
            IconButton(onClick = {
                val next = ((topN ?: 12) - 1).coerceIn(3, 12)
                onSettingsChange((config.settings ?: LedgerWidgetSettings()).copy(topN = next))
            }) { Icon(Icons.Filled.Remove, contentDescription = "Decrease top N") }
            Text(topN?.toString() ?: "off", style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = {
                val next = ((topN ?: 2) + 1).coerceIn(3, 12)
                onSettingsChange((config.settings ?: LedgerWidgetSettings()).copy(topN = next))
            }) { Icon(Icons.Filled.Add, contentDescription = "Increase top N") }
            if (topN != null) {
                TextButton(onClick = { onSettingsChange((config.settings ?: LedgerWidgetSettings()).copy(topN = null)) }) {
                    Text("Clear")
                }
            }
        }
    }
}

/** The "now showing · {date}" kicker + narrative sentence above the stat tiles, matching
 *  `DashHero`'s copy *structure* (date kicker, title/screening/hour counts) — not its literal
 *  text, since Android's own voice ("THE NUMBERS" label in [LedgerScreen]'s top bar) is kept
 *  rather than overwritten with the web's copy verbatim, per this repository's Ledger parity
 *  plan. [viewedDisplayName] is always null today (no friend/shared viewer mode exists yet);
 *  intentionally not branched on here — see [LedgerScreen]'s kdoc on that parameter. */
@Composable
private fun HeroCopy(stats: LedgerStats, viewedDisplayName: String?) {
    val today = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
    }
    val totalTitles = stats.totalMovies + stats.totalSeries
    val hours = stats.totalWatchedMovieMinutes / 60
    Column {
        Text(
            "now showing · $today",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "A private record of $totalTitles titles, ${stats.totalViewings} screenings, and roughly " +
                "$hours hours spent in the dark.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private data class StatTileData(val label: String, val value: String, val bg: androidx.compose.ui.graphics.Color, val fg: androidx.compose.ui.graphics.Color)

@Composable
private fun StatTile(tile: StatTileData, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = tile.bg),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                tile.value,
                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = DmMonoFamily),
                color = tile.fg,
            )
            Text(
                tile.label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun EmptyRow(message: String) {
    Text(message, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun CategoryRow(category: LedgerCategoryCount) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(category.label, style = MaterialTheme.typography.bodyMedium)
        Text(category.count.toString(), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WatchlistRow(entry: LedgerWatchlistEntry) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(entry.title, style = MaterialTheme.typography.bodyMedium)
        entry.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun EncoreRow(entry: LedgerEncoreEntry) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${entry.title}${entry.year?.let { " ($it)" } ?: ""}", style = MaterialTheme.typography.bodyMedium)
        Text("${entry.viewingCount}×", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun VerdictRow(entry: LedgerVerdictEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(entry.title, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Us %.1f vs IMDb %.1f (Δ%.1f)".format(entry.ourRatingOn10, entry.imdbRating, entry.delta),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun QuarterRow(entry: LedgerQuarterRating) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(entry.quarterLabel, style = MaterialTheme.typography.bodyMedium)
        Text("★%.1f (%d titles)".format(entry.averageRating, entry.titleCount), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RevivalRow(entry: LedgerPremiereRevivalBucket) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(entry.monthLabel, style = MaterialTheme.typography.bodyMedium)
        Text("${entry.premieres} premiere(s), ${entry.revivals} revival(s)", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProgressRow(entry: LedgerProgressEntry) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(entry.title, style = MaterialTheme.typography.bodyMedium)
        Text("${entry.episodesWatched} / ${entry.episodeCount} episodes", style = MaterialTheme.typography.bodyMedium)
    }
}

/** Real text stats, not a data-free dot grid — the accessible replacement for the web app's
 *  tooltip-only 30-night grid (ledger.md §5). */
@Composable
private fun StreakSummary(streaks: LedgerStreaks) {
    Column {
        Text("Current streak: ${streaks.currentStreakDays} day(s)", style = MaterialTheme.typography.bodyMedium)
        Text("Longest streak: ${streaks.longestStreakDays} day(s)", style = MaterialTheme.typography.bodyMedium)
        if (streaks.recentActiveDates.isNotEmpty()) {
            Text("Recent screening dates:", style = MaterialTheme.typography.bodySmall)
            streaks.recentActiveDates.forEach { date ->
                Text(date, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MoviegoingSection(stats: LedgerMoviegoingStats) {
    Column {
        Text("Trips: ${stats.tripCount}", style = MaterialTheme.typography.bodyMedium)
        stats.totalSpend?.let { Text("Total spend: $%.2f".format(it), style = MaterialTheme.typography.bodyMedium) }
        if (stats.byYear.isNotEmpty()) {
            Text("By year", style = MaterialTheme.typography.bodySmall)
            stats.byYear.forEach { CategoryRow(it) }
        }
        if (stats.venues.isNotEmpty()) {
            Text("Venues", style = MaterialTheme.typography.bodySmall)
            stats.venues.forEach { CategoryRow(it) }
        }
        if (stats.companions.isNotEmpty()) {
            Text("Companions", style = MaterialTheme.typography.bodySmall)
            stats.companions.forEach { CategoryRow(it) }
        }
        if (stats.formats.isNotEmpty()) {
            Text("Formats", style = MaterialTheme.typography.bodySmall)
            stats.formats.forEach { CategoryRow(it) }
        }
        if (stats.tripCount == 0) {
            Text("No cinema trips logged yet.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private class LedgerViewModelFactory(
    private val repository: LedgerRepository,
    private val layoutRepository: LedgerLayoutRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LedgerViewModel(repository, layoutRepository) as T
}
