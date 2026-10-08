package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CreditRefreshControl(titleId: String, refresh: suspend () -> Boolean) {
    var busy by remember(titleId) { mutableStateOf(false) }
    var message by rememberSaveable(titleId) { mutableStateOf<String?>(null) }
    var failed by rememberSaveable(titleId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column {
        TextButton(enabled = !busy, onClick = {
            busy = true; message = null
            scope.launch {
                try {
                    val changed = refresh()
                    failed = false
                    message = if (changed) "Credit refresh saved. Missing episodes will appear after sync." else "Credits are up to date."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failed = true
                    message = error.message ?: "Could not refresh credits. Try again."
                } finally { busy = false }
            }
        }) { Text(if (busy) "Refreshing credits…" else "Refresh credits") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
