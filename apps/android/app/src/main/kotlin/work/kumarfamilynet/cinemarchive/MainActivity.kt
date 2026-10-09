package work.kumarfamilynet.cinemarchive


import android.Manifest
import android.content.Intent
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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.core.designsystem.ExpressivePillFab
import work.kumarfamilynet.cinemarchive.core.designsystem.MediumWindowBreakpoint
import work.kumarfamilynet.cinemarchive.core.designsystem.MorphingBottomNav
import work.kumarfamilynet.cinemarchive.core.designsystem.MorphingNavigationRail
import work.kumarfamilynet.cinemarchive.core.designsystem.NavDestination
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
import work.kumarfamilynet.cinemarchive.data.ListsRepository
import work.kumarfamilynet.cinemarchive.data.NotificationRules
import work.kumarfamilynet.cinemarchive.data.visibleRuntime
import work.kumarfamilynet.cinemarchive.data.OutingsRepository
import work.kumarfamilynet.cinemarchive.data.PreferencesRepository
import work.kumarfamilynet.cinemarchive.data.SyncServices
import work.kumarfamilynet.cinemarchive.feature.auth.LoginRoute
import work.kumarfamilynet.cinemarchive.feature.discover.AddTitleOverlayRoute
import work.kumarfamilynet.cinemarchive.feature.friends.FriendsRoute
import work.kumarfamilynet.cinemarchive.feature.discover.DiscoverRoute
import work.kumarfamilynet.cinemarchive.feature.ledger.LedgerRoute
import work.kumarfamilynet.cinemarchive.feature.library.LibraryRoute
import work.kumarfamilynet.cinemarchive.feature.library.rememberAccountLibraryFilters
import work.kumarfamilynet.cinemarchive.feature.library.TitleDetailRoute
import work.kumarfamilynet.cinemarchive.feature.lists.ListsRoute
import work.kumarfamilynet.cinemarchive.feature.settings.AboutRoute
import work.kumarfamilynet.cinemarchive.feature.settings.AppearanceRoute
import work.kumarfamilynet.cinemarchive.feature.settings.DeveloperSettingsRoute
import work.kumarfamilynet.cinemarchive.feature.settings.ImportSyncRoute
import work.kumarfamilynet.cinemarchive.feature.settings.PermissionsRoute
import work.kumarfamilynet.cinemarchive.feature.settings.ProfileRoute
import work.kumarfamilynet.cinemarchive.feature.settings.SettingsCategory
import work.kumarfamilynet.cinemarchive.feature.settings.SharingRoute
import work.kumarfamilynet.cinemarchive.data.SharingRules
import work.kumarfamilynet.cinemarchive.feature.settings.profileInitial
import work.kumarfamilynet.cinemarchive.feature.settings.IdentityRoute
import work.kumarfamilynet.cinemarchive.feature.settings.InvitesRoute
import work.kumarfamilynet.cinemarchive.feature.settings.NotificationsRoute
import work.kumarfamilynet.cinemarchive.core.designsystem.LocalUnreadNotificationCount
import work.kumarfamilynet.cinemarchive.feature.upnext.UpNextRoute

private val VoidColor = Color(0xFF0B0907)
private val AmberColor = Color(0xFFE9B266)

