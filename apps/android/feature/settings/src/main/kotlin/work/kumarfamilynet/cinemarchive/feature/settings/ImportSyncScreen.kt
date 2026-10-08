package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import work.kumarfamilynet.cinemarchive.core.designsystem.ReadingWidthColumn
import work.kumarfamilynet.cinemarchive.data.IntegrationConnection
import work.kumarfamilynet.cinemarchive.data.SimklPoll
import work.kumarfamilynet.cinemarchive.data.SyncItem
import work.kumarfamilynet.cinemarchive.data.SyncProvider
import work.kumarfamilynet.cinemarchive.data.SyncResult
import work.kumarfamilynet.cinemarchive.data.SyncServices

/**
 * Settings → Import & sync. Connect-and-import for Simkl, Plex and Emby — the Android
 * counterpart of web's `SyncConnections`. Import-only: nothing is written back to these
 * services, and existing ratings/viewings in the library are never overwritten. The Plex token
 * and Emby password are held in memory for one sync and never stored.
 */
@Composable
fun ImportSyncRoute(
    services: SyncServices,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var connections by remember { mutableStateOf<List<IntegrationConnection>>(emptyList()) }
    var busy by remember { mutableStateOf<SyncProvider?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) } // (isError, text)
    var simklCode by remember { mutableStateOf<String?>(null) }
    var cancelled by remember { mutableStateOf(false) }
    // Plex token is memory-only — it grants full account access.
    var plexToken by remember { mutableStateOf<String?>(null) }
    var embyUrl by rememberSaveable { mutableStateOf("") }
    var embyUser by rememberSaveable { mutableStateOf("") }
    var embyPassword by remember { mutableStateOf("") }

    suspend fun refresh() {
        connections = runCatching { services.repository.connections() }.getOrDefault(emptyList())
    }
    LaunchedEffect(Unit) { refresh() }

    fun connected(p: SyncProvider) = connections.firstOrNull { it.provider == p }

    fun describe(r: SyncResult): String {
        val parts = mutableListOf("Added ${r.added}", "updated ${r.updated}")
        if (r.unchanged > 0) parts += "${r.unchanged} already up to date"
        if (r.unmatched.isNotEmpty()) {
            val shown = r.unmatched.take(5).joinToString(", ")
            parts += "couldn't match ${r.unmatched.size}: $shown${if (r.unmatched.size > 5) ", +${r.unmatched.size - 5} more" else ""}"
        }
        if (r.cancelled) parts += "(cancelled early)"
        return parts.joinToString(" · ") + "."
    }

    suspend fun runImport(items: List<SyncItem>) {
        if (items.isEmpty()) {
            message = false to "Nothing to import — no watched or rated items found."
            return
        }
        val result = services.repository.import(
            items,
            onProgress = { done, total -> status = "Matching $done/$total…" },
            isCancelled = { cancelled },
        )
        message = false to describe(result)
    }

    fun guarded(provider: SyncProvider, fallback: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = provider
        message = null
        cancelled = false
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = true to (e.message?.takeIf { it.isNotBlank() } ?: fallback)
            } finally {
                busy = null
                status = null
                simklCode = null
                refresh()
            }
        }
    }

    fun syncSimkl() = guarded(SyncProvider.SIMKL, "Simkl sync failed.") {
        if (connected(SyncProvider.SIMKL) == null) {
            val code = withContext(Dispatchers.IO) { services.simkl.start() }
            simklCode = "${code.userCode}|${code.verificationUri}"
            val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
            var interval = code.interval
            while (true) {
                check(!cancelled && System.currentTimeMillis() < deadline) { "Simkl sign-in timed out." }
                delay(interval * 1000L)
                when (withContext(Dispatchers.IO) { services.simkl.poll(code.deviceCode) }) {
                    SimklPoll.Connected -> break
                    SimklPoll.SlowDown -> interval += 5
                    SimklPoll.Pending -> Unit
                }
            }
            simklCode = null
        }
        status = "Fetching your Simkl library…"
        runImport(withContext(Dispatchers.IO) { services.simkl.items() })
    }

    fun syncPlex() = guarded(SyncProvider.PLEX, "Plex sync failed.") {
        if (plexToken == null) {
            val pin = withContext(Dispatchers.IO) { services.plex.startPin() }
            uriHandler.openUri(pin.authUrl)
            status = "Approve CinemArchive in the Plex page that just opened…"
            val deadline = System.currentTimeMillis() + 5 * 60 * 1000L
            while (plexToken == null) {
                check(!cancelled && System.currentTimeMillis() < deadline) { "Plex sign-in timed out." }
                delay(2000)
                plexToken = withContext(Dispatchers.IO) { services.plex.pollPin(pin) }
            }
        }
        val token = checkNotNull(plexToken)
        val server = withContext(Dispatchers.IO) { services.plex.servers(token) }.firstOrNull()
            ?: error("No reachable Plex server found on your account.")
        status = "Reading ${server.name}…"
        val items = withContext(Dispatchers.IO) { services.plex.items(server.uri, token) }
        services.repository.recordConnection(SyncProvider.PLEX, server.uri, server.name)
        runImport(items)
    }

    fun syncEmby() = guarded(SyncProvider.EMBY, "Emby sync failed.") {
        val session = withContext(Dispatchers.IO) { services.emby.signIn(embyUrl, embyUser, embyPassword) }
        embyPassword = ""
        status = "Reading your Emby library…"
        val items = withContext(Dispatchers.IO) { services.emby.items(session) }
        services.repository.recordConnection(SyncProvider.EMBY, session.baseUrl, session.username)
        runImport(items)
    }

    fun disconnect(provider: SyncProvider) {
        scope.launch {
            try {
                if (provider == SyncProvider.SIMKL) withContext(Dispatchers.IO) { services.simkl.disconnect() }
                else services.repository.removeConnection(provider)
                if (provider == SyncProvider.PLEX) plexToken = null
                refresh()
                message = false to "Disconnected. Previously imported titles stay in your library."
            } catch (e: Exception) {
                message = true to (e.message ?: "Could not disconnect.")
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp, 8.dp, 20.dp, 2.dp)) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            Text(
                "Import & sync",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = if (showBack) 4.dp else 0.dp),
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            item {
                ReadingWidthColumn {
                    Text(
                        "Import only: watched titles and ratings are copied in. Nothing is sent back to these " +
                            "services, and existing ratings and viewings are never overwritten. Plex and Emby are " +
                            "contacted straight from your phone, so the server must be reachable over https. Plex " +
                            "tokens and Emby passwords are not stored.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            message?.let { (isError, text) ->
                item {
                    ReadingWidthColumn {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(14.dp)) }
                    }
                }
            }
            item {
                ReadingWidthColumn {
                    ProviderCard(
                        name = "Simkl",
                        connection = connected(SyncProvider.SIMKL),
                        busy = busy == SyncProvider.SIMKL,
                        enabled = busy == null,
                        onSync = ::syncSimkl,
                        onDisconnect = { disconnect(SyncProvider.SIMKL) },
                    ) {
                        simklCode?.split("|")?.let { (code, uri) ->
                            Text(
                                "Enter code $code at $uri",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            item {
                ReadingWidthColumn {
                    ProviderCard(
                        name = "Plex",
                        connection = connected(SyncProvider.PLEX),
                        busy = busy == SyncProvider.PLEX,
                        enabled = busy == null,
                        onSync = ::syncPlex,
                        onDisconnect = { disconnect(SyncProvider.PLEX) },
                    ) {}
                }
            }
            item {
                ReadingWidthColumn {
                    ProviderCard(
                        name = "Emby",
                        connection = connected(SyncProvider.EMBY),
                        busy = busy == SyncProvider.EMBY,
                        enabled = busy == null && embyUrl.isNotBlank() && embyUser.isNotBlank() && embyPassword.isNotEmpty(),
                        onSync = ::syncEmby,
                        onDisconnect = { disconnect(SyncProvider.EMBY) },
                    ) {
                        OutlinedTextField(
                            value = embyUrl,
                            onValueChange = { embyUrl = it },
                            label = { Text("Server (e.g. emby.example.com)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = embyUser,
                            onValueChange = { embyUser = it },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = embyPassword,
                            onValueChange = { embyPassword = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (busy != null) {
                item {
                    ReadingWidthColumn {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                status ?: "Working…",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = { cancelled = true }) { Text("Cancel") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    name: String,
    connection: IntegrationConnection?,
    busy: Boolean,
    enabled: Boolean,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    extra: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            connection?.let {
                val detail = listOfNotNull(
                    it.accountLabel,
                    it.lastSyncedAt?.take(10)?.let { d -> "last synced $d" },
                ).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            extra()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onSync, enabled = enabled) {
                    if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(end = 8.dp).size(16.dp))
                    Text(if (connection != null) "Sync now" else "Connect & import")
                }
                if (connection != null) {
                    OutlinedButton(onClick = onDisconnect, enabled = !busy) { Text("Disconnect") }
                }
            }
        }
    }
}
