package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import work.kumarfamilynet.cinemarchive.core.designsystem.ReadingWidthColumn

private data class OfflineLicense(val name: String, val body: String)

@Composable
internal fun OfflineLicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<OfflineLicense>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<OfflineLicense?>(null) }
    val goBack = { if (selected != null) selected = null else onBack() }
    BackHandler(onBack = goBack)
    LaunchedEffect(context) {
        try {
            entries = withContext(Dispatchers.IO) {
                val json = context.assets.open("open_source_licenses.json").bufferedReader().use { JSONArray(it.readText()) }
                List(json.length()) { index ->
                    val entry = json.getJSONObject(index)
                    OfflineLicense(entry.getString("name"), entry.getString("body"))
                }
            }
        } catch (_: java.io.IOException) {
            failed = true
        } catch (_: org.json.JSONException) {
            failed = true
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = goBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("License texts & notices", style = MaterialTheme.typography.titleLarge)
        }
        val current = selected
        if (current != null) {
            // Bound each text layout even for very large upstream notice files.
            val chunks = remember(current) { current.body.chunked(4000) }
            LazyColumn(Modifier.weight(1f)) {
                item {
                    ReadingWidthColumn {
                        Text(current.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                    }
                }
                itemsIndexed(chunks) { _, chunk ->
                    ReadingWidthColumn {
                        SelectionContainer {
                            Text(chunk, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                item {
                    ReadingWidthColumn {
                        Text(
                            when {
                                failed -> "License texts could not be loaded. Please reopen this screen to try again."
                                entries == null -> "Loading license texts…"
                                else -> "License texts and notices included with this build. Available offline."
                            },
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                itemsIndexed(entries.orEmpty()) { _, entry ->
                    ReadingWidthColumn {
                        Text(
                            entry.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().clickable { selected = entry }.padding(16.dp),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