class MainActivity : ComponentActivity() {
    private var sharedToken by mutableStateOf<String?>(null)
    private var sharedLaunch by androidx.compose.runtime.mutableLongStateOf(0L)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingLink(intent)
    }

    private fun handleIncomingLink(incoming: Intent) {
        val uri = incoming.data ?: return
        val app = application as CinemArchiveApplication
        val token = SharingRules.tokenFromLink(uri.toString())
        if (token != null) {
            sharedToken = token
            sharedLaunch++
        }
        else if (app.authRepository.isAuthCallback(uri)) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { app.authRepository.completeMagicLinkCallback(uri) }
            }
        }
    }
    companion object {
        /** Read by [OutingCompletionReceiver]'s notification tap — opens straight to the
         *  title whose outing just completed (FLAG_ACTIVITY_CLEAR_TASK recreates this
         *  Activity, so onCreate always sees a fresh intent). */
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
        val app = application as CinemArchiveApplication
        val discoverRepository = app.discoverRepository
        val preferencesRepository = app.preferencesRepository
        val authRepository = app.authRepository
        val appUpdateRepository = app.appUpdateRepository
        val apkInstaller = app.apkInstaller
        val initialTitleId = intent.getStringExtra(EXTRA_OPEN_TITLE_ID)
        val initialTitleOwnerId = intent.getStringExtra(OutingCompletionReceiver.EXTRA_OWNER_ID)

        // Both cold and singleTop warm launches use the same share/auth dispatcher.
        handleIncomingLink(intent)

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
            val identity by authRepository.observeIdentity().collectAsStateWithLifecycle()
            val publishedRuntime by app.accountRuntimeManager.runtime.collectAsStateWithLifecycle()
            // Never render a runtime that is not exactly the current sign-in (see visibleRuntime).
            val runtime = visibleRuntime(identity, publishedRuntime)
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
                        val openedToken = sharedToken
                        if (openedToken != null) {
                            androidx.compose.runtime.key(openedToken, sharedLaunch) {
                                SharedLibraryRoute(openedToken, app.sharedLibraryRepository, onClose = {
                                    sharedToken = null
                                    intent.data = null
                                })
                            }
                        } else if (identity == null) {
                            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                                LoginRoute(authRepository, modifier = Modifier.weight(1f))
                                SharedLinkPromptButton(onOpen = { sharedToken = it })
                            }
                        } else {
                            val rt = runtime
                            if (rt == null) {
                                // Signed in, but the account runtime is still being assembled (local-only,
                                // no network wait) — or is being swapped for another account.
                                Box(modifier = Modifier.fillMaxSize())
                            } else {
                                // key(rt): a new sign-in is a fresh composition, so no remembered UI state
                                // survives across accounts; the runtime is also the ViewModel store.
                                androidx.compose.runtime.key(rt) {
                                    androidx.compose.runtime.DisposableEffect(rt) {
                                        rt.onUiAttached()
                                        onDispose { rt.onUiDetached() }
                                    }
                                    androidx.compose.runtime.CompositionLocalProvider(
                                        androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides rt,
                                        LocalUnreadNotificationCount provides rt.notificationsRepository.inbox.collectAsStateWithLifecycle().value.unreadCount,
                                    ) {
                                        CinemArchiveApp(
                                            rt,
                                            discoverRepository,
                                            preferencesRepository,
                                            authRepository,
                                            appUpdateRepository,
                                            apkInstaller,
                                            initialTitleId = initialTitleId.takeIf { initialTitleOwnerId == rt.ownerId },
                                            appVersionName = BuildConfig.VERSION_NAME,
                                            isDebugBuild = isDebugBuild,
                                             onOpenSharedLink = { sharedToken = it },
                                        )
                                    }
                                }
                            }
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

private typealias Tab = work.kumarfamilynet.cinemarchive.core.model.NavigationDestination

private sealed interface Overlay {
    data class Detail(val titleId: String, val initialSchedule: Boolean = false) : Overlay

    /** [preselected] is set when the add was started from a specific Discover result rather
     *  than the FAB, so the overlay opens on its log step instead of an empty search box.
     *  [openKey] is generated per opening and scopes the overlay's ViewModel to *this* add —
     *  see AddTitleOverlayRoute's kdoc for what it reuses otherwise. */
    data class Add(
        val preselected: MediaSearchResult? = null,
        val openKey: String = java.util.UUID.randomUUID().toString(),
    ) : Overlay
    data object Profile : Overlay
    data object Identity : Overlay
    data object Invites : Overlay
    data object Notifications : Overlay
    data object Sharing : Overlay
    data object Friends : Overlay
    data class FriendLibrary(val friendUserId: String, val label: String) : Overlay
    data object Appearance : Overlay
    data object Navigation : Overlay
    data object ImportSync : Overlay
    data object About : Overlay
    data object Permissions : Overlay
    data object DeveloperSettings : Overlay

    /** The "at the theater" screen (seat + ticket QR code) — carries the outing and title name
     *  by value, like [Add]'s [preselected], rather than an ID to re-fetch: the marquee card
     *  that opens this already has both in memory. */
    data class Ticket(val outingId: String, val titleName: String) : Overlay
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
    runtime: AppAccountRuntime,
    discoverRepository: DiscoverRepository,
    preferencesRepository: PreferencesRepository,
    authRepository: AuthRepository,
    appUpdateRepository: AppUpdateRepository,
    apkInstaller: ApkInstaller,
    initialTitleId: String? = null,
    appVersionName: String,
    isDebugBuild: Boolean,
    onOpenSharedLink: (String) -> Unit,
) {
    val repository = runtime.libraryRepository
    val ledgerRepository = runtime.ledgerRepository
    val ledgerLayoutRepository = runtime.ledgerLayoutRepository
    val outingsRepository = runtime.outingsRepository
    val listsRepository = runtime.listsRepository
    val librarySyncRepository = runtime.librarySyncRepository
    val syncServices = runtime.syncServices
    // Both owner and sign-in generation fence saved person labels and every Library filter.
    val libraryFiltersState = rememberAccountLibraryFilters("${runtime.ownerId}:${runtime.identity.generation}")
    var tab by remember { mutableStateOf(Tab.LIBRARY) }
    var overlay by remember { mutableStateOf<Overlay?>(initialTitleId?.let { Overlay.Detail(it) }) }
    var recommendTitleId by remember { mutableStateOf<String?>(null) }
    var shareOutingId by remember { mutableStateOf<String?>(null) }
    var globalSearch by remember(runtime) { mutableStateOf(false) }
    val commandTitlesFlow = remember(repository) { repository.observeLibrary() }
    val commandTitles by commandTitlesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val commandFocus = remember(runtime) { FocusRequester() }
    val titleSocialSource = remember(runtime) {
        work.kumarfamilynet.cinemarchive.feature.friends.RepositoryTitleSocialSource(runtime.friendsRepository) {
            authRepository.observeIdentity().value == runtime.identity
        }
    }
    recommendTitleId?.let { id ->
        TitleRecommendationDialog(repository, titleSocialSource, runtime.ownerId, id, onDismiss = { recommendTitleId = null })
    }
    shareOutingId?.let { id ->
        work.kumarfamilynet.cinemarchive.feature.friends.OutingPlansDialog(runtime.outingPlansRepository, id, onDismiss = { shareOutingId = null })
    }
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
    val navigationFlow = remember(preferencesRepository) { preferencesRepository.observeNavigation() }
    val navigationPreferences by navigationFlow.collectAsStateWithLifecycle(
        initialValue = work.kumarfamilynet.cinemarchive.core.model.NavigationPreferences())

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
    val accountRepository = runtime.accountRepository
    val notificationsRepository = runtime.notificationsRepository
    val accountProfile by accountRepository.profile.collectAsStateWithLifecycle()
    val conflictContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(runtime) {
        runtime.conflicts.collect {
            android.widget.Toast.makeText(
                conflictContext,
                "A newer change from another device replaced one of your edits; your library now matches it.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
    // Account isolation: drop the previous user's cached identity/inbox the moment the signed-in
    // user changes (or signs out), then poll the unread badge while this shell is on screen —
    // web polls every 45s because the inbox has no realtime subscription.
    val pollOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(session?.userId) {
        accountRepository.onSessionChanged(session?.userId)
        notificationsRepository.onSessionChanged(session?.userId)
        if (session != null) {
            // STARTED-gated: refreshes once on every resume, never polls while backgrounded.
            pollOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (true) {
                    notificationsRepository.refreshUnreadCount()
                    delay(NotificationRules.POLL_INTERVAL_MS)
                }
            }
        }
    }
    val profileInitial = remember(session?.email, accountProfile) {
        (accountProfile?.displayName?.takeIf { it.isNotBlank() } ?: accountProfile?.username?.takeIf { it.isNotBlank() })
            ?.firstOrNull()?.uppercaseChar()?.toString()
            ?: profileInitial(session?.email)
    }

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
    if (globalSearch && runtime.isCurrent()) OwnerGlobalSearch(
        "${runtime.ownerId}:${runtime.identity.generation}", commandTitles,
        onDismiss = { globalSearch = false },
        onTitle = { id -> if (runtime.isCurrent()) { globalSearch = false; overlay = Overlay.Detail(id) } },
        onSchedule = { id -> if (runtime.isCurrent()) { globalSearch = false; overlay = Overlay.Detail(id, initialSchedule = true) } },
        onCommand = { id -> if (runtime.isCurrent()) {
            globalSearch = false
            when (id) {
                "add" -> overlay = Overlay.Add()
                "profile" -> overlay = Overlay.Profile
                "friends" -> overlay = Overlay.Friends
                else -> {
                    overlay = null
                    tab = when (id) {
                        "upnext", "marquee" -> Tab.UP_NEXT
                        "ledger" -> Tab.LEDGER
                        "discover" -> Tab.DISCOVER
                        "lists" -> Tab.LISTS
                        else -> Tab.LIBRARY
                    }
                    if (id == "grid" || id == "list") coroutineScope.launch {
                        if (runtime.isCurrent()) preferencesRepository.setLibraryViewMode(
                            if (id == "grid") LibraryViewMode.GRID else LibraryViewMode.LIST)
                    }
                }
            }
        } },
    )
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
                Overlay.Identity, Overlay.Invites, Overlay.Notifications, Overlay.Friends,
                Overlay.Appearance, Overlay.ImportSync, Overlay.About, Overlay.Permissions, Overlay.DeveloperSettings -> Overlay.Profile
                is Overlay.FriendLibrary -> Overlay.Friends
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
    Box(modifier = Modifier.fillMaxSize().statusBarsPadding().onPreviewKeyEvent {
        if (it.opensGlobalSearch() && runtime.isCurrent()) { globalSearch = true; true } else false
    }.focusRequester(commandFocus).focusable()) {
        LaunchedEffect(runtime) { commandFocus.requestFocus() }
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Below Medium, nav stays a bottom bar as before. At/above it — an unfolded
            // foldable, a tablet — a bottom bar stretched across the full width reads as a
            // phone control blown up rather than adapted, so nav moves to a leading-edge rail.
            val useNavigationRail = maxWidth >= MediumWindowBreakpoint
            val allNavDestinations = listOf(
                NavDestination(Tab.DISCOVER, "Discover", Icons.Outlined.Explore, Icons.Filled.Explore),
                NavDestination(
                    Tab.LIBRARY,
                    "Library",
                    icon = if (libraryViewMode == LibraryViewMode.GRID) Icons.Outlined.Apps else Icons.AutoMirrored.Outlined.ViewList,
                    selectedIcon = if (libraryViewMode == LibraryViewMode.GRID) Icons.Filled.Apps else Icons.AutoMirrored.Filled.ViewList,
                ),
                NavDestination(Tab.UP_NEXT, "Up Next", Icons.Outlined.PlayArrow, Icons.Filled.PlayArrow),
                NavDestination(Tab.LEDGER, "Ledger", Icons.Outlined.Insights, Icons.Filled.Insights),
                // Bookmarks, not the ViewList glyph Library swaps to in list-view-mode above —
                // the two tabs sitting side by side with the same icon would be confusing.
                NavDestination(Tab.LISTS, "Lists", Icons.Outlined.Bookmarks, Icons.Filled.Bookmarks),
            )
            val navDestinations: List<NavDestination<Tab?>> = navigationPreferences.visible.map { destination ->
                val item = allNavDestinations.first { it.value == destination }
                NavDestination<Tab?>(item.value, item.label, item.icon, item.selectedIcon)
            } + NavDestination<Tab?>(null, "Search", Icons.Outlined.Search)

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
                                filtersState = libraryFiltersState,
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
                                onRecommendTitle = { recommendTitleId = it },
                                onOpenProfile = openProfile,
                                profileInitial = profileInitial,
                                onTitleClick = { overlay = Overlay.Detail(it) },
                                onViewTicket = { overlay = Overlay.Ticket(it.outing.id, it.titleName) },
                                onFabExpandedChange = { fabExpanded = it },
                            )
                            Tab.LEDGER -> LedgerRoute(
                                ledgerRepository,
                                ledgerLayoutRepository,
                                onTitleClick = { overlay = Overlay.Detail(it) },
                                onOpenProfile = openProfile,
                                profileInitial = profileInitial,
                                isWideLayout = useNavigationRail,
                            )
                            Tab.LISTS -> ListsRoute(
                                listsRepository,
                                repository,
                                onTitleClick = { overlay = Overlay.Detail(it) },
                                onOpenProfile = openProfile,
                                profileInitial = profileInitial,
                                onFabExpandedChange = { fabExpanded = it },
                            )
                        }
                    }

                    if (tab != Tab.LEDGER && tab != Tab.DISCOVER && tab != Tab.LISTS) {
                        ExpressivePillFab(
                            label = "New Title",
                            expanded = fabExpanded,
                            onClick = { overlay = Overlay.Add() },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = innerPadding.calculateBottomPadding() + 16.dp),
                        )
                    }
                    // Lists has its own "+ New list" entry points inline (ListsRoute's header
                    // button and empty state) rather than a shared FAB — unlike "New Title",
                    // creating a list needs only a name, not a multi-step add flow, so a
                    // dedicated FAB here would be a second, redundant affordance for the same
                    // one-field action.
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
                        onSelect = { if (it == null) globalSearch = true else tab = it },
                        compact = navigationPreferences.compact,
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
                            onSelect = { if (it == null) globalSearch = true else tab = it },
                            compact = navigationPreferences.compact,
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
                Overlay.Identity -> SettingsCategory.IDENTITY
                Overlay.Invites -> SettingsCategory.INVITES
                Overlay.Notifications -> SettingsCategory.NOTIFICATIONS
                Overlay.Sharing -> SettingsCategory.SHARING
                Overlay.Appearance -> SettingsCategory.APPEARANCE
                Overlay.Navigation -> SettingsCategory.NAVIGATION
                Overlay.ImportSync -> SettingsCategory.IMPORT_SYNC
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
                            accountRepository,
                            notificationsRepository,
                            appVersionName,
                            onClose = closeOverlay,
                            onOpenIdentity = { selectedSettingsCategory = SettingsCategory.IDENTITY },
                            onOpenInvites = { selectedSettingsCategory = SettingsCategory.INVITES },
                            onOpenNotifications = { selectedSettingsCategory = SettingsCategory.NOTIFICATIONS },
                            onOpenFriends = { overlay = Overlay.Friends },
                            onOpenSharing = { selectedSettingsCategory = SettingsCategory.SHARING },
                            onOpenAppearance = { selectedSettingsCategory = SettingsCategory.APPEARANCE },
                            onOpenNavigation = { selectedSettingsCategory = SettingsCategory.NAVIGATION },
                            onOpenImportSync = { selectedSettingsCategory = SettingsCategory.IMPORT_SYNC },
                            onOpenAbout = { selectedSettingsCategory = SettingsCategory.ABOUT },
                            onOpenPermissions = { selectedSettingsCategory = SettingsCategory.PERMISSIONS },
                            devSettingsUnlocked = devSettingsUnlocked,
                            onOpenDeveloperSettings = { selectedSettingsCategory = SettingsCategory.DEVELOPER },
                            legacyLoadStatus = runtime::legacyStatus,
                            legacyRestore = runtime::restoreLegacy,
                            outingRecovery = { work.kumarfamilynet.cinemarchive.feature.settings.OutingRecoverySection(runtime.outingRecoveryRepository) },
                            lifecycleChangesContent = { work.kumarfamilynet.cinemarchive.feature.settings.OutingRecoverySection(runtime.outingLifecycleRecovery,
                                subject = work.kumarfamilynet.cinemarchive.feature.settings.RecoverySubject.LIFECYCLE) },
                            listChangesContent = { work.kumarfamilynet.cinemarchive.feature.settings.OutingRecoverySection(runtime.listMembershipRecovery,
                                subject = work.kumarfamilynet.cinemarchive.feature.settings.RecoverySubject.LIST) },
                            titleChangesContent = {
                        work.kumarfamilynet.cinemarchive.feature.library.TitleMetadataRecoveryPanel(runtime.titleMetadataRepository)
                        work.kumarfamilynet.cinemarchive.feature.library.EpisodeBulkRecoveryPanel(runtime.episodeBulkRepository)
                    },
                            ticketChangesContent = { work.kumarfamilynet.cinemarchive.feature.library.SavedTicketsSection(runtime.tickets) { id, title -> overlay = Overlay.Ticket(id, title) } },
                            moviegoingContent = { work.kumarfamilynet.cinemarchive.feature.settings.MoviegoingPreferencesPanel(runtime.moviegoingPreferences) { venue, dismiss ->
                                work.kumarfamilynet.cinemarchive.feature.library.VenueNoteEditor(runtime.moviegoingPreferences, venue, dismiss)
                            } },
                            viewingChangesContent = { work.kumarfamilynet.cinemarchive.feature.settings.OutingRecoverySection(runtime.viewingRecoveryRepository,
                                subject = work.kumarfamilynet.cinemarchive.feature.settings.RecoverySubject.VIEWING) },
                            selectedCategory = activeCategory,
                        )
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(modifier = Modifier.weight(1f)) {
                        // showBack = false: this pane has no "back" of its own to unwind — the
                        // list pane opposite it is the only way out, via its own close button.
                        when (activeCategory) {
                            SettingsCategory.IDENTITY -> IdentityRoute(accountRepository, onBack = closeOverlay, showBack = false)
                            SettingsCategory.INVITES -> InvitesRoute(accountRepository, onBack = closeOverlay, showBack = false)
                            SettingsCategory.SHARING -> SharingSettings(runtime, onBack = closeOverlay, onOpenSharedLink = onOpenSharedLink, showBack = false)
                            SettingsCategory.NOTIFICATIONS -> NotificationsRoute(
                                notificationsRepository,
                                onBack = closeOverlay,
                                onOpenTitle = { overlay = Overlay.Detail(it) },
                                onOpenProfile = { selectedSettingsCategory = SettingsCategory.IDENTITY },
                                onOpenFriends = { overlay = Overlay.Friends },
                                showBack = false,
                            )
                            SettingsCategory.APPEARANCE -> AppearanceRoute(preferencesRepository, onBack = closeOverlay, showBack = false)
                            SettingsCategory.NAVIGATION -> work.kumarfamilynet.cinemarchive.feature.settings.NavigationSettingsRoute(preferencesRepository, onBack = closeOverlay, showBack = false)
                            SettingsCategory.IMPORT_SYNC -> ImportSyncRoute(syncServices, onBack = closeOverlay, showBack = false,
                                backupContent = { Column {
                                    work.kumarfamilynet.cinemarchive.feature.settings.LibraryBackupSection(runtime.backupRepository)
                                    work.kumarfamilynet.cinemarchive.feature.settings.LibraryRestoreSection(runtime.restoreRepository)
                                } })
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
                    listsRepository,
                    current.titleId,
                    onBack = closeOverlay,
                    initialSchedule = current.initialSchedule,
                    onInitialScheduleConsumed = { if (overlay == current) overlay = current.copy(initialSchedule = false) },
                    onScheduled = runtime::syncTickets,
                    onRequestNotificationPermission = requestNotificationPermission,
                    onRecommendTitle = { recommendTitleId = it },
                    onShareOutingPlans = { shareOutingId = it },
                    onViewTicket = { outing, title -> overlay = Overlay.Ticket(outing.id, title) },
                    onBrowsePerson = { person ->
                        libraryFiltersState.value = libraryFiltersState.value.copy(person = person)
                        overlay = null
                        tab = Tab.LIBRARY
                    },
                    onRefreshCredits = { runtime.creditRefreshRepository.refresh(current.titleId) },
                    catalogExtrasSource = runtime.catalogExtrasRepository,
                    titleMetadataRecovery = runtime.titleMetadataRepository,
                    socialContent = { detail ->
                        work.kumarfamilynet.cinemarchive.feature.friends.OwnerTitleSocial(titleSocialSource, runtime.ownerId, detail)
                    },
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
                    accountRepository,
                    notificationsRepository,
                    appVersionName,
                    onClose = closeOverlay,
                    onOpenIdentity = { overlay = Overlay.Identity },
                    onOpenInvites = { overlay = Overlay.Invites },
                    onOpenNotifications = { overlay = Overlay.Notifications },
                    onOpenFriends = { overlay = Overlay.Friends },
                    onOpenSharing = { overlay = Overlay.Sharing },
                    onOpenAppearance = { overlay = Overlay.Appearance },
                    onOpenNavigation = { overlay = Overlay.Navigation },
                    onOpenImportSync = { overlay = Overlay.ImportSync },
                    onOpenAbout = { overlay = Overlay.About },
                    onOpenPermissions = { overlay = Overlay.Permissions },
                    devSettingsUnlocked = devSettingsUnlocked,
                    onOpenDeveloperSettings = { overlay = Overlay.DeveloperSettings },
                    legacyLoadStatus = runtime::legacyStatus,
                    legacyRestore = runtime::restoreLegacy,
                    outingRecovery = { work.kumarfamilynet.cinemarchive.feature.settings.OutingRecoverySection(runtime.outingRecoveryRepository) },
                    lifecycleChangesContent = { work.kumarfamilynet.cinemarchive.feature.settings.OutingRecoverySection(runtime.outingLifecycleRecovery,
                        subject = work.kumarfamilynet.cinemarchive.feature.settings.RecoverySubject.LIFECYCLE) },
                            listChangesContent = { work.kumarfamilynet.cinemarchive.feature.settings.OutingRecoverySection(runtime.listMembershipRecovery,
                                subject = work.kumarfamilynet.cinemarchive.feature.settings.RecoverySubject.LIST) },
                    titleChangesContent = {
                        work.kumarfamilynet.cinemarchive.feature.library.TitleMetadataRecoveryPanel(runtime.titleMetadataRepository)
                        work.kumarfamilynet.cinemarchive.feature.library.EpisodeBulkRecoveryPanel(runtime.episodeBulkRepository)
                    },
                    ticketChangesContent = { work.kumarfamilynet.cinemarchive.feature.library.SavedTicketsSection(runtime.tickets) { id, title -> overlay = Overlay.Ticket(id, title) } },
                    moviegoingContent = { work.kumarfamilynet.cinemarchive.feature.settings.MoviegoingPreferencesPanel(runtime.moviegoingPreferences) { venue, dismiss ->
                        work.kumarfamilynet.cinemarchive.feature.library.VenueNoteEditor(runtime.moviegoingPreferences, venue, dismiss)
                    } },
                    viewingChangesContent = { work.kumarfamilynet.cinemarchive.feature.settings.OutingRecoverySection(runtime.viewingRecoveryRepository,
                        subject = work.kumarfamilynet.cinemarchive.feature.settings.RecoverySubject.VIEWING) },
                )
                Overlay.Identity -> IdentityRoute(accountRepository, onBack = openProfile)
                Overlay.Invites -> InvitesRoute(accountRepository, onBack = openProfile)
                Overlay.Sharing -> SharingSettings(runtime, onBack = openProfile, onOpenSharedLink = onOpenSharedLink)
                Overlay.Notifications -> NotificationsRoute(
                    notificationsRepository,
                    onBack = openProfile,
                    onOpenTitle = { overlay = Overlay.Detail(it) },
                    onOpenProfile = openProfile,
                    onOpenFriends = { overlay = Overlay.Friends },
                )
                Overlay.Friends -> FriendsWithAccessEditor(
                    runtime, titleSocialSource,
                    onBack = openProfile,
                    onOpenFriendLibrary = { id, label -> overlay = Overlay.FriendLibrary(id, label) },
                )
                is Overlay.FriendLibrary -> FriendLibraryRoute(
                    runtime.friendsRepository,
                    viewerUserId = runtime.ownerId,
                    friendUserId = current.friendUserId,
                    label = current.label,
                    socialSource = titleSocialSource,
                    onBack = { overlay = Overlay.Friends },
                )
                Overlay.Appearance -> AppearanceRoute(preferencesRepository, onBack = openProfile)
                Overlay.Navigation -> work.kumarfamilynet.cinemarchive.feature.settings.NavigationSettingsRoute(preferencesRepository, onBack = openProfile)
                Overlay.ImportSync -> ImportSyncRoute(syncServices, onBack = openProfile,
                    backupContent = { Column {
                        work.kumarfamilynet.cinemarchive.feature.settings.LibraryBackupSection(runtime.backupRepository)
                        work.kumarfamilynet.cinemarchive.feature.settings.LibraryRestoreSection(runtime.restoreRepository)
                    } })
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
                is Overlay.Ticket -> work.kumarfamilynet.cinemarchive.feature.library.PortableTicketRoute(
                    runtime.tickets, current.outingId, current.titleName, onBack = closeOverlay, onSaved = runtime::syncTickets,
                )
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

@Composable
private fun FriendsWithAccessEditor(
    runtime: AppAccountRuntime,
    socialSource: work.kumarfamilynet.cinemarchive.feature.friends.TitleSocialSource,
    onBack: () -> Unit,
    onOpenFriendLibrary: (String, String) -> Unit,
) {
    key(runtime) {
        val titles by runtime.libraryRepository.observeLibrary().collectAsStateWithLifecycle(initialValue = emptyList())
        val source = remember(runtime, socialSource) {
            work.kumarfamilynet.cinemarchive.feature.settings.RepositoryShareScopeSource(
                runtime.sharingRepository, socialSource::isActive)
        }
        var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
        FriendsRoute(runtime.friendsRepository, runtime.ownerId, onBack, onOpenFriendLibrary,
            onEditFriendAccess = { id, label -> editing = id to label })
        editing?.let { (id, label) ->
            work.kumarfamilynet.cinemarchive.feature.settings.ShareScopeEditorDialog(
                source, work.kumarfamilynet.cinemarchive.data.ShareScopeTarget.Friend(id), label,
                titles.flatMap { it.genres }.distinct().sorted(), onClose = { editing = null })
        }
    }
}

@Composable
private fun SharingSettings(runtime: AppAccountRuntime, onBack: () -> Unit, onOpenSharedLink: (String) -> Unit, showBack: Boolean = true) {
    val titles by runtime.libraryRepository.observeLibrary().collectAsStateWithLifecycle(initialValue = emptyList())
    SharingRoute(runtime.sharingRepository, availableGenres = titles.flatMap { it.genres }.distinct().sorted(),
        onBack = onBack, showBack = showBack, onOpenSharedLink = onOpenSharedLink)
}
