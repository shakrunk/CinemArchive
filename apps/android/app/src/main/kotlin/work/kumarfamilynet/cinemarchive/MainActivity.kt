package work.kumarfamilynet.cinemarchive

import android.Manifest
import android.animation.ObjectAnimator
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.core.designsystem.ExpressivePillFab
import work.kumarfamilynet.cinemarchive.core.designsystem.MediumWindowBreakpoint
import work.kumarfamilynet.cinemarchive.core.designsystem.MorphingBottomNav
import work.kumarfamilynet.cinemarchive.core.designsystem.MorphingNavigationRail
import work.kumarfamilynet.cinemarchive.core.designsystem.NavDestination
import work.kumarfamilynet.cinemarchive.core.designsystem.TicketScreen
import work.kumarfamilynet.cinemarchive.core.designsystem.expressiveSpring
import work.kumarfamilynet.cinemarchive.core.model.ArchiveFontFamily
import work.kumarfamilynet.cinemarchive.core.model.ArchiveFontScale
import work.kumarfamilynet.cinemarchive.core.model.ArchivePalette
import work.kumarfamilynet.cinemarchive.core.model.ArchiveThemeMode
import work.kumarfamilynet.cinemarchive.core.model.CinemaOuting
import work.kumarfamilynet.cinemarchive.core.model.LibraryViewMode
import work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult
import work.kumarfamilynet.cinemarchive.core.model.asSearchResult
import work.kumarfamilynet.cinemarchive.data.ApkInstaller
import work.kumarfamilynet.cinemarchive.data.AppUpdateRepository
import work.kumarfamilynet.cinemarchive.data.AuthRepository
import work.kumarfamilynet.cinemarchive.data.DiscoverRepository
import work.kumarfamilynet.cinemarchive.data.LedgerLayoutRepository
import work.kumarfamilynet.cinemarchive.data.LedgerRepository
import work.kumarfamilynet.cinemarchive.data.LibraryRepository
import work.kumarfamilynet.cinemarchive.data.LibrarySyncRepository
import work.kumarfamilynet.cinemarchive.data.OutingsRepository
import work.kumarfamilynet.cinemarchive.data.PreferencesRepository
import work.kumarfamilynet.cinemarchive.feature.auth.LoginRoute
import work.kumarfamilynet.cinemarchive.feature.discover.AddTitleOverlayRoute
import work.kumarfamilynet.cinemarchive.feature.discover.DiscoverRoute
import work.kumarfamilynet.cinemarchive.feature.ledger.LedgerRoute
import work.kumarfamilynet.cinemarchive.feature.library.LibraryRoute
import work.kumarfamilynet.cinemarchive.feature.library.TitleDetailRoute
import work.kumarfamilynet.cinemarchive.feature.settings.AboutRoute
import work.kumarfamilynet.cinemarchive.feature.settings.AppearanceRoute
import work.kumarfamilynet.cinemarchive.feature.settings.DeveloperSettingsRoute
import work.kumarfamilynet.cinemarchive.feature.settings.PermissionsRoute
import work.kumarfamilynet.cinemarchive.feature.settings.ProfileRoute
import work.kumarfamilynet.cinemarchive.feature.settings.SettingsCategory
import work.kumarfamilynet.cinemarchive.feature.settings.profileInitial
import work.kumarfamilynet.cinemarchive.feature.upnext.UpNextRoute

private val VoidColor = Color(0xFF0B0907)
private val AmberColor = Color(0xFFE9B266)

