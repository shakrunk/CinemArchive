package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.ReadingWidthColumn
import work.kumarfamilynet.cinemarchive.core.model.ApkInstallState
import work.kumarfamilynet.cinemarchive.core.model.InstallSource
import work.kumarfamilynet.cinemarchive.core.model.UpdateCheckResult
import work.kumarfamilynet.cinemarchive.data.ApkInstaller
import work.kumarfamilynet.cinemarchive.data.AppUpdateRepository
import work.kumarfamilynet.cinemarchive.data.PreferencesRepository

private data class LegalDoc(val title: String, val body: String)

// Placeholder copy — not yet reviewed as real legal text. Replace before any wider release.
private val LEGAL_DOCS = listOf(
    LegalDoc(
        title = "Privacy Policy",
        body = "Placeholder — a full Privacy Policy hasn't been written yet. In short: your " +
            "library data lives in Supabase (Postgres) under your account behind Row Level " +
            "Security; TMDB/OMDb metadata lookups are proxied through a server-side Edge " +
            "Function so those API keys never reach this app; any read-only link you generate " +
            "to share your library is time-bound and revocable. This section will be replaced " +
            "with a complete policy before the app is offered more broadly.",
    ),
    LegalDoc(
        title = "Terms of Service & EULA",
        body = "Placeholder — CinemArchive doesn't have a finalized Terms of Service yet. " +
            "It's a personal project, provided as-is with no warranty, for tracking your own " +
            "film and TV viewing. This section will be replaced with real terms before the app " +
            "is offered more broadly.",
    ),
)

private sealed interface AboutSubpage {
    data class Doc(val doc: LegalDoc) : AboutSubpage
    data object Credits : AboutSubpage
}

private data class CreditEntry(
    val name: String,
    val detail: String,
    val license: String,
    val url: String,
)

private data class CreditSection(val title: String, val entries: List<CreditEntry>)

// Sourced from apps/android/gradle/libs.versions.toml and CinemArchiveTypography.kt — keep in
// sync when a dependency or Google Font is added, swapped, or dropped.
private val CREDIT_SECTIONS = listOf(
    CreditSection(
        title = "Data & Media",
        entries = listOf(
            CreditEntry(
                name = "TMDB",
                detail = "Movie & TV metadata, artwork, and posters. This product uses the " +
                    "TMDB API but is not endorsed or certified by TMDB.",
                license = "TMDB API Terms of Use",
                url = "https://www.themoviedb.org/",
            ),
            CreditEntry(
                name = "OMDb API",
                detail = "Critic and audience scores.",
                license = "OMDb API Terms of Use",
                url = "https://www.omdbapi.com/",
            ),
        ),
    ),
    CreditSection(
        title = "Fonts",
        entries = listOf(
            CreditEntry(
                name = "Fraunces",
                detail = "Display/heading typeface. Delivered at runtime via the Google Play " +
                    "services Downloadable Fonts API.",
                license = "SIL Open Font License 1.1",
                url = "https://fonts.google.com/specimen/Fraunces",
            ),
            CreditEntry(
                name = "Hanken Grotesk",
                detail = "UI and body typeface.",
                license = "SIL Open Font License 1.1",
                url = "https://fonts.google.com/specimen/Hanken+Grotesk",
            ),
            CreditEntry(
                name = "DM Mono",
                detail = "Tabular/stat typeface.",
                license = "SIL Open Font License 1.1",
                url = "https://fonts.google.com/specimen/DM+Mono",
            ),
            CreditEntry(
                name = "Lexend",
                detail = "Dyslexia-friendly accessibility typeface.",
                license = "SIL Open Font License 1.1",
                url = "https://fonts.google.com/specimen/Lexend",
            ),
        ),
    ),
    CreditSection(
        title = "Open Source Libraries",
        entries = listOf(
            CreditEntry(
                name = "Kotlin",
                detail = "Language and standard library.",
                license = "Apache License 2.0",
                url = "https://github.com/JetBrains/kotlin",
            ),
            CreditEntry(
                name = "Kotlin Coroutines",
                detail = "Asynchronous and concurrent code.",
                license = "Apache License 2.0",
                url = "https://github.com/Kotlin/kotlinx.coroutines",
            ),
            CreditEntry(
                name = "Jetpack Compose & AndroidX",
                detail = "UI toolkit, Activity, Lifecycle, Core, and DataStore.",
                license = "Apache License 2.0",
                url = "https://developer.android.com/jetpack",
            ),
            CreditEntry(
                name = "Material Components for Android",
                detail = "Material 3 design system components.",
                license = "Apache License 2.0",
                url = "https://github.com/material-components/material-components-android",
            ),
            CreditEntry(
                name = "AndroidX Room",
                detail = "Local SQLite persistence.",
                license = "Apache License 2.0",
                url = "https://developer.android.com/jetpack/androidx/releases/room",
            ),
            CreditEntry(
                name = "AndroidX Security Crypto",
                detail = "Encrypted local storage.",
                license = "Apache License 2.0",
                url = "https://developer.android.com/jetpack/androidx/releases/security",
            ),
            CreditEntry(
                name = "AndroidX CameraX",
                detail = "Camera capture for invite QR-code scanning.",
                license = "Apache License 2.0",
                url = "https://developer.android.com/jetpack/androidx/releases/camera",
            ),
            CreditEntry(
                name = "AndroidX Graphics Shapes",
                detail = "Expressive shape morphing.",
                license = "Apache License 2.0",
                url = "https://developer.android.com/jetpack/androidx/releases/graphics",
            ),
            CreditEntry(
                name = "Coil",
                detail = "Image loading.",
                license = "Apache License 2.0",
                url = "https://github.com/coil-kt/coil",
            ),
            CreditEntry(
                name = "OkHttp",
                detail = "HTTP client.",
                license = "Apache License 2.0",
                url = "https://github.com/square/okhttp",
            ),
            CreditEntry(
                name = "JSON-java (org.json)",
                detail = "JSON parsing.",
                license = "The JSON License",
                url = "https://github.com/stleary/JSON-java",
            ),
        ),
    ),
    CreditSection(
        title = "Google Services",
        entries = listOf(
            CreditEntry(
                name = "Google ML Kit — Barcode Scanning",
                detail = "On-device QR-code recognition for invite redemption. Proprietary — " +
                    "not open source.",
                license = "Google APIs Terms of Service",
                url = "https://developers.google.com/ml-kit/vision/barcode-scanning",
            ),
        ),
    ),
)

