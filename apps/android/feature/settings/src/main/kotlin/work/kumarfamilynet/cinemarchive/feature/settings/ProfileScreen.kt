package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import work.kumarfamilynet.cinemarchive.core.designsystem.GroupedSeamGap
import work.kumarfamilynet.cinemarchive.core.designsystem.ReadingWidthColumn
import work.kumarfamilynet.cinemarchive.core.designsystem.groupedItemShape
import work.kumarfamilynet.cinemarchive.core.model.ArchivePalette
import work.kumarfamilynet.cinemarchive.core.model.ArchiveThemeMode
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.data.AccountRepository
import work.kumarfamilynet.cinemarchive.data.AuthRepository
import work.kumarfamilynet.cinemarchive.data.NotificationsRepository
import work.kumarfamilynet.cinemarchive.data.LibraryRepository
import work.kumarfamilynet.cinemarchive.data.PreferencesRepository

/** The settings sub-screens reachable from Profile — named here (rather than left as bare
 *  navigation calls) so the foldable/tablet split view can track which one is showing in the
 *  trailing pane alongside this leading [ProfileRoute] list. */
enum class SettingsCategory { IDENTITY, INVITES, NOTIFICATIONS, SHARING, APPEARANCE, NAVIGATION, MOVIEGOING, IMPORT_SYNC, DATA_RECOVERY, PERMISSIONS, ABOUT, DEVELOPER }

@Composable
fun ProfileRoute(
    libraryRepository: LibraryRepository,
    preferencesRepository: PreferencesRepository,
    authRepository: AuthRepository,
    accountRepository: AccountRepository,
    notificationsRepository: NotificationsRepository,
    appVersionName: String,
    onClose: () -> Unit,
    onOpenIdentity: () -> Unit,
    onOpenInvites: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenSharing: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenNavigation: () -> Unit,
    onOpenMoviegoing: () -> Unit,
    onOpenImportSync: () -> Unit,
    onOpenDataRecovery: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenPermissions: () -> Unit,
    // Governs the Developer Settings row's visibility — see
    // PreferencesRepository.observeDevSettingsUnlocked: unlocked by default in debug builds,
    // locked by default in release builds, until the version-number tap gesture in About & Legal
    // overrides it.
    devSettingsUnlocked: Boolean,
    onOpenDeveloperSettings: () -> Unit,
    // Non-null only in the wide/split layout, where this list sits permanently alongside its
    // detail pane rather than being replaced by it — highlights which category is showing
    // opposite it. Full-screen (phone) navigation has no such concept: the row tap itself is
    // the only feedback needed before the screen it names takes over.
    selectedCategory: SettingsCategory? = null,
) {
    val titles by libraryRepository.observeLibrary().collectAsStateWithLifecycle(initialValue = emptyList())
    val themeMode by preferencesRepository.observeThemeMode().collectAsStateWithLifecycle(initialValue = ArchiveThemeMode.DARK)
    val palette by preferencesRepository.observePalette().collectAsStateWithLifecycle(initialValue = ArchivePalette.BRAND)
    val session by authRepository.observeSession().collectAsStateWithLifecycle()

    val account by accountRepository.profile.collectAsStateWithLifecycle()
    val inbox by notificationsRepository.inbox.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(session?.userId) { runCatching { accountRepository.refreshProfile() } }

    ProfileScreen(
        heading = account?.displayName?.takeIf { it.isNotBlank() } ?: account?.username?.takeIf { it.isNotBlank() },
        unreadNotifications = inbox.unreadCount,
        onOpenIdentity = onOpenIdentity,
        onOpenInvites = onOpenInvites,
        onOpenNotifications = onOpenNotifications,
        onOpenFriends = onOpenFriends,
        onOpenSharing = onOpenSharing,
        ownedCount = titles.size,
        watchedCount = titles.count { it.status == LibraryStatus.WATCHED },
        appearanceSummary = "${themeMode.label()} · ${palette.label()}",
        signedInEmail = session?.email,
        appVersionName = appVersionName,
        onClose = onClose,
        onOpenAppearance = onOpenAppearance,
        onOpenNavigation = onOpenNavigation,
        onOpenMoviegoing = onOpenMoviegoing,
        onOpenImportSync = onOpenImportSync,
        onOpenDataRecovery = onOpenDataRecovery,
        onOpenAbout = onOpenAbout,
        onOpenPermissions = onOpenPermissions,
        devSettingsUnlocked = devSettingsUnlocked,
        onOpenDeveloperSettings = onOpenDeveloperSettings,
        onSignOut = authRepository::signOut,
        selectedCategory = selectedCategory,
    )
}

internal fun ArchiveThemeMode.label(): String = when (this) {
    ArchiveThemeMode.SYSTEM -> "System"
    ArchiveThemeMode.LIGHT -> "Light"
    ArchiveThemeMode.DARK -> "Dark"
}

/** The name to head the Profile screen with. Magic-link sign-in gives us no
 *  profile name, so derive one from the email's local part (dropping any `+tag`)
 *  and fall back to the app name when signed out. Public (not `internal`): also backs the
 *  top-bar avatar's initial across every tab — see [profileInitial] and #156/#157. */
