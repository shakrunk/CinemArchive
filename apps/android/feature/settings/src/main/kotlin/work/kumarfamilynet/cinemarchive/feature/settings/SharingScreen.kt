package work.kumarfamilynet.cinemarchive.feature.settings

import work.kumarfamilynet.cinemarchive.data.rethrowIfCancelled

import work.kumarfamilynet.cinemarchive.data.userMessage

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.designsystem.ChoiceOption
import work.kumarfamilynet.cinemarchive.core.designsystem.ReadingWidthColumn
import work.kumarfamilynet.cinemarchive.core.designsystem.SegmentedGroup
import work.kumarfamilynet.cinemarchive.data.ShareExpiry
import work.kumarfamilynet.cinemarchive.data.ShareScopeTarget
import work.kumarfamilynet.cinemarchive.data.SharedAccessKey
import work.kumarfamilynet.cinemarchive.data.SharingRepository
import work.kumarfamilynet.cinemarchive.data.SharingRules

/**
 * Share-link management (web `Profile.tsx` SharingSection): create a read-only link with a
 * label and expiry (max [SharingRules.MAX_ACTIVE_LINKS] active), copy/share its URL, edit which
 * genres/statuses it exposes, and revoke it. Opening a link delegates to the isolated viewer.
 */
@Composable
fun SharingRoute(
    repository: SharingRepository,
    /** Genres the scope editor offers (from the owner's library). */
    availableGenres: List<String>,
    onBack: () -> Unit,
    showBack: Boolean = true,
    onOpenSharedLink: (String) -> Unit = {},
) {
    var keys by remember { mutableStateOf<List<SharedAccessKey>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var creating by remember { mutableStateOf(false) }
    var pastedLink by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var label by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf(ShareExpiry.NEVER) }
    var revoking by remember { mutableStateOf<SharedAccessKey?>(null) }
    var editing by remember { mutableStateOf<SharedAccessKey?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    suspend fun reload() {
        try {
            keys = repository.listSharedKeys()
        } catch (e: Exception) { e.rethrowIfCancelled();
            if (e is kotlinx.coroutines.CancellationException) throw e
            status = (e.userMessage("Couldn't load share links.")) to true
        }
        loading = false
    }
    LaunchedEffect(Unit) { reload() }

    val active = keys.filter { it.isActive }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp, 8.dp, 20.dp, 2.dp)) {
            if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Share links", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
        }
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 4.dp, 20.dp, 28.dp)) {
            item {
                ReadingWidthColumn {
                    Text(
                        "Anyone with a link can browse a read-only copy of your library — nothing else. Up to ${SharingRules.MAX_ACTIVE_LINKS} active links.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Label (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    SegmentedGroup(
                        options = ShareExpiry.entries.map { ChoiceOption(it, it.label) },
                        selected = expiry,
                        onSelect = { expiry = it },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Button(
                        enabled = !loading && !creating && !SharingRules.atCap(keys),
                        onClick = {
                            creating = true
                            scope.launch {
                                try {
                                    repository.createSharedKey(label.trim().ifEmpty { null }, expiry)
                                    label = ""
                                    status = "Link created." to false
                                    reload()
                                } catch (e: Exception) { e.rethrowIfCancelled();
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    status = (e.userMessage("Couldn't create the link.")) to true
                                } finally {
                                    creating = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) { Text(if (SharingRules.atCap(keys)) "Link limit reached" else "Create link") }
                    status?.let {
                        Text(
                            it.first,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.second) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            if (loading) item { ReadingWidthColumn { Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            else if (active.isEmpty()) item {
                ReadingWidthColumn { Text("No active links.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp)) }
            }
            items(active, key = { it.id }) { k ->
                ReadingWidthColumn {
                    val name = k.label?.takeIf { it.isNotBlank() } ?: "Untitled link"
                    var menuOpen by remember { mutableStateOf(false) }
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    (k.expiresAt?.let { "Expires ${formatLinkDate(it)}" } ?: "Never expires") +
                                        (k.lastUsedAt?.let { " · last opened ${formatLinkDate(it)}" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Sharing is the common action; the rest sit in the overflow menu.
                            IconButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, SharingRules.shareLink(k.token))
                                context.startActivity(Intent.createChooser(send, "Share link"))
                            }) { Icon(Icons.Filled.Share, contentDescription = "Share $name") }
                            Box {
                                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for $name") }
                                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(text = { Text("Copy link") }, onClick = {
                                        menuOpen = false
                                        clipboard.setText(AnnotatedString(SharingRules.shareLink(k.token)))
                                        status = "Link copied." to false
                                    })
                                    DropdownMenuItem(text = { Text("Edit access") }, onClick = { menuOpen = false; editing = k })
                                    DropdownMenuItem(text = { Text("Revoke", color = MaterialTheme.colorScheme.error) },
                                        onClick = { menuOpen = false; revoking = k })
                                }
                            }
                        }
                    }
                }
            }
            item {
                ReadingWidthColumn(modifier = Modifier.padding(top = 28.dp)) {
                    Text("Open someone's archive", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(pastedLink, { pastedLink = it }, label = { Text("Paste a share link") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        trailingIcon = {
                            TextButton(enabled = SharingRules.parseToken(pastedLink) != null,
                                onClick = { SharingRules.parseToken(pastedLink)?.let(onOpenSharedLink) }) { Text("Open") }
                        })
                }
            }
        }
    }

    revoking?.let { k ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text("Revoke this link?") },
            text = { Text("Anyone using it will immediately lose access. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    revoking = null
                    scope.launch {
                        try {
                            repository.revokeSharedKey(k.id)
                            reload()
                        } catch (e: Exception) { e.rethrowIfCancelled();
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            status = (e.userMessage("Couldn't revoke the link.")) to true
                        }
                    }
                }) { Text("Revoke") }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text("Cancel") } },
        )
    }

    editing?.let { k ->
        val source = remember(repository) { RepositoryShareScopeSource(repository) }
        ShareScopeEditorDialog(source, ShareScopeTarget.Link(k.id), k.label ?: "Untitled link", availableGenres,
            onClose = { editing = null })
    }
}

/** Link timestamps are instants; show the local calendar day. */
private fun formatLinkDate(value: String): String = runCatching {
    java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US))
}.getOrDefault(value.take(10))
