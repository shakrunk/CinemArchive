package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The top-bar "→ Settings" avatar: a single letter in a rounded tile. Shared by every tab
 * header (Library, Discover, Up Next, Ledger — #155/KP shared-header parity) so the tap target
 * and styling can't drift between screens. [initial] is the signed-in user's own initial, not
 * a hardcoded "C" (#156) — see `profileDisplayName` in `feature/settings/ProfileScreen.kt`.
 */
@Composable
fun ProfileAvatarButton(initial: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(initial, style = MaterialTheme.typography.titleMedium)
        }
    }
}
