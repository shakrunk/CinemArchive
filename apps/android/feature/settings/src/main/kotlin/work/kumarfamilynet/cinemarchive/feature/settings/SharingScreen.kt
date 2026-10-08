package work.kumarfamilynet.cinemarchive.feature.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import work.kumarfamilynet.cinemarchive.data.ShareScope
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
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            status = (e.message ?: "Couldn't load share links.") to true
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
                    OutlinedTextField(pastedLink, { pastedLink = it }, label = { Text("Open a shared link") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(enabled = SharingRules.parseToken(pastedLink) != null,
                        onClick = { SharingRules.parseToken(pastedLink)?.let(onOpenSharedLink) }) { Text("Open archive") }
                }
            }
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
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    status = (e.message ?: "Couldn't create the link.") to true
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
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Column(modifier = Modifier.padding(14.dp, 10.dp)) {
                            Text(k.label?.takeIf { it.isNotBlank() } ?: "Untitled link", style = MaterialTheme.typography.titleSmall)
                            Text(
                                (k.expiresAt?.take(10)?.let { "Expires $it" } ?: "Never expires") +
                                    (k.lastUsedAt?.take(10)?.let { " · last opened $it" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(SharingRules.shareLink(k.token))) }) { Text("Copy") }
                                OutlinedButton(onClick = {
                                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, SharingRules.shareLink(k.token))
                                    context.startActivity(Intent.createChooser(send, "Share link"))
                                }) { Text("Share") }
                                OutlinedButton(onClick = { editing = k }) { Text("Scope") }
                                TextButton(onClick = { revoking = k }) { Text("Revoke") }
                            }
                        }
                    }
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
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            status = (e.message ?: "Couldn't revoke the link.") to true
                        }
                    }
                }) { Text("Revoke") }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text("Cancel") } },
        )
    }

    editing?.let { k ->
        ScopeEditorDialog(repository, k, availableGenres, onClose = { editing = null }, onError = { status = it to true })
    }
}

@Composable
private fun ScopeEditorDialog(
    repository: SharingRepository,
    key: SharedAccessKey,
    availableGenres: List<String>,
    onClose: () -> Unit,
    onError: (String) -> Unit,
) {
    val statuses = listOf("watchlist", "watching", "watched", "dropped")
    var loaded by remember { mutableStateOf(false) }
    var restrictGenres by remember { mutableStateOf(false) }
    var restrictStatuses by remember { mutableStateOf(false) }
    var genres by remember { mutableStateOf(setOf<String>()) }
    var allowed by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(key.id) {
        try {
            repository.getShareScope(ShareScopeTarget.Link(key.id))?.let { s ->
                s.allowedGenres?.let { restrictGenres = true; genres = it.toSet() }
                s.allowedStatuses?.let { restrictStatuses = true; allowed = it.toSet() }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            onError(e.message ?: "Couldn't load the scope.")
            onClose()
            return@LaunchedEffect
        }
        loaded = true
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("What this link shows") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!loaded) Text("Loading…") else {
                    Text("Statuses", style = MaterialTheme.typography.labelLarge)
                    FilterChip(selected = !restrictStatuses, onClick = { restrictStatuses = !restrictStatuses }, label = { Text("All statuses") })
                    if (restrictStatuses) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        statuses.forEach { s ->
                            FilterChip(selected = s in allowed, onClick = { allowed = if (s in allowed) allowed - s else allowed + s }, label = { Text(s) })
                        }
                    }
                    Text("Genres", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                    FilterChip(selected = !restrictGenres, onClick = { restrictGenres = !restrictGenres }, label = { Text("All genres") })
                    if (restrictGenres) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (availableGenres + genres).distinct().sorted().forEach { g ->
                            FilterChip(selected = g in genres, onClick = { genres = if (g in genres) genres - g else genres + g }, label = { Text(g) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = loaded, onClick = {
                scope.launch {
                    try {
                        val scopeValue = ShareScope(
                            allowedGenres = if (restrictGenres) genres.toList() else null,
                            allowedStatuses = if (restrictStatuses) allowed.toList() else null,
                        )
                        repository.setShareScope(ShareScopeTarget.Link(key.id), scopeValue)
                        onClose()
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        onError(e.message ?: "Couldn't save the scope.")
                        onClose()
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
