package work.kumarfamilynet.cinemarchive.feature.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import work.kumarfamilynet.cinemarchive.core.designsystem.GroupedSeamGap
import work.kumarfamilynet.cinemarchive.core.designsystem.ReadingWidthColumn
import work.kumarfamilynet.cinemarchive.core.designsystem.expressiveSpring
import work.kumarfamilynet.cinemarchive.core.designsystem.groupedItemShape
import work.kumarfamilynet.cinemarchive.core.model.InstallSource
import work.kumarfamilynet.cinemarchive.data.ApkInstaller
import work.kumarfamilynet.cinemarchive.data.AppUpdateRepository

private fun cameraGranted(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun notificationsGranted(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun exactAlarmsGranted(context: Context) =
    (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

private fun notificationSettingsIntent(context: Context) =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

private fun exactAlarmSettingsIntent(context: Context) =
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))

// Camera has no runtime dialog for taking a grant back (unlike notifications, which reopen the
// same toggle screen used to grant) — the app's permissions sub-page is the one place a granted
// camera permission can be revoked, so that's where "Manage" routes once it's on.
private fun appDetailsSettingsIntent(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

/**
 * Surfaces the permissions this app actually asks for, each contextually when a feature first
 * needs it (QR sign-in's camera preview, an outing's "how was it?" notification, a sideloaded
 * update's install prompt) rather than up front at launch — this screen is the one place to see
 * current status and fix a denial without hunting through system Settings. Exact alarms and
 * install-unknown-apps have no runtime request dialog at all, so they always route to their
 * Settings screen; the other two try the in-app dialog first.
 */
@Composable
fun PermissionsRoute(
    onBack: () -> Unit,
    apkInstaller: ApkInstaller? = null,
    appUpdateRepository: AppUpdateRepository? = null,
) {
    val context = LocalContext.current
    var cameraOk by remember { mutableStateOf(cameraGranted(context)) }
    var notificationsOk by remember { mutableStateOf(notificationsGranted(context)) }
    var exactAlarmsOk by remember { mutableStateOf(exactAlarmsGranted(context)) }
    var installUnknownAppsOk by remember { mutableStateOf(apkInstaller?.canRequestInstalls() ?: false) }

    fun refresh() {
        cameraOk = cameraGranted(context)
        notificationsOk = notificationsGranted(context)
        exactAlarmsOk = exactAlarmsGranted(context)
        installUnknownAppsOk = apkInstaller?.canRequestInstalls() ?: false
    }

    // Exact alarms and (pre-33) notifications only ever change via a system Settings screen this
    // Activity has no callback into, so re-check whenever the user comes back to this screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    // Meaningless for a Play-installed build — same gate AppUpdateRepository.checkForUpdate()
    // already applies, since Play (not this app) owns that install path.
    val showInstallUnknownApps = apkInstaller != null && appUpdateRepository?.installSource != InstallSource.PLAY_STORE

    PermissionsScreen(
        cameraGranted = cameraOk,
        notificationsGranted = notificationsOk,
        exactAlarmsGranted = exactAlarmsOk,
        installUnknownAppsGranted = installUnknownAppsOk,
        showInstallUnknownApps = showInstallUnknownApps,
        onBack = onBack,
        onRequestCamera = { cameraLauncher.launch(Manifest.permission.CAMERA) },
        onManageCamera = { context.startActivity(appDetailsSettingsIntent(context)) },
        onRequestNotifications = {
            if (Build.VERSION.SDK_INT >= 33) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(notificationSettingsIntent(context))
            }
        },
        // Same toggle screen either direction — it's just a switch the user flips.
        onManageNotifications = { context.startActivity(notificationSettingsIntent(context)) },
        onOpenExactAlarmSettings = { context.startActivity(exactAlarmSettingsIntent(context)) },
        onOpenInstallUnknownAppsSettings = { apkInstaller?.unknownSourcesSettingsIntent()?.let(context::startActivity) },
    )
}

@Composable
private fun PermissionsScreen(
    cameraGranted: Boolean,
    notificationsGranted: Boolean,
    exactAlarmsGranted: Boolean,
    installUnknownAppsGranted: Boolean,
    showInstallUnknownApps: Boolean,
    onBack: () -> Unit,
    onRequestCamera: () -> Unit,
    onManageCamera: () -> Unit,
    onRequestNotifications: () -> Unit,
    onManageNotifications: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenInstallUnknownAppsSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp, 8.dp, 20.dp, 2.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Permissions", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
        }

        // One grouped container rather than a flat run of separate cards, so the three
        // permissions read as a single set — the same convention Appearance's palette list
        // uses (#153: this section previously stood apart with individually rounded, gapped
        // cards instead of the seam-grouped M3 Expressive pattern used elsewhere in Settings).
        val rows = listOfNotNull(
            PermissionRowSpec(
                icon = Icons.Filled.Notifications,
                title = "Notifications",
                subtitle = "The \"how was it?\" prompt when a cinema outing you logged wraps up.",
                granted = notificationsGranted,
                actionLabel = if (Build.VERSION.SDK_INT >= 33) "Enable" else "Open Settings",
                onAction = onRequestNotifications,
                onManage = onManageNotifications,
            ),
            PermissionRowSpec(
                icon = Icons.Filled.CameraAlt,
                title = "Camera",
                subtitle = "Scans the QR code to sign in from a paired desktop session.",
                granted = cameraGranted,
                actionLabel = "Enable",
                onAction = onRequestCamera,
                onManage = onManageCamera,
            ),
            PermissionRowSpec(
                icon = Icons.Filled.Alarm,
                title = "Alarms & reminders",
                subtitle = "Lets an outing's \"how was it?\" notification fire on time even if the app is closed.",
                granted = exactAlarmsGranted,
                actionLabel = "Open Settings",
                onAction = onOpenExactAlarmSettings,
                onManage = onOpenExactAlarmSettings,
            ),
            if (showInstallUnknownApps) {
                PermissionRowSpec(
                    icon = Icons.Filled.InstallMobile,
                    title = "Install unknown apps",
                    subtitle = "Lets an in-app update install with one tap instead of opening the release page.",
                    granted = installUnknownAppsGranted,
                    actionLabel = "Open Settings",
                    onAction = onOpenInstallUnknownAppsSettings,
                    onManage = onOpenInstallUnknownAppsSettings,
                )
            } else {
                null
            },
        )
        LazyColumn(
            contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(GroupedSeamGap),
        ) {
            itemsIndexed(rows) { index, row ->
                ReadingWidthColumn {
                    PermissionRow(row, shape = groupedItemShape(isFirst = index == 0, isLast = index == rows.lastIndex))
                }
            }
        }
    }
}