@Composable
fun AboutRoute(
    appVersionName: String,
    appUpdateRepository: AppUpdateRepository,
    apkInstaller: ApkInstaller,
    preferencesRepository: PreferencesRepository,
    onBack: () -> Unit,
    // False in the wide/split settings layout, where this screen sits permanently in the
    // trailing pane rather than having been navigated to. Only applies to the top-level list —
    // the Doc/Credits subpages below it are real in-pane navigation (List -> Doc), so they keep
    // their own back arrow regardless.
    showBack: Boolean = true,
) {
    var subpage by remember { mutableStateOf<AboutSubpage?>(null) }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val autoCheck by preferencesRepository.observeAutoCheckUpdates()
        .collectAsStateWithLifecycle(initialValue = true)
    var updateResult by remember { mutableStateOf<UpdateCheckResult>(UpdateCheckResult.Idle) }
    // Owned by ApkInstaller rather than this screen: the decisive transitions arrive on a
    // manifest receiver (InstallStatusReceiver) that outlives this composable.
    val installState by apkInstaller.installState.collectAsStateWithLifecycle()
    // Re-read on each recomposition rather than caching: the user can grant the permission in
    // Settings and come straight back, and a cached "false" would strand them on the fallback.
    val canInstallDirectly = apkInstaller.canRequestInstalls()

    fun runCheck() {
        scope.launch {
            updateResult = UpdateCheckResult.Checking
            updateResult = appUpdateRepository.checkForUpdate()
        }
    }

    // The automatic check the toggle governs. Runs once per entry into About; the manual
    // button below ignores the toggle entirely.
    LaunchedEffect(autoCheck) {
        if (autoCheck && updateResult == UpdateCheckResult.Idle) runCheck()
    }

    BackHandler(enabled = subpage != null) { subpage = null }

    when (val current = subpage) {
        is AboutSubpage.Doc -> AboutDetailScreen(doc = current.doc, onBack = { subpage = null })
        AboutSubpage.Credits -> CreditsScreen(onBack = { subpage = null })
        null -> AboutListScreen(
            appVersionName = appVersionName,
            onBack = onBack,
            showBack = showBack,
            onOpenDoc = { subpage = AboutSubpage.Doc(it) },
            onOpenCredits = { subpage = AboutSubpage.Credits },
            onOpenSource = { uriHandler.openUri("https://github.com/shakrunk/CinemArchive") },
            onOpenReleaseNotes = { uriHandler.openUri("https://github.com/shakrunk/CinemArchive/releases") },
            installSource = appUpdateRepository.installSource,
            autoCheckEnabled = autoCheck,
            onSetAutoCheck = { enabled -> scope.launch { preferencesRepository.setAutoCheckUpdates(enabled) } },
            updateResult = updateResult,
            canInstallDirectly = canInstallDirectly,
            onCheckNow = ::runCheck,
            installState = installState,
            // Failures surface through installState, not updateResult: overwriting the latter
            // would drop the Available result this screen needs to keep offering a retry.
            onInstall = { apkUrl -> scope.launch { apkInstaller.downloadAndInstall(apkUrl) } },
            onOpenReleasePage = { url -> uriHandler.openUri(url) },
            onGrantInstallPermission = {
                apkInstaller.unknownSourcesSettingsIntent()?.let { intent ->
                    context.startActivity(intent)
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutListScreen(
    appVersionName: String,
    onBack: () -> Unit,
    showBack: Boolean = true,
    onOpenDoc: (LegalDoc) -> Unit,
    onOpenCredits: () -> Unit,
    onOpenSource: () -> Unit,
    onOpenReleaseNotes: () -> Unit,
    installSource: InstallSource,
    autoCheckEnabled: Boolean,
    onSetAutoCheck: (Boolean) -> Unit,
    updateResult: UpdateCheckResult,
    canInstallDirectly: Boolean,
    installState: ApkInstallState,
    onCheckNow: () -> Unit,
    onInstall: (String) -> Unit,
    onOpenReleasePage: (String) -> Unit,
    onGrantInstallPermission: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp, 8.dp, 20.dp, 2.dp)) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            Text(
                "About & Legal",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = if (showBack) 4.dp else 0.dp),
            )
        }

        LazyColumn(contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp)) {
            item {
                ReadingWidthColumn {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(16.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                Text("C", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 10.dp))
                            }
                        }
                        Column(modifier = Modifier.padding(start = 14.dp)) {
                            Text("CinemArchive", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Version $appVersionName",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Buttons rather than bare tappable text: these leave the app, and
                    // plain labels gave no affordance that they were actionable at all.
                    // FlowRow, not a weighted Row: at large font scale / narrow widths, a
                    // forced 50/50 split leaves no room for "Releases" and Compose breaks it
                    // mid-word. Sizing buttons to content and letting the second one wrap to
                    // its own full-width line keeps every label on one line.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
                    ) {
                        OutlinedButton(onClick = onOpenSource) {
                            Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Source", modifier = Modifier.padding(start = 8.dp))
                        }
                        OutlinedButton(onClick = onOpenReleaseNotes) {
                            Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Releases", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            item {
                ReadingWidthColumn {
                    UpdatesSection(
                        installSource = installSource,
                        autoCheckEnabled = autoCheckEnabled,
                        onSetAutoCheck = onSetAutoCheck,
                        result = updateResult,
                        canInstallDirectly = canInstallDirectly,
                        installState = installState,
                        onCheckNow = onCheckNow,
                        onInstall = onInstall,
                        onOpenReleasePage = onOpenReleasePage,
                        onGrantInstallPermission = onGrantInstallPermission,
                        modifier = Modifier.padding(bottom = 22.dp),
                    )
                }
            }

            item {
                ReadingWidthColumn {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        LEGAL_DOCS.forEach { doc ->
                            LegalRow(title = doc.title, onClick = { onOpenDoc(doc) })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        LegalRow(title = "Credits & Open Source Licenses", onClick = onOpenCredits)
                    }
                    Text(
                        "© 2026 CinemArchive · Not endorsed by TMDB or OMDb",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 18.dp).fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutDetailScreen(doc: LegalDoc, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp, 8.dp, 20.dp, 2.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(doc.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
        }
        ReadingWidthColumn {
            Text(
                doc.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 28.dp),
            )
        }
    }
}

@Composable
private fun LegalRow(title: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CreditsScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp, 8.dp, 20.dp, 2.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Credits & Open Source Licenses",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        LazyColumn(contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp)) {
            item {
                ReadingWidthColumn {
                    Text(
                        "CinemArchive is built on the following data providers, type families, " +
                            "and open-source software. Tap an entry to open its source or license " +
                            "page.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 20.dp),
                    )
                }
            }
            CREDIT_SECTIONS.forEachIndexed { sectionIndex, section ->
                item {
                    ReadingWidthColumn {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
                item {
                    ReadingWidthColumn {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer),
                        ) {
                            section.entries.forEachIndexed { index, entry ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { uriHandler.openUri(entry.url) }
                                        .padding(16.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            entry.detail,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                        Text(
                                            entry.license,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = "Open link",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 12.dp).size(18.dp),
                                    )
                                }
                                if (index < section.entries.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                }
                if (sectionIndex < CREDIT_SECTIONS.lastIndex) {
                    item { Spacer(modifier = Modifier.height(20.dp)) }
                }
            }
        }
    }
}
