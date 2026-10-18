package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Scope for a [SavedChangeRow]'s menu items; [dismiss] closes the menu before an item acts. */
class SavedChangeMenuScope internal constructor(val dismiss: () -> Unit)

/**
 * One saved (unsynced) change: its title and status, with the per-item actions behind an overflow
 * menu instead of a stack of full-width buttons that each repeat the title.
 */
@Composable
internal fun SavedChangeRow(
    title: String,
    status: String,
    enabled: Boolean,
    menu: @Composable SavedChangeMenuScope.() -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { open = true }, enabled = enabled) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Actions for $title")
                }
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    SavedChangeMenuScope { open = false }.menu()
                }
            }
        }
    }
}