class MainActivity : ComponentActivity() {
    companion object {
        /** Read by [OutingCompletionReceiver]'s notification tap — opens straight to the
         *  title whose outing just completed (standard launchMode recreates this Activity on
         *  tap via FLAG_ACTIVITY_CLEAR_TASK, so onCreate always sees a fresh intent). */
        const val EXTRA_OPEN_TITLE_ID = "open_title_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        // The system splash (static reel on void) hands off to this fade rather than
        // vanishing abruptly, so it reads as one continuous transition into the Compose
        // splash beneath, which is already on-screen and picks up the spin from here.
        splashScreen.setOnExitAnimationListener { splashScreenView ->
            ObjectAnimator.ofFloat(splashScreenView.view, View.ALPHA, 1f, 0f).apply {
                duration = 220L
                doOnEnd { splashScreenView.remove() }
                start()
            }
        }
        val repository = (application as CinemArchiveApplication).libraryRepository
        val discoverRepository = (application as CinemArchiveApplication).discoverRepository
        val ledgerRepository = (application as CinemArchiveApplication).ledgerRepository
        val ledgerLayoutRepository = (application as CinemArchiveApplication).ledgerLayoutRepository
        val preferencesRepository = (application as CinemArchiveApplication).preferencesRepository
        val outingsRepository = (application as CinemArchiveApplication).outingsRepository
        val authRepository = (application as CinemArchiveApplication).authRepository
        val librarySyncRepository = (application as CinemArchiveApplication).librarySyncRepository
        val appUpdateRepository = (application as CinemArchiveApplication).appUpdateRepository
        val apkInstaller = (application as CinemArchiveApplication).apkInstaller
        val initialTitleId = intent.getStringExtra(EXTRA_OPEN_TITLE_ID)

        // Magic-link tap: standard launchMode means this is a fresh onCreate (same pattern
        // OutingCompletionReceiver's notification tap relies on), so intent.data is always
        // this launch's own — never a stale one from a prior instance.
        intent.data?.let { uri ->
            if (authRepository.isAuthCallback(uri)) {
                lifecycleScope.launch { withContext(Dispatchers.IO) { authRepository.completeMagicLinkCallback(uri) } }
            }
        }

        setContent {
            val themeMode by preferencesRepository.observeThemeMode()
                .collectAsStateWithLifecycle(initialValue = ArchiveThemeMode.DARK)
            val palette by preferencesRepository.observePalette()
                .collectAsStateWithLifecycle(initialValue = ArchivePalette.BRAND)
            val fontFamily by preferencesRepository.observeFontFamily()
                .collectAsStateWithLifecycle(initialValue = ArchiveFontFamily.DEFAULT)
            val fontScale by preferencesRepository.observeFontScale()
                .collectAsStateWithLifecycle(initialValue = ArchiveFontScale.DEFAULT)
            val session by authRepository.observeSession().collectAsStateWithLifecycle()
            val isDebugBuild = BuildConfig.DEBUG
            // Read at this top level (rather than inside CinemArchiveApp) so the banner covers
            // LoginRoute too, not just the signed-in app shell. remember(isDebugBuild) keeps the
            // Flow instance stable across recompositions of this whole setContent block instead
            // of restarting the DataStore collection every time (isDebugBuild itself never
            // changes, but a fresh `preferencesRepository.observeX(...)` call each recomposition
            // would still be a fresh Flow instance).
            val showBuildBannerFlow = remember(isDebugBuild) { preferencesRepository.observeDevShowBuildBanner(isDebugBuild) }
            val showBuildBanner by showBuildBannerFlow.collectAsStateWithLifecycle(initialValue = isDebugBuild)
            CinemArchiveTheme(mode = themeMode, palette = palette, fontFamily = fontFamily, fontScale = fontScale) {
                Surface {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (session == null) {
                            LoginRoute(authRepository)
                        } else {
                            CinemArchiveApp(
                                repository,
                                discoverRepository,
                                ledgerRepository,
                                ledgerLayoutRepository,
                                preferencesRepository,
                                outingsRepository,
                                authRepository,
                                librarySyncRepository,
                                appUpdateRepository,
                                apkInstaller,
                                initialTitleId = initialTitleId,
                                appVersionName = BuildConfig.VERSION_NAME,
                                isDebugBuild = isDebugBuild,
                            )
                        }
                        // Sibling of both LoginRoute and CinemArchiveApp (rather than nested
                        // inside the latter) so it covers sign-in too — added after the app
                        // content but before the splash below, so it sits under the splash while
                        // that's still up and over everything once it fades out.
                        if (showBuildBanner) {
                            DebugBuildBanner(isDebugBuild)
                        }
                        var showBrandedSplash by remember { mutableStateOf(true) }
                        AnimatedVisibility(
                            visible = showBrandedSplash,
                            exit = fadeOut(animationSpec = tween(250)),
                        ) {
                            CinemArchiveSplash(onFinished = { showBrandedSplash = false })
                        }
                    }
                }
            }
        }
    }
}

