package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.ReadingWidthColumn
import work.kumarfamilynet.cinemarchive.core.designsystem.groupedItemShape
import work.kumarfamilynet.cinemarchive.data.PreferencesRepository

/**
 * Settings → Developer settings. Reachable only once unlocked — [AboutRoute]'s version-number
 * tap handler flips [PreferencesRepository.setDevSettingsUnlocked] to `true`, and this row's
 * visibility in [ProfileScreen] follows [PreferencesRepository.observeDevSettingsUnlocked]
 * directly. Debug builds start unlocked, release builds start locked, per the [isDebugBuild]
 * default passed through from `BuildConfig.DEBUG` at the call site (`:app`, the only module
 * with a `BuildConfig`).
 */
@Composable
fun DeveloperSettingsRoute(
    preferencesRepository: PreferencesRepository,
    appVersionName: String,
    isDebugBuild: Boolean,
    onBack: () -> Unit,
    onLock: () -> Unit,
    // False in the wide/split settings layout, where this screen sits permanently in the
    // trailing pane rather than having been navigated to — a back arrow there would point at
    // nothing to go back to.
    showBack: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    // Same defaultEnabled-per-build-type shape as observeDevSettingsUnlocked; remember(isDebugBuild)
    // keeps the Flow instance stable across recompositions instead of restarting the collection
    // every time this composable's caller recomposes for an unrelated reason.
    val showBuildBannerFlow = remember(isDebugBuild) { preferencesRepository.observeDevShowBuildBanner(isDebugBuild) }
    val showBuildBanner by showBuildBannerFlow.collectAsStateWithLifecycle(initialValue = isDebugBuild)

    DeveloperSettingsScreen(
        appVersionName = appVersionName,
        isDebugBuild = isDebugBuild,
        showBack = showBack,
        onBack = onBack,
        showBuildBanner = showBuildBanner,
        onSetShowBuildBanner = { enabled -> scope.launch { preferencesRepository.setDevShowBuildBanner(enabled) } },
        onLock = onLock,
    )
}

@Composable
private fun DeveloperSettingsScreen(
    appVersionName: String,
    isDebugBuild: Boolean,
    showBack: Boolean,
    onBack: () -> Unit,
    showBuildBanner: Boolean,
    onSetShowBuildBanner: (Boolean) -> Unit,
    onLock: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp, 8.dp, 20.dp, 2.dp)) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            Text(
                "Developer Settings",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = if (showBack) 4.dp else 0.dp),
            )
        }

        // weight(1f): a size-less LazyColumn wrap-content-sizes to its rows, leaving the rest of
        // the screen a plain Column that doesn't own touch — a drag there fell through to
        // whatever's underneath this overlay instead of being absorbed as a no-op scroll.
        LazyColumn(contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp), modifier = Modifier.weight(1f)) {
            item {
                ReadingWidthColumn {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
                            .padding(16.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isDebugBuild) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                            contentColor = if (isDebugBuild) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.DeveloperMode, contentDescription = null, modifier = Modifier.size(24.dp))
                            }
                        }
                        Column(modifier = Modifier.padding(start = 14.dp)) {
                            Text(if (isDebugBuild) "Debug build" else "Release build", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Version $appVersionName",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                ReadingWidthColumn(modifier = Modifier.padding(top = 22.dp)) {
                    Text(
                        "OPTIONS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                    Surface(
                        shape = groupedItemShape(isFirst = true, isLast = true),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Show build indicator", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Displays a permanent \"Debug\"/\"Release\" badge over the app",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = showBuildBanner, onCheckedChange = onSetShowBuildBanner)
                        }
                    }
                }
            }

            item {
                ReadingWidthColumn(modifier = Modifier.padding(top = 22.dp)) {
                    OutlinedButton(onClick = onLock, modifier = Modifier.fillMaxWidth()) {
                        Text("Lock developer settings")
                    }
                    Text(
                        "Hides this section again. Tap the version number in About & Legal 7 " +
                            "times to bring it back.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
