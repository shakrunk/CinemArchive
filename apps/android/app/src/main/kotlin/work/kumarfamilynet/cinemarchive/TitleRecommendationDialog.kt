package work.kumarfamilynet.cinemarchive

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import work.kumarfamilynet.cinemarchive.core.model.TitleDetail
import work.kumarfamilynet.cinemarchive.data.LibraryRepository
import work.kumarfamilynet.cinemarchive.data.RecommendationDraft
import work.kumarfamilynet.cinemarchive.feature.friends.RecommendDialog
import work.kumarfamilynet.cinemarchive.feature.friends.TitleSocialSource

/** Post-show entrypoints pass only an ID; use the current account's current title snapshot. */
@Composable
internal fun TitleRecommendationDialog(
    repository: LibraryRepository, source: TitleSocialSource, viewerId: String, titleId: String, onDismiss: () -> Unit,
) {
    if (!source.isActive()) return
    key(repository, source, viewerId, titleId) {
        var detail by remember { mutableStateOf<TitleDetail?>(null) }
        var loaded by remember { mutableStateOf(false) }
        LaunchedEffect(repository, titleId) {
            repository.observeTitleDetail(titleId).collect { value ->
                if (source.isActive()) { detail = value; loaded = true }
            }
        }
        val title = detail
        val tmdbId = title?.tmdbId
        if (title != null && tmdbId != null && tmdbId > 0) {
            RecommendDialog(source, viewerId, RecommendationDraft(tmdbId, title.type, title.title, title.year, title.posterUrl), onDismiss)
        } else {
            AlertDialog(onDismissRequest = onDismiss, title = { Text("Recommend to a friend") },
                text = { Text(if (loaded) "This title is no longer available for recommendations." else "Loading title…") },
                confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
        }
    }
}
