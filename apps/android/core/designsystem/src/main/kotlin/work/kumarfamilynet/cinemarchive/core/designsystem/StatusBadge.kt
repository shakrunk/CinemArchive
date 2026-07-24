package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus

/** Label + M3 container/content color pair for a [LibraryStatus] — mirrors `STATUS_META` in
 *  the design handoff (CinemArchive Android.dc.html). */
data class StatusMeta(val label: String, val container: Color, val onContainer: Color)

@Composable
fun LibraryStatus.meta(): StatusMeta = when (this) {
    LibraryStatus.WATCHED -> StatusMeta(
        "Seen",
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.onPrimaryContainer,
    )
    LibraryStatus.WATCHING -> StatusMeta(
        "Watching",
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.onTertiaryContainer,
    )
    LibraryStatus.WATCHLIST -> StatusMeta(
        "Watchlist",
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.onSecondaryContainer,
    )
    LibraryStatus.DROPPED -> StatusMeta(
        "Dropped",
        MaterialTheme.colorScheme.surfaceContainerHighest,
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun StatusBadge(status: LibraryStatus, modifier: Modifier = Modifier) {
    val meta = status.meta()
    Text(
        meta.label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = meta.onContainer,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(meta.container)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}
