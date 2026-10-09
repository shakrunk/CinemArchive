package work.kumarfamilynet.cinemarchive

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.data.*
import work.kumarfamilynet.cinemarchive.feature.friends.RecommendDialog
import work.kumarfamilynet.cinemarchive.feature.friends.TitleSocialSection
import work.kumarfamilynet.cinemarchive.feature.friends.TitleSocialSource

/** Full friend archive, kept solely in this account-keyed composition's memory. */
@Composable
fun FriendLibraryRoute(
    source: FriendLibrarySource,
    viewerUserId: String,
    friendUserId: String,
    label: String,
    socialSource: TitleSocialSource,
    onBack: () -> Unit,
) {
    key(source, viewerUserId, friendUserId, socialSource) {
        val context = LocalContext.current.applicationContext
        var snapshot by remember { mutableStateOf<SharedLibrarySnapshot?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var attempt by remember { mutableIntStateOf(0) }
        LaunchedEffect(attempt) {
            snapshot = null
            error = null
            try {
                check(socialSource.isActive())
                val library = source.loadFriendLibrary(viewerUserId, friendUserId)
                currentCoroutineContext().ensureActive()
                check(socialSource.isActive() && library.ownerUserId == friendUserId)
                val loaded = buildSharedLibrarySnapshot(context, library)
                currentCoroutineContext().ensureActive()
                check(socialSource.isActive())
                snapshot = loaded
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (socialSource.isActive()) error = "This friend archive could not be loaded. Check your connection and friendship, then retry."
            }
        }
        if (socialSource.isActive()) {
            ArchiveViewer("$viewerUserId:$friendUserId", snapshot, error, { attempt++ }, onBack,
                heading = "$label's archive", displayName = label,
                emptyMessage = "Nothing shared with you yet.",
                socialContent = { title -> FriendTitleSocial(socialSource, viewerUserId, title) })
        }
    }
}

@Composable
private fun FriendTitleSocial(source: TitleSocialSource, viewerUserId: String, title: SharedLibraryTitle) {
    key(source, viewerUserId, title.id) {
        var recommending by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { recommending = true }) { Text("Recommend to a friend") }
        TitleSocialSection(source, title.id, viewerUserId)
        if (recommending) RecommendDialog(source, viewerUserId,
            RecommendationDraft(title.tmdbId, if (title.mediaType == "movie") MediaType.MOVIE else MediaType.TV,
                title.title, title.year, title.posterUrl), onDismiss = { recommending = false })
    }
}