/** Post-handoff splash: continues the film-reel spin the system splash's static icon
 *  couldn't do, over a pulsing amber "projector beam" glow — mirrors the web app's
 *  `.projector-beam` atmosphere layer (src/index.css). Shown for a fixed minimum beat
 *  before crossfading into the real UI underneath. */
@Composable
private fun CinemArchiveSplash(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(850)
        onFinished()
    }
    val infiniteTransition = rememberInfiniteTransition(label = "splash")
    val reelRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(2200, easing = LinearEasing)),
        label = "reelRotation",
    )
    val beamAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "beamAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(VoidColor)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(AmberColor.copy(alpha = 0.18f * beamAlpha), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.38f),
                        radius = size.maxDimension * 0.55f,
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .size(96.dp)
                .rotate(reelRotation),
        )
    }
}

/** Persistent "which build is this" indicator — Settings > Developer Settings' opt-in toggle.
 *  A small pill rather than a full-width bar so it doesn't compete with the top-bar chrome of
 *  whatever screen is underneath; no gesture modifier, so it never intercepts touches meant for
 *  the content behind it. */
@Composable
private fun DebugBuildBanner(isDebugBuild: Boolean) {
    Box(modifier = Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopEnd) {
        Surface(
            shape = RoundedCornerShape(bottomStart = 12.dp),
            color = if (isDebugBuild) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            contentColor = if (isDebugBuild) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onError,
        ) {
            Text(
                if (isDebugBuild) "DEBUG" else "RELEASE",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

private enum class Tab { DISCOVER, LIBRARY, UP_NEXT, LEDGER }

private sealed interface Overlay {
    data class Detail(val titleId: String) : Overlay

    /** [preselected] is set when the add was started from a specific Discover result rather
     *  than the FAB, so the overlay opens on its log step instead of an empty search box.
     *  [openKey] is generated per opening and scopes the overlay's ViewModel to *this* add —
     *  see AddTitleOverlayRoute's kdoc for what it reuses otherwise. */
    data class Add(
        val preselected: MediaSearchResult? = null,
        val openKey: String = java.util.UUID.randomUUID().toString(),
    ) : Overlay
    data object Profile : Overlay
    data object Appearance : Overlay
    data object About : Overlay
    data object Permissions : Overlay
    data object DeveloperSettings : Overlay

    /** The "at the theater" screen (seat + ticket QR code) — carries the outing and title name
     *  by value, like [Add]'s [preselected], rather than an ID to re-fetch: the marquee card
     *  that opens this already has both in memory. */
    data class Ticket(val outing: CinemaOuting, val titleName: String) : Overlay
}

/**
 * Nav via local state rather than androidx.navigation — matches the design handoff's own
 * model (CinemArchive Android.dc.html): four persistent tabs plus a FAB sit beneath a stack
 * of full-screen overlays (title detail / add / profile / appearance / about), each of which
 * simply closes back to whichever tab was already active rather than pushing a back-stack
 * entry of its own.
 */
@Composable
private fun CinemArchiveApp(
    repository: LibraryRepository,
    discoverRepository: DiscoverRepository,
    ledgerRepository: LedgerRepository,
    ledgerLayoutRepository: LedgerLayoutRepository,
    preferencesRepository: PreferencesRepository,
    outingsRepository: OutingsRepository,
    authRepository: AuthRepository,
    librarySyncRepository: LibrarySyncRepository,
    appUpdateRepository: AppUpdateRepository,
    apkInstaller: ApkInstaller,
    initialTitleId: String? = null,
    appVersionName: String,
    isDebugBuild: Boolean,
) {
    var tab by remember { mutableStateOf(Tab.LIBRARY) }
    var overlay by remember { mutableStateOf<Overlay?>(initialTitleId?.let { Overlay.Detail(it) }) }
    // Only consulted in the wide/foldable-unfolded split layout below — the list pane there
    // stays on screen permanently, so which detail sits opposite it needs its own state
    // instead of being encoded in `overlay` the way the phone-width push navigation is.
    var selectedSettingsCategory by remember { mutableStateOf(SettingsCategory.APPEARANCE) }
    // Hoisted above LibraryRoute (rather than let it own this DataStore subscription itself)
    // because LibraryRoute is torn down and recreated every time `tab` switches away from and
    // back to LIBRARY — re-subscribing there would reset to collectAsStateWithLifecycle's
    // hardcoded initialValue on every visit, flashing grid before the real persisted value
    // loads. This composable lives for the whole signed-in session, so it only pays that
    // flash once, on cold start.
    val libraryViewMode by preferencesRepository.observeLibraryViewMode()
        .collectAsStateWithLifecycle(initialValue = LibraryViewMode.GRID)

    // Hoisted for the same reason, and shared by both poster grids: pinching the density on
    // Discover and finding Library unchanged would be the surprising behaviour.
    val posterGridColumns by preferencesRepository.observePosterGridColumns()
        .collectAsStateWithLifecycle(initialValue = 2)

    // Governs the Developer Settings row's visibility in Profile — debug builds default
    // unlocked, release builds default locked (isDebugBuild), until the version-tap gesture in
    // About & Legal overrides it. remember(isDebugBuild): see the matching comment in
    // MainActivity.onCreate's setContent — keeps the Flow instance stable across this
    // composable's frequent recompositions (tab switches, overlay changes) instead of
    // restarting the DataStore collection on every one.
    val devSettingsUnlockedFlow = remember(isDebugBuild) { preferencesRepository.observeDevSettingsUnlocked(isDebugBuild) }
    val devSettingsUnlocked by devSettingsUnlockedFlow.collectAsStateWithLifecycle(initialValue = isDebugBuild)

    val openProfile = { overlay = Overlay.Profile }
    val closeOverlay = { overlay = null }

    val session by authRepository.observeSession().collectAsStateWithLifecycle()
    val profileInitial = remember(session?.email) { profileInitial(session?.email) }

    // Requested contextually — the moment the user opens the schedule sheet, not at app
    // launch (docs/superpowers/plans/2026-07-21-android-cinema-outings.md §6) — the OS prompt
    // means nothing before the user has expressed intent to get a "how was it?" notification.
    // Safe to call unconditionally: the system no-ops if already granted/permanently denied.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val requestNotificationPermission = {
        if (Build.VERSION.SDK_INT >= 33) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // This composable only enters composition once signed in (MainActivity's session gate),
    // so firing once here covers "just completed the magic-link sign-in" — a case cold-launch
    // sync (CinemArchiveApplication.onCreate) can't, since that runs before any session exists
    // yet. Harmless if it races/duplicates that launch-time sync — syncNow() is idempotent.
    LaunchedEffect(Unit) { librarySyncRepository.syncNow() }

    // onResume reconciliation trigger (docs/superpowers/plans/2026-07-21-android-cinema-
    // outings.md §5) — a superset of the web's foreground triggers (app load is already
    // covered by CinemArchiveApplication.onCreate). Coroutine scope tied to this composable's
    // lifecycle, not the ViewModel layer, since it's app-shell-wide rather than one screen's.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val onToggleLibraryViewMode: () -> Unit = {
        val next = if (libraryViewMode == LibraryViewMode.GRID) LibraryViewMode.LIST else LibraryViewMode.GRID
        coroutineScope.launch { preferencesRepository.setLibraryViewMode(next) }
    }
    val onPosterGridColumnsChange: (Int) -> Unit = { next ->
        coroutineScope.launch { preferencesRepository.setPosterGridColumns(next) }
    }
    // Persists the lock and steers navigation away from Developer Settings in the same step —
    // without the latter, the row this screen was opened from just disappeared from Profile
    // (devSettingsUnlocked flips to false) while `overlay`/`selectedSettingsCategory` still
    // point at it, stranding the split-mode detail pane and the phone-stack overlay on a screen
    // with no way back in.
    val lockDeveloperSettings: () -> Unit = {
        coroutineScope.launch { preferencesRepository.setDevSettingsUnlocked(false) }
        if (selectedSettingsCategory == SettingsCategory.DEVELOPER) selectedSettingsCategory = SettingsCategory.APPEARANCE
        if (overlay == Overlay.DeveloperSettings) overlay = Overlay.Profile
    }

    // The FAB is a single instance shared across tabs, but only Discover/Library/Up Next report
    // scroll-collapse (they're the ones with a header/list worth tucking it away from) —
    // reset to expanded on every tab switch so a collapse from the tab just left doesn't
    // leak into a tab that never reports back in.
    var fabExpanded by remember { mutableStateOf(true) }
    LaunchedEffect(tab) { fabExpanded = true }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                // Pull remote changes (e.g. made on the web app while backgrounded) before
                // deciding which outings are due, same ordering rationale as the launch path.
                coroutineScope.launch {
                    librarySyncRepository.syncNow()
                    outingsRepository.completeDueOutings()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Without this, the system back gesture/button has nothing to intercept and falls
    // through to the default Activity behavior (finish()) — it wouldn't unwind overlays at
    // all, it'd just exit the app from underneath one. Appearance/About nest one level below
    // Profile (matching their own in-overlay back arrows); everything else closes outright.
    //
    // PredictiveBackHandler rather than BackHandler so the overlay's exit follows the
    // gesture instead of popping the instant the finger lifts: `backProgress` tracks the
    // swipe (0..1) and drives the transform below. Only a *completed* gesture commits the
    // navigation; a cancelled one springs the overlay back to rest.
    val backProgress = remember { Animatable(0f) }
    PredictiveBackHandler(enabled = overlay != null) { progress ->
        try {
            progress.collect { backEvent -> backProgress.snapTo(backEvent.progress) }
            overlay = when (overlay) {
                Overlay.Appearance, Overlay.About, Overlay.Permissions, Overlay.DeveloperSettings -> Overlay.Profile
                else -> null
            }
            backProgress.snapTo(0f)
        } catch (_: CancellationException) {
            backProgress.animateTo(0f, expressiveSpring())
        }
    }

    // The overlay is a sibling of the Scaffold, not nested inside its content slot, so it
    // paints above the bottom nav bar regardless of Scaffold's own internal draw order —
    // mirroring the design handoff's overlays (z-index 40/50, above the nav's implicit
    // stacking context) covering the full device frame, nav bar included. Scaffold's own
    // contentWindowInsets is zeroed (MorphingBottomNav/MorphingNavigationRail inset their own
    // edges instead), so the status bar inset is applied once here, above both the Scaffold
    // and the overlay.
    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Below Medium, nav stays a bottom bar as before. At/above it — an unfolded
            // foldable, a tablet — a bottom bar stretched across the full width reads as a
            // phone control blown up rather than adapted, so nav moves to a leading-edge rail.
            val useNavigationRail = maxWidth >= MediumWindowBreakpoint
            val navDestinations = listOf(
                NavDestination(Tab.DISCOVER, "Discover", Icons.Outlined.Explore, Icons.Filled.Explore),
                NavDestination(
                    Tab.LIBRARY,
                    "Library",
                    icon = if (libraryViewMode == LibraryViewMode.GRID) Icons.Outlined.Apps else Icons.AutoMirrored.Outlined.ViewList,
                    selectedIcon = if (libraryViewMode == LibraryViewMode.GRID) Icons.Filled.Apps else Icons.AutoMirrored.Filled.ViewList,
                ),
                NavDestination(Tab.UP_NEXT, "Up Next", Icons.Outlined.PlayArrow, Icons.Filled.PlayArrow),
                NavDestination(Tab.LEDGER, "Ledger", Icons.Outlined.Insights, Icons.Filled.Insights),
            )

            @Composable
            fun TabScaffoldContent(innerPadding: PaddingValues) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (tab) {
                            Tab.DISCOVER -> DiscoverRoute(
                                discoverRepository,
                                repository,
                                gridColumns = posterGridColumns,
                                onGridColumnsChange = onPosterGridColumnsChange,
                                onOpenProfile = openProfile,
                                profileInitial = profileInitial,
                                onFabExpandedChange = { fabExpanded = it },
                                onTitleClick = { overlay = Overlay.Detail(it) },
                                onAddTitle = { overlay = Overlay.Add(it.asSearchResult()) },
                            )
                            Tab.LIBRARY -> LibraryRoute(
                                repository,
                                librarySyncRepository,
                                viewMode = libraryViewMode,
                                onToggleViewMode = onToggleLibraryViewMode,
                                gridColumns = posterGridColumns,
                                onGridColumnsChange = onPosterGridColumnsChange,
                                onOpenProfile = openProfile,
                                profileInitial = profileInitial,
                                onTitleClick = { overlay = Overlay.Detail(it) },
                                onFabExpandedChange = { fabExpanded = it },
                            )
                            Tab.UP_NEXT -> UpNextRoute(
                                repository,
                                outingsRepository,
                                librarySyncRepository,
                                onOpenProfile = openProfile,
                                profileInitial = profileInitial,
                                onTitleClick = { overlay = Overlay.Detail(it) },
                                onViewTicket = { overlay = Overlay.Ticket(it.outing, it.titleName) },
                                onFabExpandedChange = { fabExpanded = it },
                            )
                            Tab.LEDGER -> LedgerRoute(
                                ledgerRepository,
                                ledgerLayoutRepository,
                                onOpenProfile = openProfile,
                                profileInitial = profileInitial,
                                isWideLayout = useNavigationRail,
                            )
                        }
                    }

                    if (tab != Tab.LEDGER && tab != Tab.DISCOVER) {
                        ExpressivePillFab(
                            label = "New Title",
                            expanded = fabExpanded,
                            onClick = { overlay = Overlay.Add() },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = innerPadding.calculateBottomPadding() + 16.dp),
                        )
                    }
                }
            }

            if (useNavigationRail) {
                // No bottomBar here to self-apply WindowInsets.navigationBars the way
                // MorphingBottomNav does below — the gesture-nav pill is still at the bottom
                // of the device regardless of nav living in a leading rail, so the whole row
                // (rail included, so its last item doesn't sit under the pill) gets its own
                // bottom inset explicitly instead.
                Row(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
                    MorphingNavigationRail(
                        destinations = navDestinations,
                        selected = tab,
                        onSelect = { tab = it },
                    )
                    Scaffold(
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        modifier = Modifier.weight(1f),
                    ) { innerPadding -> TabScaffoldContent(innerPadding) }
                }
            } else {
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        MorphingBottomNav(
                            destinations = navDestinations,
                            selected = tab,
                            onSelect = { tab = it },
                        )
                    },
                ) { innerPadding -> TabScaffoldContent(innerPadding) }
            }

            // The overlay's predictive-back transform: shrink it and ease it toward the trailing
            // edge as the gesture progresses, so the tab content behind is revealed underneath
            // rather than the overlay vanishing in one frame.
            Box(
                modifier = Modifier.graphicsLayer {
                    val p = backProgress.value
                    val scale = 1f - BACK_SCALE_TRAVEL * p
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - BACK_ALPHA_TRAVEL * p
                    translationX = size.width * BACK_SLIDE_FRACTION * p
                },
            ) {
            // Which settings sub-screen the trailing pane below shows while wide-mode split is
            // active: Appearance/About/Permissions overlay values still carry it (e.g. the
            // device was unfolded mid-visit to one of them), otherwise it's whatever was last
            // picked from the list, defaulting to Appearance.
            val settingsCategoryFromOverlay = when (overlay) {
                Overlay.Appearance -> SettingsCategory.APPEARANCE
                Overlay.Permissions -> SettingsCategory.PERMISSIONS
                Overlay.About -> SettingsCategory.ABOUT
                Overlay.DeveloperSettings -> SettingsCategory.DEVELOPER
                else -> null
            }
            val isSettingsOverlay = overlay == Overlay.Profile || settingsCategoryFromOverlay != null

            // Below Medium, Profile/Appearance/About/Permissions stay the phone-style
            // full-screen stack (below) — an unfolded foldable or a tablet instead shows the
            // category list and its detail side by side permanently, same threshold the nav
            // rail above just switched on rather than a second breakpoint for the same
            // physical class of device.
            if (useNavigationRail && isSettingsOverlay) {
                val activeCategory = settingsCategoryFromOverlay ?: selectedSettingsCategory
                // Fold/rotate mid-visit to a phone-style Appearance/About/Permissions push can
                // land here with overlay still holding that value rather than Profile — absorb
                // it into selectedSettingsCategory once and normalize overlay back to Profile,
                // so a later list tap isn't overridden by this same stale overlay value on
                // every recomposition (settingsCategoryFromOverlay would otherwise keep winning
                // the `?:` above regardless of what's tapped next).
                LaunchedEffect(settingsCategoryFromOverlay) {
                    settingsCategoryFromOverlay?.let {
                        selectedSettingsCategory = it
                        overlay = Overlay.Profile
                    }
                }
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.width(SettingsListPaneWidth)) {
                        ProfileRoute(
                            repository,
                            preferencesRepository,
                            authRepository,
                            appVersionName,
                            onClose = closeOverlay,
                            onOpenAppearance = { selectedSettingsCategory = SettingsCategory.APPEARANCE },
                            onOpenAbout = { selectedSettingsCategory = SettingsCategory.ABOUT },
                            onOpenPermissions = { selectedSettingsCategory = SettingsCategory.PERMISSIONS },
                            devSettingsUnlocked = devSettingsUnlocked,
                            onOpenDeveloperSettings = { selectedSettingsCategory = SettingsCategory.DEVELOPER },
                            selectedCategory = activeCategory,
                        )
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(modifier = Modifier.weight(1f)) {
                        // showBack = false: this pane has no "back" of its own to unwind — the
                        // list pane opposite it is the only way out, via its own close button.
                        when (activeCategory) {
                            SettingsCategory.APPEARANCE -> AppearanceRoute(preferencesRepository, onBack = closeOverlay, showBack = false)
                            SettingsCategory.ABOUT -> AboutRoute(
                                appVersionName,
                                appUpdateRepository,
                                apkInstaller,
                                preferencesRepository,
                                onBack = closeOverlay,
                                showBack = false,
                            )
                            SettingsCategory.PERMISSIONS -> PermissionsRoute(
                                onBack = closeOverlay,
                                apkInstaller = apkInstaller,
                                appUpdateRepository = appUpdateRepository,
                                showBack = false,
                            )
                            SettingsCategory.DEVELOPER -> DeveloperSettingsRoute(
                                preferencesRepository,
                                appVersionName,
                                isDebugBuild,
                                onBack = closeOverlay,
                                onLock = lockDeveloperSettings,
                                showBack = false,
                            )
                        }
                    }
                }
            } else {
            when (val current = overlay) {
                null -> Unit
                is Overlay.Detail -> TitleDetailRoute(
                    repository,
                    outingsRepository,
                    current.titleId,
                    onBack = closeOverlay,
                    onRequestNotificationPermission = requestNotificationPermission,
                )
                is Overlay.Add -> AddTitleOverlayRoute(
                    discoverRepository,
                    repository,
                    openKey = current.openKey,
                    onClose = closeOverlay,
                    // Land on what was just created rather than back where the add started — the
                    // title detail screen is where every follow-up action (rate, log a viewing,
                    // book an outing) lives.
                    onAdded = { overlay = Overlay.Detail(it) },
                    onOpenTitle = { overlay = Overlay.Detail(it) },
                    preselected = current.preselected,
                )
                Overlay.Profile -> ProfileRoute(
                    repository,
                    preferencesRepository,
                    authRepository,
                    appVersionName,
                    onClose = closeOverlay,
                    onOpenAppearance = { overlay = Overlay.Appearance },
                    onOpenAbout = { overlay = Overlay.About },
                    onOpenPermissions = { overlay = Overlay.Permissions },
                    devSettingsUnlocked = devSettingsUnlocked,
                    onOpenDeveloperSettings = { overlay = Overlay.DeveloperSettings },
                )
                Overlay.Appearance -> AppearanceRoute(preferencesRepository, onBack = openProfile)
                Overlay.About -> AboutRoute(
                    appVersionName,
                    appUpdateRepository,
                    apkInstaller,
                    preferencesRepository,
                    onBack = openProfile,
                )
                Overlay.Permissions -> PermissionsRoute(onBack = openProfile, apkInstaller = apkInstaller, appUpdateRepository = appUpdateRepository)
                Overlay.DeveloperSettings -> DeveloperSettingsRoute(
                    preferencesRepository,
                    appVersionName,
                    isDebugBuild,
                    onBack = openProfile,
                    onLock = lockDeveloperSettings,
                )
                is Overlay.Ticket -> TicketScreen(current.titleName, current.outing, onBack = closeOverlay)
            }
            }
            }
        }
    }
}

/** How far the overlay travels under a full predictive-back swipe. Deliberately restrained —
 *  the system is already animating the window behind it. */
private const val BACK_SCALE_TRAVEL = 0.12f
private const val BACK_ALPHA_TRAVEL = 0.35f
private const val BACK_SLIDE_FRACTION = 0.10f

/** Fixed width of the settings list pane in the wide/split layout — a detail pane that grows
 *  with the window but a list pane that also grew would leave the category rows looking
 *  stretched well past what their short titles need. */
private val SettingsListPaneWidth = 320.dp
