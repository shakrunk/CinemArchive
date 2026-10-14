package work.kumarfamilynet.cinemarchive.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import work.kumarfamilynet.cinemarchive.core.model.CatalogProvider

@Composable
fun CatalogExtrasSection(
    state: CatalogExtrasState,
    onRetryVideos: () -> Unit,
    onRetryProviders: () -> Unit,
    openUrl: ((String) -> Unit)? = null,
) {
    if (state.key == null) return
    val uriHandler = LocalUriHandler.current
    var linkError by remember(state.key) { mutableStateOf(false) }
    val open: (String) -> Unit = { url ->
        linkError = runCatching { if (openUrl != null) openUrl(url) else uriHandler.openUri(url) }.isFailure
    }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag("catalog-extras")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Trailers", style = MaterialTheme.typography.titleMedium)
            when (val videos = state.videos) {
                CatalogLoad.Loading -> Text("Loading trailers…", style = MaterialTheme.typography.bodyMedium)
                CatalogLoad.Failed -> {
                    Text("Trailers couldn't load.")
                    TextButton(onClick = onRetryVideos) { Text("Retry trailers") }
                }
                is CatalogLoad.Ready -> {
                    if (videos.value.isEmpty()) Text("No trailers available.", style = MaterialTheme.typography.bodyMedium)
                    videos.value.forEach { video ->
                        OutlinedButton(onClick = { open(video.watchUrl) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(video.name, style = MaterialTheme.typography.titleSmall)
                                Text("${if (video.official) "Official " else ""}${video.type} · YouTube", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            Text("Where to watch", style = MaterialTheme.typography.titleMedium)
            when (val providers = state.providers) {
                CatalogLoad.Loading -> Text("Loading providers…", style = MaterialTheme.typography.bodyMedium)
                CatalogLoad.Failed -> {
                    Text("Watch providers couldn't load.")
                    TextButton(onClick = onRetryProviders) { Text("Retry providers") }
                }
                is CatalogLoad.Ready -> {
                    val value = providers.value
                    if (value == null || value.isEmpty) Text("No streaming providers available.", style = MaterialTheme.typography.bodyMedium)
                    if (value != null) {
                        ProvidersRow("Stream", value.stream)
                        ProvidersRow("Rent", value.rent)
                        ProvidersRow("Buy", value.buy)
                        value.link?.let { link ->
                            TextButton(onClick = { open(link) }) { Text("Streaming data provided by JustWatch") }
                        }
                    }
                }
            }
            if (linkError) Text("Couldn't open the link. Try again.", color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProvidersRow(label: String, providers: List<CatalogProvider>) {
    if (providers.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            providers.forEach { provider ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    provider.logoUrl?.let { AsyncImage(it, contentDescription = null, modifier = Modifier.size(24.dp)) }
                    Text(provider.name, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
