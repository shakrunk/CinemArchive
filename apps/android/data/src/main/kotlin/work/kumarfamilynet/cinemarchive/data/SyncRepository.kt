package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult
import work.kumarfamilynet.cinemarchive.core.model.MediaType

data class SyncResult(
    val added: Int,
    val updated: Int,
    val unchanged: Int,
    val unmatched: List<String>,
    val cancelled: Boolean,
    val failed: List<String> = emptyList(),
)

data class IntegrationConnection(
    val provider: SyncProvider,
    val serverUrl: String?,
    val accountLabel: String?,
    val lastSyncedAt: String?,
)

/** Provider imports use the normal owner-scoped library journal. New titles, their complete
 * catalog/history graph and provider identity become durable together before success is reported. */
class SyncRepository(
    private val libraryRepository: LibraryRepository,
    private val discoverRepository: DiscoverRepository,
    private val authRepository: SessionSource,
    private val client: SupabaseRestClient,
    private val newTitles: ProviderImportAdmission,
    private val current: () -> Boolean,
    private val sync: suspend () -> Unit,
) {
    fun isCurrent(): Boolean = current()
    private fun fence() { if (!current()) throw CancellationException("Account changed.") }
    suspend fun synchronize() { fence(); sync(); fence() }

    suspend fun import(
        items: List<SyncItem>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): SyncResult = withContext(Dispatchers.IO) {
        fence()
        val added = AtomicInteger()
        val updated = AtomicInteger()
        val unchanged = AtomicInteger()
        val unmatched = java.util.Collections.synchronizedList(mutableListOf<String>())
        val failed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val done = AtomicInteger()
        val permits = Semaphore(3)
        val writeLock = Mutex()

        coroutineScope {
            items.map { item ->
                async {
                    if (isCancelled()) return@async
                    permits.withPermit {
                        fence()
                        if (isCancelled()) return@withPermit
                        val label = item.year?.let { "${item.title} ($it)" } ?: item.title
                        var resolved = false
                        try {
                            val match = resolve(item)
                            fence()
                            if (isCancelled()) return@withPermit
                            if (match == null) {
                                unmatched += label
                            } else {
                                resolved = true
                                val existingId = libraryRepository.findLibraryTitleId(match.tmdbId, match.type)
                                // Metadata fetch is independent; serialize the duplicate recheck and
                                // durable admission so repeated provider rows cannot race each other.
                                val details = if (existingId == null) discoverRepository.fetchDetails(match) else null
                                fence()
                                if (isCancelled()) return@withPermit
                                writeLock.withLock {
                                    fence()
                                    if (isCancelled()) return@withLock
                                    val id = existingId ?: libraryRepository.findLibraryTitleId(match.tmdbId, match.type)
                                    if (id != null) {
                                        if (mergeInto(id, match.type, item)) updated.incrementAndGet() else unchanged.incrementAndGet()
                                    } else {
                                        val graph = details ?: discoverRepository.fetchDetails(match)
                                        fence()
                                        if (isCancelled()) return@withLock
                                        check(graph.tmdbId == match.tmdbId && graph.type == match.type) {
                                            "The catalog returned a different title. Try matching again."
                                        }
                                        if (newTitles.addNew(graph, item)) {
                                            added.incrementAndGet()
                                        } else {
                                            val racedId = libraryRepository.findLibraryTitleId(match.tmdbId, match.type)
                                            check(racedId != null) { "This title is already queued. Retry after it appears in your library." }
                                            if (mergeInto(racedId, match.type, item)) updated.incrementAndGet() else unchanged.incrementAndGet()
                                        }
                                    }
                                }
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            fence()
                            if (resolved) failed += "$label: ${error.message ?: "could not save"}" else unmatched += label
                        } finally {
                            if (current()) onProgress(done.incrementAndGet(), items.size)
                        }
                    }
                }
            }.awaitAll()
        }
        fence()
        SyncResult(added.get(), updated.get(), unchanged.get(), unmatched.toList(), isCancelled(), failed.toList())
    }

    private suspend fun resolve(item: SyncItem): MediaSearchResult? {
        item.ids.tmdb?.let { return MediaSearchResult(it, item.title, item.year, item.type, null, null) }
        item.ids.imdb?.let { id -> discoverRepository.findByExternalId("imdb_id", id, item.type)?.let { return it } }
        item.ids.tvdb?.let { id -> discoverRepository.findByExternalId("tvdb_id", id.toString(), item.type)?.let { return it } }
        return pickBestMatch(discoverRepository.searchMedia(item.title), item.title, item.year, item.type)
    }

    /** True when something changed. */
    private suspend fun mergeInto(titleId: String, type: MediaType, item: SyncItem): Boolean {
        val snapshot = libraryRepository.syncSnapshot(titleId) ?: return false
        val patch = planMerge(snapshot, type, item) ?: return false
        val now = Instant.now().toString()
        patch.rating?.let { libraryRepository.updateTitleRating(titleId, it, now) }
        patch.status?.let { libraryRepository.updateTitleStatus(titleId, it, now) }
        patch.newViewingDates.forEach { libraryRepository.logViewing(titleId, it) }
        return true
    }

    // ─── Connections (owner-only rows; no secrets) ───────────────────────────

    suspend fun connections(): List<IntegrationConnection> = withContext(Dispatchers.IO) {
        fence()
        val session = authRepository.currentSession() ?: return@withContext emptyList()
        fence()
        val body = client.get("integration_connections", "select=provider,server_url,account_label,last_synced_at&user_id=eq.${session.userId}", session.accessToken)
        fence()
        val rows = JSONArray(body)
        (0 until rows.length()).mapNotNull { i ->
            val r = rows.getJSONObject(i)
            val provider = SyncProvider.fromWire(r.getString("provider")) ?: return@mapNotNull null
            IntegrationConnection(
                provider,
                r.optString("server_url").takeIf { it.isNotEmpty() && it != "null" },
                r.optString("account_label").takeIf { it.isNotEmpty() && it != "null" },
                r.optString("last_synced_at").takeIf { it.isNotEmpty() && it != "null" },
            )
        }
    }

    suspend fun recordConnection(provider: SyncProvider, serverUrl: String? = null, accountLabel: String? = null) =
        withContext(Dispatchers.IO) {
            fence()
            val session = authRepository.currentSession() ?: return@withContext
            fence()
            val row = JSONObject()
                .put("user_id", session.userId)
                .put("provider", provider.wire)
                .put("server_url", serverUrl ?: JSONObject.NULL)
                .put("account_label", accountLabel ?: JSONObject.NULL)
                .put("last_synced_at", Instant.now().toString())
            client.upsert("integration_connections", session.accessToken, JSONArray().put(row).toString(), "user_id,provider")
            fence()
            Unit
        }

    suspend fun removeConnection(provider: SyncProvider) = withContext(Dispatchers.IO) {
        fence()
        val session = authRepository.currentSession() ?: return@withContext
        fence()
        client.delete("integration_connections", "user_id=eq.${session.userId}&provider=eq.${provider.wire}", session.accessToken)
        fence()
        Unit
    }
}

/** Everything the Import & sync screen needs, bundled so the screen takes one dependency. */
class SyncServices(
    val repository: SyncRepository,
    val simkl: SimklApi,
    val plex: PlexClient,
    val emby: EmbyClient,
) {
    companion object {
        fun create(
            library: LibraryRepository,
            discover: DiscoverRepository,
            auth: SessionSource,
            client: SupabaseRestClient,
            plexClientId: String,
            newTitles: ProviderImportAdmission,
            current: () -> Boolean,
            synchronize: suspend () -> Unit,
        ): SyncServices {
            val http = okhttp3.OkHttpClient()
            return SyncServices(
                repository = SyncRepository(library, discover, auth, client, newTitles, current, synchronize),
                simkl = SimklApi(client, auth),
                plex = PlexClient(http, plexClientId),
                emby = EmbyClient(http),
            )
        }
    }
}