private data class PermissionRowSpec(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val granted: Boolean,
    val actionLabel: String,
    val onAction: () -> Unit,
    // Every permission here can be taken back from Settings, so the row always offers a way in —
    // not just a one-shot "Enable" that vanishes once granted (#see PR description: revoking
    // should be as easy as granting). Defaults to onAction since exact-alarms and install-unknown
    // route both directions through the same OS toggle screen; camera and notifications override
    // it because granting and revoking land on different screens.
    val manageLabel: String = "Manage",
    val onManage: () -> Unit = onAction,
)

@Composable
private fun PermissionRow(row: PermissionRowSpec, shape: Shape) {
    val (icon, title, subtitle, granted, actionLabel, onAction, manageLabel, onManage) = row
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Granted/not-granted state lives on the pill below, not here — a filled icon
                // chip next to a filled status pill double-encoded the same state and read as
                // two loud badges stacked in one row.
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp),
                ) {
                    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    PermissionStatusPill(granted, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (!granted) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
                )
                // Not granted is the state that wants the user's attention, so it gets the
                // filled, higher-emphasis button; once granted the row is in its settled state
                // and "Manage" (revoke) drops to the same quiet outlined treatment every other
                // secondary action in this screen uses.
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                    Text(actionLabel)
                }
            } else {
                OutlinedButton(
                    onClick = onManage,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Text(manageLabel)
                }
            }
        }
    }
}

/** Status readout for a permission row — a filled pill rather than bare text underneath the
 *  title, so "granted" reads as a distinct on/active state instead of a caption easy to miss.
 *  Fully rounded (not a soft-cornered rectangle) so it reads as a pill, and the flip between
 *  states animates rather than snapping — M3 Expressive treats state changes as motion, not
 *  just a color swap. */
@Composable
private fun PermissionStatusPill(granted: Boolean, modifier: Modifier = Modifier) {
    val container by animateColorAsState(
        if (granted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = expressiveSpring(),
        label = "permissionPillContainer",
    )
    val onContainer by animateColorAsState(
        if (granted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = expressiveSpring(),
        label = "permissionPillContent",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Icon(
            // Not-granted is a neutral off-state, not a failure — an outline dot reads as
            // "unset" where a filled Cancel glyph reads as an error.
            if (granted) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = onContainer,
            modifier = Modifier.size(12.dp),
        )
        Text(
            if (granted) "Allowed" else "Not allowed",
            style = MaterialTheme.typography.labelSmall,
            color = onContainer,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
