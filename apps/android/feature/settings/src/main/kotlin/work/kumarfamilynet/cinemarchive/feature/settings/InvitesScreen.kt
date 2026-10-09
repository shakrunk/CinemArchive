package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.ReadingWidthColumn
import work.kumarfamilynet.cinemarchive.data.AccountRepository
import work.kumarfamilynet.cinemarchive.data.InviteCode
import work.kumarfamilynet.cinemarchive.data.InviteRules

/** Invite codes — web `Profile.tsx`'s Invites section: generate (capped at 2 for non-owners),
 *  copy and delete unredeemed codes, redeemed ones shown with their date. */
@Composable
fun InvitesRoute(accountRepository: AccountRepository, onBack: () -> Unit, showBack: Boolean = true) {
    val profile by accountRepository.profile.collectAsStateWithLifecycle()
    var codes by remember { mutableStateOf<List<InviteCode>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    suspend fun reload() {
        try {
            codes = accountRepository.listInviteCodes()
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load invites."
        }
        loading = false
    }
    LaunchedEffect(Unit) {
        // Owner status gates the cap, so make sure the profile row is loaded.
        runCatching { accountRepository.refreshProfile() }
        reload()
    }

    val isOwner = profile?.isOwner ?: false
    val atCap = InviteRules.atCap(isOwner, codes)
    val unredeemed = InviteRules.unredeemedCount(codes)

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp, 8.dp, 20.dp, 2.dp)) {
            if (showBack) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            }
            Text("Invites", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp, 4.dp, 20.dp, 28.dp)) {
            ReadingWidthColumn {
                Text(
                    if (isOwner) {
                        "This is a private, invite-only archive. Generate codes for people you want to give access to."
                    } else {
                        "This is a private, invite-only archive. You can generate up to ${InviteRules.CAP} invite codes" +
                            (if (unredeemed > 0) " ($unredeemed unredeemed)" else "") + "."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    enabled = !generating && !atCap,
                    onClick = {
                        generating = true
                        error = null
                        scope.launch {
                            try {
                                accountRepository.createInviteCode()
                                reload()
                            } catch (e: Exception) {
                                error = e.message?.takeIf { !it.startsWith("POST ") } ?: "Failed to generate invite code."
                            }
                            generating = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                ) { Text(if (generating) "Generating…" else "Generate invite code") }
                if (atCap) {
                    Text(
                        "You've used both of your invites.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                    when {
                        loading -> Text("Loading invites…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        codes.isEmpty() -> Text("No invite codes generated yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> codes.forEach { c ->
                            InviteRow(
                                c,
                                onCopy = { clipboard.setText(AnnotatedString(c.code)) },
                                onDelete = {
                                    scope.launch {
                                        try {
                                            accountRepository.deleteInviteCode(c.id)
                                            reload()
                                        } catch (e: Exception) {
                                            error = "Failed to delete invite code."
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InviteRow(code: InviteCode, onCopy: () -> Unit, onDelete: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp, 10.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(code.code, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                Text(
                    if (code.isRedeemed) "Redeemed ${code.redeemedAt?.take(10).orEmpty()}" else "Created ${code.createdAt.take(10)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!code.isRedeemed) {
                IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy invite code") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete invite code") }
            }
        }
    }
}
