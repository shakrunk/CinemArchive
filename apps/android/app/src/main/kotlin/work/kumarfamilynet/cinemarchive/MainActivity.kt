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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import work.kumarfamilynet.cinemarchive.core.designsystem.MorphingBottomNav
import work.kumarfamilynet.cinemarchive.core.designsystem.NavDestination
import work.kumarfamilynet.cinemarchive.core.designsystem.expressiveSpring
import work.kumarfamilynet.cinemarchive.core.model.ArchiveFontFamily
import work.kumarfamilynet.cinemarchive.core.model.ArchiveFontScale
import work.kumarfamilynet.cinemarchive.core.model.ArchivePalette
import work.kumarfamilynet.cinemarchive.core.model.ArchiveThemeMode
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
import work.kumarfamilynet.cinemarchive.feature.settings.PermissionsRoute
import work.kumarfamilynet.cinemarchive.feature.settings.ProfileRoute
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
                            )
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

private enum class Tab { DISCOVER, LIBRARY, UP_NEXT, LEDGER }

private sealed interface Overlay {
    data class Detail(val titleId: String) : Overlay

    /** [preselected] is set when the add was started from a specific Discover result rather
     *  than the FAB, so the overlay opens on its log step instead of an empty search box. */
    data class Add(val preselected: MediaSearchResult? = null) : Overlay
    data object Profile : Overlay
    data object Appearance : Overlay
    data object About : Overlay
    data object Permissions : Overlay
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
) {
    var tab by remember { mutableStateOf(Tab.LIBRARY) }
    var overlay by remember { mutableStateOf<Overlay?>(initialTitleId?.let { Overlay.Detail(it) }) }
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
                Overlay.Appearance, Overlay.About, Overlay.Permissions -> Overlay.Profile
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
    // contentWindowInsets is zeroed (MorphingBottomNav insets its own bottom edge instead),
    // so the status bar inset is applied once here, above both the Scaffold and the overlay.
    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                MorphingBottomNav(
                    destinations = listOf(
                        NavDestination(Tab.DISCOVER, "Discover", Icons.Outlined.Explore, Icons.Filled.Explore),
                        NavDestination(
                            Tab.LIBRARY,
                            "Library",
                            icon = if (libraryViewMode == LibraryViewMode.GRID) Icons.Outlined.Apps else Icons.AutoMirrored.Outlined.ViewList,
                            selectedIcon = if (libraryViewMode == LibraryViewMode.GRID) Icons.Filled.Apps else Icons.AutoMirrored.Filled.ViewList,
                        ),
                        NavDestination(Tab.UP_NEXT, "Up Next", Icons.Outlined.PlayArrow, Icons.Filled.PlayArrow),
                        NavDestination(Tab.LEDGER, "Ledger", Icons.Outlined.Insights, Icons.Filled.Insights),
                    ),
                    selected = tab,
                    onSelect = { tab = it },
                )
            },
        ) { innerPadding ->
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
                            onFabExpandedChange = { fabExpanded = it },
                        )
                        Tab.LEDGER -> LedgerRoute(
                            ledgerRepository,
                            ledgerLayoutRepository,
                            onOpenProfile = openProfile,
                            profileInitial = profileInitial,
                        )
                    }
                }

                if (tab != Tab.LEDGER) {
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
            )
            Overlay.Appearance -> AppearanceRoute(preferencesRepository, onBack = openProfile)
            Overlay.About -> AboutRoute(
                appVersionName,
                appUpdateRepository,
                apkInstaller,
                preferencesRepository,
                onBack = openProfile,
            )
            Overlay.Permissions -> PermissionsRoute(onBack = openProfile)
        }
        }
    }
}

/** How far the overlay travels under a full predictive-back swipe. Deliberately restrained —
 *  the system is already animating the window behind it. */
private const val BACK_SCALE_TRAVEL = 0.12f
private const val BACK_ALPHA_TRAVEL = 0.35f
private const val BACK_SLIDE_FRACTION = 0.10f
