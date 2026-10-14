package work.kumarfamilynet.cinemarchive.feature.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.data.IntegrationConnection
import work.kumarfamilynet.cinemarchive.data.SimklPoll
import work.kumarfamilynet.cinemarchive.data.SyncItem
import work.kumarfamilynet.cinemarchive.data.SyncProvider
import work.kumarfamilynet.cinemarchive.data.SyncResult
import work.kumarfamilynet.cinemarchive.data.SyncServices
import work.kumarfamilynet.cinemarchive.data.letterboxdToSyncItems
import work.kumarfamilynet.cinemarchive.data.parseLetterboxdCsv

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
    backupContent: @Composable () -> Unit = {},
) {
    key(services) {
        if (services.repository.isCurrent()) ImportSyncContent(services, onBack, showBack, backupContent)
    }
}

@Composable
private fun ImportSyncContent(
    services: SyncServices,
    onBack: () -> Unit,
    showBack: Boolean,
    backupContent: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
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

    fun ensureCurrent() {
        if (!services.repository.isCurrent()) throw CancellationException("Account changed.")
    }
    suspend fun refresh() {
        ensureCurrent()
        val latest = try { services.repository.connections() } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) { emptyList() }
        ensureCurrent()
        connections = latest
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
        if (r.failed.isNotEmpty()) {
            parts += "${r.failed.size} not saved: ${r.failed.take(3).joinToString("; ")}"
        }
        if (r.added > 0 || r.updated > 0) parts += "saved on this device; pending changes sync when connected"
        if (r.added > 0) parts += "use Saved imports above to review or retry a new-title import"
        if (r.cancelled) parts += "(cancelled early)"
        return parts.joinToString(" · ") + "."
    }

    suspend fun runImport(items: List<SyncItem>) {
        ensureCurrent()
        if (items.isEmpty()) {
            message = false to "Nothing to import — no watched or rated items found."
            return
        }
        val result = services.repository.import(
            items,
            onProgress = { done, total -> if (services.repository.isCurrent()) status = "Matching $done/$total…" },
            isCancelled = { cancelled },
        )
        ensureCurrent()
        message = result.failed.isNotEmpty() to describe(result)
        if (result.added > 0 || result.updated > 0 || result.unchanged > 0) scope.launch {
            try { services.repository.synchronize() } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Durable commands remain available in Saved imports. */ }
        }
    }

    fun guarded(provider: SyncProvider, fallback: String, block: suspend () -> Unit) {
        if (busy != null || !services.repository.isCurrent()) return
        busy = provider
        message = null
        cancelled = false
        scope.launch {
            try {
                ensureCurrent()
                block()
                ensureCurrent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (services.repository.isCurrent()) message = true to (e.message?.takeIf { it.isNotBlank() } ?: fallback)
            } finally {
                if (services.repository.isCurrent()) {
                    busy = null
                    status = null
                    simklCode = null
                    refresh()
                }
            }
        }
    }

    fun syncSimkl() = guarded(SyncProvider.SIMKL, "Simkl sync failed.") {
        if (connected(SyncProvider.SIMKL) == null) {
            val code = withContext(Dispatchers.IO) { services.simkl.start() }
            ensureCurrent()
            simklCode = "${code.userCode}|${code.verificationUri}"
            val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
            var interval = code.interval
            while (true) {
                check(!cancelled && System.currentTimeMillis() < deadline) { "Simkl sign-in timed out." }
                delay(interval * 1000L)
                ensureCurrent()
                when (withContext(Dispatchers.IO) { services.simkl.poll(code.deviceCode) }) {
                    SimklPoll.Connected -> break
                    SimklPoll.SlowDown -> interval += 5
                    SimklPoll.Pending -> Unit
                }
            }
            ensureCurrent()
            simklCode = null
        }
        ensureCurrent()
        status = "Fetching your Simkl library…"
        runImport(withContext(Dispatchers.IO) { services.simkl.items() })
    }

    fun syncPlex() = guarded(SyncProvider.PLEX, "Plex sync failed.") {
        if (plexToken == null) {
            val pin = withContext(Dispatchers.IO) { services.plex.startPin() }
            ensureCurrent()
            uriHandler.openUri(pin.authUrl)
            status = "Approve CinemArchive in the Plex page that just opened…"
            val deadline = System.currentTimeMillis() + 5 * 60 * 1000L
            while (plexToken == null) {
                check(!cancelled && System.currentTimeMillis() < deadline) { "Plex sign-in timed out." }
                delay(2000)
                ensureCurrent()
                val token = withContext(Dispatchers.IO) { services.plex.pollPin(pin) }
                ensureCurrent()
                plexToken = token
            }
        }
        val token = checkNotNull(plexToken)
        val server = withContext(Dispatchers.IO) { services.plex.servers(token) }.firstOrNull()
            ?: error("No reachable Plex server found on your account.")
        ensureCurrent()
        status = "Reading ${server.name}…"
        val items = withContext(Dispatchers.IO) { services.plex.items(server.uri, token) }
        services.repository.recordConnection(SyncProvider.PLEX, server.uri, server.name)
        runImport(items)
    }

    fun syncEmby() = guarded(SyncProvider.EMBY, "Emby sync failed.") {
        val session = withContext(Dispatchers.IO) { services.emby.signIn(embyUrl, embyUser, embyPassword) }
        ensureCurrent()
        embyPassword = ""
        status = "Reading your Emby library…"
        val items = withContext(Dispatchers.IO) { services.emby.items(session) }
        services.repository.recordConnection(SyncProvider.EMBY, session.baseUrl, session.username)
        runImport(items)
    }

    fun importLetterboxd(uri: Uri) = guarded(SyncProvider.LETTERBOXD, "Letterboxd import failed.") {
        val (text, fileName) = withContext(Dispatchers.IO) {
            val body = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Could not read that file.")
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            body to name.orEmpty()
        }
        ensureCurrent()
        val rows = parseLetterboxdCsv(text)
        check(rows.isNotEmpty()) { "No films found in that CSV." }
        // watchlist.csv rows land on the watchlist; everything else is history.
        val status = if (fileName.contains("watchlist", ignoreCase = true)) LibraryStatus.WATCHLIST else LibraryStatus.WATCHED
        runImport(letterboxdToSyncItems(rows, status))
    }
    val letterboxdPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && services.repository.isCurrent()) importLetterboxd(uri)
    }

    fun disconnect(provider: SyncProvider) {
        if (!services.repository.isCurrent()) return
        scope.launch {
            try {
                ensureCurrent()
                if (provider == SyncProvider.SIMKL) withContext(Dispatchers.IO) { services.simkl.disconnect() }
                else services.repository.removeConnection(provider)
                ensureCurrent()
                if (provider == SyncProvider.PLEX) plexToken = null
                refresh()
                message = false to "Disconnected. Previously imported titles stay in your library."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (services.repository.isCurrent()) message = true to (e.message ?: "Could not disconnect.")
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
            item { backupContent() }
            item { ProviderMergeSection(services.repository.merges) }
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
            item {
                ReadingWidthColumn {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp)) {
                            Text("Letterboxd", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Pick one file from your Letterboxd data export (watched.csv, ratings.csv, diary.csv or " +
                                    "watchlist.csv). Films are matched to TMDB by name and year; anything that can't be " +
                                    "matched confidently is reported, not guessed.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { letterboxdPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain")) },
                                enabled = busy == null,
                            ) {
                                if (busy == SyncProvider.LETTERBOXD) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(end = 8.dp).size(16.dp))
                                Text("Import CSV")
                            }
                        }
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
