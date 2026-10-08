package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Unread in-app notification count, provided once at the app shell so every tab header's
 * [ProfileAvatarButton] can show the web bell's unread dot without each screen threading a
 * new parameter through (notifications live behind the avatar → Profile on Android).
 */
val LocalUnreadNotificationCount = compositionLocalOf { 0 }

/**
 * The top-bar "→ Settings" avatar: a single letter in a rounded tile. Shared by every tab
 * header (Library, Discover, Up Next, Ledger — #155/KP shared-header parity) so the tap target
 * and styling can't drift between screens. [initial] is the signed-in user's own initial, not
 * a hardcoded "C" (#156) — see `profileDisplayName` in `feature/settings/ProfileScreen.kt`.
 * Shows a dot when [LocalUnreadNotificationCount] is above zero.
 */
@Composable
fun ProfileAvatarButton(initial: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val unread = LocalUnreadNotificationCount.current
    Box(
        modifier = modifier
            .size(36.dp)
            .semantics { if (unread > 0) contentDescription = "Profile — $unread unread notifications" },
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(initial, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (unread > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
            )
        }
    }
}
