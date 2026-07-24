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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Notifications
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private fun cameraGranted(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun notificationsGranted(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun exactAlarmsGranted(context: Context) =
    (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

private fun notificationSettingsIntent(context: Context) =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

private fun exactAlarmSettingsIntent(context: Context) =
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))

/**
 * Surfaces the three permissions this app actually asks for, each contextually when a feature
 * first needs it (QR sign-in's camera preview, an outing's "how was it?" notification) rather
 * than up front at launch — this screen is the one place to see current status and fix a denial
 * without hunting through system Settings. Exact alarms has no runtime request dialog at all,
 * so it always routes to its Settings screen; the other two try the in-app dialog first.
 */
@Composable
fun PermissionsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    var cameraOk by remember { mutableStateOf(cameraGranted(context)) }
    var notificationsOk by remember { mutableStateOf(notificationsGranted(context)) }
    var exactAlarmsOk by remember { mutableStateOf(exactAlarmsGranted(context)) }

    fun refresh() {
        cameraOk = cameraGranted(context)
        notificationsOk = notificationsGranted(context)
        exactAlarmsOk = exactAlarmsGranted(context)
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

    PermissionsScreen(
        cameraGranted = cameraOk,
        notificationsGranted = notificationsOk,
        exactAlarmsGranted = exactAlarmsOk,
        onBack = onBack,
        onRequestCamera = { cameraLauncher.launch(Manifest.permission.CAMERA) },
        onRequestNotifications = {
            if (Build.VERSION.SDK_INT >= 33) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(notificationSettingsIntent(context))
            }
        },
        onOpenExactAlarmSettings = { context.startActivity(exactAlarmSettingsIntent(context)) },
    )
}

@Composable
private fun PermissionsScreen(
    cameraGranted: Boolean,
    notificationsGranted: Boolean,
    exactAlarmsGranted: Boolean,
    onBack: () -> Unit,
    onRequestCamera: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Permissions", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
        }

        LazyColumn(contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp)) {
            item {
                PermissionRow(
                    icon = Icons.Filled.Notifications,
                    title = "Notifications",
                    subtitle = "The \"how was it?\" prompt when a cinema outing you logged wraps up.",
                    granted = notificationsGranted,
                    actionLabel = if (Build.VERSION.SDK_INT >= 33) "Enable" else "Open Settings",
                    onAction = onRequestNotifications,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                PermissionRow(
                    icon = Icons.Filled.CameraAlt,
                    title = "Camera",
                    subtitle = "Scans the QR code to sign in from a paired desktop session.",
                    granted = cameraGranted,
                    actionLabel = "Enable",
                    onAction = onRequestCamera,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                PermissionRow(
                    icon = Icons.Filled.Alarm,
                    title = "Alarms & reminders",
                    subtitle = "Lets an outing's \"how was it?\" notification fire on time even if the app is closed.",
                    granted = exactAlarmsGranted,
                    actionLabel = "Open Settings",
                    onAction = onOpenExactAlarmSettings,
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (granted) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (granted) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp),
                ) {
                    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (granted) "Allowed" else "Not allowed",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!granted) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
                )
                OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                    Text(actionLabel)
                }
            }
        }
    }
}
