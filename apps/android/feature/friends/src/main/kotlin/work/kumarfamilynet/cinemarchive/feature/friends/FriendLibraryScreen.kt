package work.kumarfamilynet.cinemarchive.feature.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import work.kumarfamilynet.cinemarchive.core.designsystem.ReadingWidthColumn
import work.kumarfamilynet.cinemarchive.data.FriendTitle
import work.kumarfamilynet.cinemarchive.data.FriendsRepository

/**
 * A friend's library, read-only (web `loadFriendLibrary`): fetched live through RLS — which
 * applies the friend's share scope server-side — and never written into this account's Room
 * database, so it can't mix into your own library, Ledger or sync queue.
 */
@Composable
fun FriendLibraryRoute(repository: FriendsRepository, friendUserId: String, label: String, onBack: () -> Unit) {
    var titles by remember { mutableStateOf<List<FriendTitle>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(friendUserId) {
        try {
            titles = repository.fetchFriendTitles(friendUserId)
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load this library."
        }
    }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp, 8.dp, 20.dp, 2.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(modifier = Modifier.padding(start = 4.dp)) {
                Text("$label's library", style = MaterialTheme.typography.titleLarge)
                Text("Read-only", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 8.dp, 20.dp, 28.dp)) {
            val list = titles
            item {
                ReadingWidthColumn {
                    when {
                        error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        list == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        list.isEmpty() -> Text("Nothing shared with you yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(list.orEmpty(), key = { it.id }) { t ->
                ReadingWidthColumn {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Column(modifier = Modifier.padding(14.dp, 10.dp)) {
                            Text(t.title + (t.year?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOfNotNull(t.status?.name?.lowercase()?.replaceFirstChar { it.uppercase() }, t.rating?.let { "★ $it" }, t.genres.takeIf { it.isNotEmpty() }?.joinToString(", "))
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
