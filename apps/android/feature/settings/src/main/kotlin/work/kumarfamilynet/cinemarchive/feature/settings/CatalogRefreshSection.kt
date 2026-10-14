package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    Column {
        Text("Library metadata", style = MaterialTheme.typography.titleMedium)
        Text("Refresh catalog details and credits for every title. Your ratings, notes and viewing history stay intact.")
        TextButton(enabled = !busy, onClick = {
            busy = true; report = null; error = null
            scope.launch {
                try { report = refresh { progress = it } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "Could not finish refreshing. Saved changes are kept." }
                finally { busy = false }
            }
        }) { Text(if (busy) "Refreshing library…" else "Refresh all metadata") }
        if (busy) progress?.let { Text("${it.completed}/${it.total} · ${it.title}") }
        report?.let {
            Text("${it.refreshed} refreshed · ${it.unchanged} unchanged · ${it.failures.size} failed. Changes are saved for sync.")
            it.failures.forEach { failure -> Text(failure, color = MaterialTheme.colorScheme.error) }
            if (it.failures.isNotEmpty()) Text("Run refresh again to retry. Previously saved changes are kept.")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