fun profileDisplayName(email: String?): String {
    val local = email?.substringBefore('@')?.substringBefore('+')?.trim().orEmpty()
    return local
        .split('.', '_', '-')
        .filter { it.isNotBlank() }
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }
        .ifBlank { "CinemArchive" }
}

/** Single-letter initial for [ProfileAvatarButton][work.kumarfamilynet.cinemarchive.core.designsystem.ProfileAvatarButton]
 *  across every tab header — Android has no separate `name`/`username` fields to prefer
 *  between (no `profiles` table sync; magic-link auth only gives an email), so this is the
 *  closest equivalent to the web app's name-then-username precedence (#157): the same
 *  email-derived display name already used to head the Profile screen itself. */
fun profileInitial(email: String?): String =
    profileDisplayName(email).firstOrNull()?.uppercaseChar()?.toString() ?: "C"

internal fun ArchivePalette.label(): String = when (this) {
    ArchivePalette.BRAND -> "Brand"
    ArchivePalette.MATERIAL_YOU -> "Material You"
    ArchivePalette.NOIR -> "Spider-Noir"
    ArchivePalette.MATRIX -> "The Construct"
}

@Composable
private fun ProfileScreen(
    heading: String?,
    unreadNotifications: Int,
    onOpenIdentity: () -> Unit,
    onOpenInvites: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenSharing: () -> Unit,
    ownedCount: Int,
    watchedCount: Int,
    appearanceSummary: String,
    signedInEmail: String?,
    appVersionName: String,
    onClose: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenNavigation: () -> Unit,
    onOpenMoviegoing: () -> Unit,
    onOpenImportSync: () -> Unit,
    onOpenDataRecovery: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenPermissions: () -> Unit,
    devSettingsUnlocked: Boolean,
    onOpenDeveloperSettings: () -> Unit,
    onSignOut: () -> Unit,
    selectedCategory: SettingsCategory? = null,
) {
    val displayName = heading ?: profileDisplayName(signedInEmail)
    val signedIn = signedInEmail != null
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize().background(cs.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp, 8.dp, 20.dp, 2.dp)) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
            }
            Text("Profile", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
        }

        // weight(1f): a size-less LazyColumn wrap-content-sizes to its rows, leaving the rest of
        // the screen a plain Column that doesn't own touch — a drag there fell through to
        // whatever's underneath this overlay instead of being absorbed as a no-op scroll.
        LazyColumn(contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 28.dp), modifier = Modifier.weight(1f)) {
            item {
                ReadingWidthColumn {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(28.dp),
                            color = cs.primaryContainer,
                            contentColor = cs.onPrimaryContainer,
                            modifier = Modifier.size(76.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    displayName.first().uppercaseChar().toString(),
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                            }
                        }
                        Text(displayName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
                        Text(
                            "a private film archive",
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        StatTile(value = ownedCount.toString(), label = "Titles logged", modifier = Modifier.weight(1f))
                        StatTile(value = watchedCount.toString(), label = "Watched", modifier = Modifier.weight(1f))
                    }
                }
            }

            if (signedIn) {
                settingsSection(
                    "Account",
                    SettingsRow(
                        icon = Icons.Filled.Person,
                        iconContainer = cs.primaryContainer,
                        iconTint = cs.onPrimaryContainer,
                        title = "Identity",
                        subtitle = "Display name & username",
                        onClick = onOpenIdentity,
                        selected = selectedCategory == SettingsCategory.IDENTITY,
                    ),
                    SettingsRow(
                        icon = Icons.Filled.ConfirmationNumber,
                        iconContainer = cs.tertiaryContainer,
                        iconTint = cs.onTertiaryContainer,
                        title = "Invites",
                        subtitle = "Invite people to the archive",
                        onClick = onOpenInvites,
                        selected = selectedCategory == SettingsCategory.INVITES,
                    ),
                )
                settingsSection(
                    "Social",
                    SettingsRow(
                        icon = Icons.Filled.People,
                        iconContainer = cs.primaryContainer,
                        iconTint = cs.onPrimaryContainer,
                        title = "Friends",
                        subtitle = "Requests, inbox & activity",
                        onClick = onOpenFriends,
                    ),
                    SettingsRow(
                        icon = Icons.Filled.Share,
                        iconContainer = cs.secondaryContainer,
                        iconTint = cs.onSecondaryContainer,
                        title = "Share links",
                        subtitle = "Read-only access & visibility",
                        onClick = onOpenSharing,
                        selected = selectedCategory == SettingsCategory.SHARING,
                    ),
                    SettingsRow(
                        icon = Icons.Filled.Notifications,
                        iconContainer = cs.tertiaryContainer,
                        iconTint = cs.onTertiaryContainer,
                        title = "Notifications",
                        subtitle = if (unreadNotifications > 0) "$unreadNotifications unread" else "You're all caught up",
                        onClick = onOpenNotifications,
                        selected = selectedCategory == SettingsCategory.NOTIFICATIONS,
                    ),
                )
            }

            settingsSection(
                "Preferences",
                SettingsRow(
                    icon = Icons.Filled.Palette,
                    iconContainer = cs.tertiaryContainer,
                    iconTint = cs.onTertiaryContainer,
                    title = "Appearance",
                    subtitle = appearanceSummary,
                    onClick = onOpenAppearance,
                    selected = selectedCategory == SettingsCategory.APPEARANCE,
                ),
                SettingsRow(
                    icon = Icons.Filled.Tune,
                    iconContainer = cs.secondaryContainer,
                    iconTint = cs.onSecondaryContainer,
                    title = "Navigation",
                    subtitle = "Tab order, visibility & compact labels",
                    onClick = onOpenNavigation,
                    selected = selectedCategory == SettingsCategory.NAVIGATION,
                ),
                if (signedIn) SettingsRow(
                    icon = Icons.Filled.Theaters,
                    iconContainer = cs.primaryContainer,
                    iconTint = cs.onPrimaryContainer,
                    title = "Moviegoing",
                    subtitle = "Venue notes & theater interest",
                    onClick = onOpenMoviegoing,
                    selected = selectedCategory == SettingsCategory.MOVIEGOING,
                ) else null,
            )

            settingsSection(
                "Data",
                SettingsRow(
                    icon = Icons.Filled.Sync,
                    iconContainer = cs.secondaryContainer,
                    iconTint = cs.onSecondaryContainer,
                    title = "Import & sync",
                    subtitle = "Letterboxd, Simkl, Plex & Emby",
                    onClick = onOpenImportSync,
                    selected = selectedCategory == SettingsCategory.IMPORT_SYNC,
                ),
                if (signedIn) SettingsRow(
                    icon = Icons.Filled.Restore,
                    iconContainer = cs.tertiaryContainer,
                    iconTint = cs.onTertiaryContainer,
                    title = "Data & recovery",
                    subtitle = "Unsynced changes, restore & catalog refresh",
                    onClick = onOpenDataRecovery,
                    selected = selectedCategory == SettingsCategory.DATA_RECOVERY,
                ) else null,
            )

            settingsSection(
                "App",
                SettingsRow(
                    icon = Icons.Filled.Lock,
                    iconContainer = cs.primaryContainer,
                    iconTint = cs.onPrimaryContainer,
                    title = "Permissions",
                    subtitle = "Camera, notifications & alarms",
                    onClick = onOpenPermissions,
                    selected = selectedCategory == SettingsCategory.PERMISSIONS,
                ),
                SettingsRow(
                    icon = Icons.Filled.Info,
                    iconContainer = cs.secondaryContainer,
                    iconTint = cs.onSecondaryContainer,
                    title = "About & Legal",
                    subtitle = "Version $appVersionName",
                    onClick = onOpenAbout,
                    selected = selectedCategory == SettingsCategory.ABOUT,
                ),
                if (devSettingsUnlocked) SettingsRow(
                    icon = Icons.Filled.DeveloperMode,
                    iconContainer = cs.errorContainer,
                    iconTint = cs.onErrorContainer,
                    title = "Developer Settings",
                    subtitle = "Debug tools & build indicator",
                    onClick = onOpenDeveloperSettings,
                    selected = selectedCategory == SettingsCategory.DEVELOPER,
                ) else null,
            )

            item {
                ReadingWidthColumn {
                    OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                        Text("Sign out")
                    }
                    if (signedInEmail != null) {
                        Text(
                            "Signed in as $signedInEmail",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** One row of a [settingsSection]: the destination it opens and how [ProfileRow] draws it. */
private class SettingsRow(
    val icon: ImageVector,
    val iconContainer: Color,
    val iconTint: Color,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
    val selected: Boolean = false,
)

/**
 * One titled group of [ProfileRow]s, drawn as a single M3 Expressive grouped container: the
 * group's outer corners take the large radius and the seams between rows stay tight (see
 * [groupedItemShape]). Rows are handed over as a list, null for one that doesn't apply to this
 * reader, so the shapes follow the rows actually shown: a section that loses its last row to a
 * signed-out reader still rounds off at the bottom.
 */
private fun LazyListScope.settingsSection(title: String, vararg rows: SettingsRow?) {
    val shown = rows.filterNotNull()
    if (shown.isEmpty()) return
    item {
        ReadingWidthColumn {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(GroupedSeamGap)) {
                shown.forEachIndexed { index, row ->
                    ProfileRow(
                        icon = row.icon,
                        iconContainer = row.iconContainer,
                        iconTint = row.iconTint,
                        title = row.title,
                        subtitle = row.subtitle,
                        onClick = row.onClick,
                        selected = row.selected,
                        shape = groupedItemShape(isFirst = index == 0, isLast = index == shown.lastIndex),
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
    selected: Boolean = false,
    shape: Shape = RoundedCornerShape(20.dp),
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        // Only meaningful in the split layout, where this row's screen sits permanently in the
        // trailing pane rather than being navigated away to — a border, not a container-color
        // swap, so it doesn't fight the row's own icon-chip color underneath.
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
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
