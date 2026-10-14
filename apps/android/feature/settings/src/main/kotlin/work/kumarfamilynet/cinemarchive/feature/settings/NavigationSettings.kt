package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.model.NavigationDestination
import work.kumarfamilynet.cinemarchive.core.model.NavigationPreferences
import work.kumarfamilynet.cinemarchive.data.PreferencesRepository

@Composable
fun NavigationSettingsRoute(repository: PreferencesRepository, onBack: () -> Unit, showBack: Boolean = true) {
    val flow = remember(repository) { repository.observeNavigation() }
    val preferences by flow.collectAsStateWithLifecycle(initialValue = NavigationPreferences())
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save(action: suspend () -> Unit) {
        if (saving) return
        saving = true
        scope.launch {
            try { action(); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Navigation could not be saved. Try again." }
            finally { saving = false }
        }
    }
    NavigationSettingsScreen(preferences, onBack, showBack, saving, error,
        onMove = { id, direction -> save { repository.moveNavigation(id, direction) } },
        onShow = { id, visible -> save { repository.showNavigation(id, visible) } },
        onCompact = { save { repository.setNavigationCompact(it) } },
        onReset = { save { repository.resetNavigation() } })
}

@Composable
fun NavigationSettingsScreen(
    preferences: NavigationPreferences, onBack: () -> Unit, showBack: Boolean = true,
    saving: Boolean = false, error: String? = null,
    onMove: (NavigationDestination, Int) -> Unit,
    onShow: (NavigationDestination, Boolean) -> Unit,
    onCompact: (Boolean) -> Unit, onReset: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Navigation", style = MaterialTheme.typography.headlineSmall)
        }
        Text("Choose the order and visibility of tabs on this device. At least one tab must stay visible.",
            modifier = Modifier.padding(vertical = 16.dp))
        preferences.order.forEachIndexed { index, destination ->
            val visible = destination !in preferences.hidden
            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(destination.label, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onMove(destination, -1) }, enabled = !saving && index > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, "Move ${destination.label} up")
                        }
                        IconButton(onClick = { onMove(destination, 1) }, enabled = !saving && index < preferences.order.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, "Move ${destination.label} down")
                        }
                        Spacer(Modifier.weight(1f))
                        Text(if (visible) "Visible" else "Hidden", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = visible, onCheckedChange = { onShow(destination, it) },
                            enabled = !saving && (!visible || preferences.visible.size > 1),
                            modifier = Modifier.semantics { contentDescription = "Show ${destination.label} in navigation" })
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Compact navigation (icons only)", modifier = Modifier.weight(1f))
            Switch(checked = preferences.compact, onCheckedChange = onCompact, enabled = !saving,
                modifier = Modifier.semantics { contentDescription = "Compact navigation" })
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onReset, enabled = !saving) { Text("Reset navigation") }
    }
}
