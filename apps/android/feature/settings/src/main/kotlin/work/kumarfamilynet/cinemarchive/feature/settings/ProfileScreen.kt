package work.kumarfamilynet.cinemarchive.feature.settings

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import work.kumarfamilynet.cinemarchive.core.model.ArchivePalette
import work.kumarfamilynet.cinemarchive.core.model.ArchiveThemeMode
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.data.AuthRepository
import work.kumarfamilynet.cinemarchive.data.LibraryRepository
import work.kumarfamilynet.cinemarchive.data.PreferencesRepository

@Composable
fun ProfileRoute(
    libraryRepository: LibraryRepository,
    preferencesRepository: PreferencesRepository,
    authRepository: AuthRepository,
    appVersionName: String,
    onClose: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val titles by libraryRepository.observeLibrary().collectAsStateWithLifecycle(initialValue = emptyList())
    val themeMode by preferencesRepository.observeThemeMode().collectAsStateWithLifecycle(initialValue = ArchiveThemeMode.DARK)
    val palette by preferencesRepository.observePalette().collectAsStateWithLifecycle(initialValue = ArchivePalette.BRAND)
    val session by authRepository.observeSession().collectAsStateWithLifecycle()

    ProfileScreen(
        ownedCount = titles.size,
        watchedCount = titles.count { it.status == LibraryStatus.WATCHED },
        appearanceSummary = "${themeMode.label()} · ${palette.label()}",
        signedInEmail = session?.email,
        appVersionName = appVersionName,
        onClose = onClose,
        onOpenAppearance = onOpenAppearance,
        onOpenAbout = onOpenAbout,
        onOpenPermissions = onOpenPermissions,
        onSignOut = authRepository::signOut,
    )
}

internal fun ArchiveThemeMode.label(): String = when (this) {
    ArchiveThemeMode.SYSTEM -> "System"
    ArchiveThemeMode.LIGHT -> "Light"
    ArchiveThemeMode.DARK -> "Dark"
}

internal fun ArchivePalette.label(): String = when (this) {
    ArchivePalette.BRAND -> "Brand"
    ArchivePalette.MATERIAL_YOU -> "Material You"
    ArchivePalette.NOIR -> "Spider-Noir"
    ArchivePalette.MATRIX -> "The Construct"
}

@Composable
private fun ProfileScreen(
    ownedCount: Int,
    watchedCount: Int,
    appearanceSummary: String,
    signedInEmail: String?,
    appVersionName: String,
    onClose: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenPermissions: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 4.dp)) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
            }
            Text("Profile", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
        }

        LazyColumn(contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 28.dp)) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 26.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(76.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("C", style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                    Text("Cinephile", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
                    Text(
                        "a private film archive",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                ProfileRow(
                    icon = Icons.Filled.Palette,
                    iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                    iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                    title = "Appearance",
                    subtitle = appearanceSummary,
                    onClick = onOpenAppearance,
                )
            }
            item {
                ProfileRow(
                    icon = Icons.Filled.Lock,
                    iconContainer = MaterialTheme.colorScheme.primaryContainer,
                    iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                    title = "Permissions",
                    subtitle = "Camera, notifications & alarms",
                    onClick = onOpenPermissions,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            item {
                ProfileRow(
                    icon = Icons.Filled.Info,
                    iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                    iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                    title = "About & Legal",
                    subtitle = "Version $appVersionName",
                    onClick = onOpenAbout,
                    modifier = Modifier.padding(top = 10.dp, bottom = 22.dp),
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
                    StatTile(value = ownedCount.toString(), label = "Titles logged", modifier = Modifier.weight(1f))
                    StatTile(value = watchedCount.toString(), label = "Watched", modifier = Modifier.weight(1f))
                }
            }

            item {
                OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign out")
                }
                if (signedInEmail != null) {
                    Text(
                        "Signed in as $signedInEmail",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconContainer: androidx.compose.ui.graphics.Color,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(16.dp, 14.dp),
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = iconContainer, contentColor = iconTint, modifier = Modifier.size(36.dp)) {
                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
        ) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
