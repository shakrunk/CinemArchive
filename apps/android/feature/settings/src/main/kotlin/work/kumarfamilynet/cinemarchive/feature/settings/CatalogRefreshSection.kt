package work.kumarfamilynet.cinemarchive.feature.settings

import work.kumarfamilynet.cinemarchive.data.rethrowIfCancelled

import work.kumarfamilynet.cinemarchive.data.userMessage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.data.CatalogRefreshProgress
import work.kumarfamilynet.cinemarchive.data.CatalogRefreshReport

@Composable
fun CatalogRefreshSection(refresh: suspend ((CatalogRefreshProgress) -> Unit) -> CatalogRefreshReport) {
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<CatalogRefreshProgress?>(null) }
    var report by remember { mutableStateOf<CatalogRefreshReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Library metadata", style = MaterialTheme.typography.titleMedium)
            Text("Refresh catalog details and credits for every title. Your ratings, notes and viewing history stay intact.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy, onClick = {
                busy = true; report = null; error = null
                scope.launch {
                    try { report = refresh { progress = it } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { failure.rethrowIfCancelled(); error = failure.userMessage("Could not finish refreshing. Saved changes are kept.") }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Refreshing library…" else "Refresh all metadata") }
            if (busy) progress?.let {
                LinearProgressIndicator(progress = { if (it.total > 0) it.completed.toFloat() / it.total else 0f }, modifier = Modifier.fillMaxWidth())
                Text("${it.completed}/${it.total} · ${it.title}", style = MaterialTheme.typography.bodySmall)
            }
            report?.let {
                Text("${it.refreshed} refreshed · ${it.unchanged} unchanged · ${it.failures.size} failed. Changes are saved for sync.")
                it.failures.forEach { failure -> Text(failure, color = MaterialTheme.colorScheme.error) }
                if (it.failures.isNotEmpty()) Text("Run refresh again to retry. Previously saved changes are kept.")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
