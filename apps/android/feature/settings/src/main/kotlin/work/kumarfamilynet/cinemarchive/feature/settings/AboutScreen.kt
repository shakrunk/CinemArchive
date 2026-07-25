package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
    LegalDoc(
        title = "Credits & Open Source Licenses",
        body = "Metadata & posters — TMDB\nCritic scores — OMDb\n\nThis app is built with " +
            "Jetpack Compose, Room, Coil and other open-source packages, each under its " +
            "respective license (MIT/Apache-2.0). This product uses the TMDB API but is not " +
            "endorsed or certified by TMDB.",
    ),
)

@Composable
fun AboutRoute(
    appVersionName: String,
    appUpdateRepository: AppUpdateRepository,
    apkInstaller: ApkInstaller,
    preferencesRepository: PreferencesRepository,
    onBack: () -> Unit,
) {
    var subpage by remember { mutableStateOf<LegalDoc?>(null) }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val autoCheck by preferencesRepository.observeAutoCheckUpdates()
        .collectAsStateWithLifecycle(initialValue = true)
    var updateResult by remember { mutableStateOf<UpdateCheckResult>(UpdateCheckResult.Idle) }
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

    if (subpage != null) {
        AboutDetailScreen(doc = subpage!!, onBack = { subpage = null })
    } else {
        AboutListScreen(
            appVersionName = appVersionName,
            onBack = onBack,
            onOpenDoc = { subpage = it },
            onOpenSource = { uriHandler.openUri("https://github.com/shakrunk/CinemArchive") },
            onOpenReleaseNotes = { uriHandler.openUri("https://github.com/shakrunk/CinemArchive/releases") },
            installSource = appUpdateRepository.installSource,
            autoCheckEnabled = autoCheck,
            onSetAutoCheck = { enabled -> scope.launch { preferencesRepository.setAutoCheckUpdates(enabled) } },
            updateResult = updateResult,
            canInstallDirectly = canInstallDirectly,
            onCheckNow = ::runCheck,
            onInstall = { apkUrl ->
                scope.launch {
                    apkInstaller.downloadAndInstall(apkUrl).onFailure { e ->
                        updateResult = UpdateCheckResult.Failed(e.message ?: "Install failed")
                    }
                }
            },
            onOpenReleasePage = { url -> uriHandler.openUri(url) },
            onGrantInstallPermission = {
                apkInstaller.unknownSourcesSettingsIntent()?.let { intent ->
                    context.startActivity(intent)
                }
            },
        )
    }
}

@Composable
private fun AboutListScreen(
    appVersionName: String,
    onBack: () -> Unit,
    onOpenDoc: (LegalDoc) -> Unit,
    onOpenSource: () -> Unit,
    onOpenReleaseNotes: () -> Unit,
    installSource: InstallSource,
    autoCheckEnabled: Boolean,
    onSetAutoCheck: (Boolean) -> Unit,
    updateResult: UpdateCheckResult,
    canInstallDirectly: Boolean,
    onCheckNow: () -> Unit,
    onInstall: (String) -> Unit,
    onOpenReleasePage: (String) -> Unit,
    onGrantInstallPermission: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp, 8.dp, 20.dp, 2.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("About & Legal", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
        }

        LazyColumn(contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp)) {
            item {
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp, bottom = 18.dp)) {
                    OutlinedButton(onClick = onOpenSource, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Source", modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = onOpenReleaseNotes, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Releases", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            item {
                UpdatesSection(
                    installSource = installSource,
                    autoCheckEnabled = autoCheckEnabled,
                    onSetAutoCheck = onSetAutoCheck,
                    result = updateResult,
                    canInstallDirectly = canInstallDirectly,
                    onCheckNow = onCheckNow,
                    onInstall = onInstall,
                    onOpenReleasePage = onOpenReleasePage,
                    onGrantInstallPermission = onGrantInstallPermission,
                    modifier = Modifier.padding(bottom = 22.dp),
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    LEGAL_DOCS.forEachIndexed { index, doc ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenDoc(doc) }
                                .padding(16.dp),
                        ) {
                            Text(doc.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (index < LEGAL_DOCS.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
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

@Composable
private fun AboutDetailScreen(doc: LegalDoc, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp, 8.dp, 20.dp, 2.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(doc.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
        }
        Text(
            doc.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 28.dp),
        )
    }
}
