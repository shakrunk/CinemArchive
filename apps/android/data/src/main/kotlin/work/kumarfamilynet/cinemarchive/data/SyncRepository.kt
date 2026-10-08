package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
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
import work.kumarfamilynet.cinemarchive.core.model.AddTitleRequest
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult
import work.kumarfamilynet.cinemarchive.core.model.MediaType

data class SyncResult(
    val added: Int,
    val updated: Int,
    val unchanged: Int,
    val unmatched: List<String>,
    val cancelled: Boolean,
)

data class IntegrationConnection(
    val provider: SyncProvider,
    val serverUrl: String?,
    val accountLabel: String?,
    val lastSyncedAt: String?,
)

/**
 * Orchestrates third-party import on Android — the counterpart of `resolveSyncItems` +
 * `applySyncOutcome` in `apps/web/src/lib/sync`. Items resolve to TMDB (by id, then name+year),
 * then either merge into the existing library title via [planMerge] (never overwriting) or are
 * added through [LibraryRepository.addTitle], so every write rides the normal Room + outbox
 * path.
 *
 * Unlike web, no `external_title_links` rows are written: new titles reach the server later via
 * the outbox, so a link upserted now would violate its `titles(id)` foreign key. Dedupe is by
 * TMDB id either way; the links table is provenance only.
 */
class SyncRepository(
    private val libraryRepository: LibraryRepository,
    private val discoverRepository: DiscoverRepository,
    private val authRepository: SessionSource,
    private val client: SupabaseRestClient,
) {
    suspend fun import(
        items: List<SyncItem>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): SyncResult = withContext(Dispatchers.IO) {
        val added = AtomicInteger()
        val updated = AtomicInteger()
        val unchanged = AtomicInteger()
        val unmatched = java.util.Collections.synchronizedList(mutableListOf<String>())
        val done = AtomicInteger()
        val permits = Semaphore(3)
        val writeLock = Mutex()

        coroutineScope {
            items.map { item ->
                async {
                    if (isCancelled()) return@async
                    permits.withPermit {
                        val label = item.year?.let { "${item.title} ($it)" } ?: item.title
                        try {
                            val match = resolve(item)
                            if (match == null) {
                                unmatched += label
                            } else {
                                val existingId = libraryRepository.findLibraryTitleId(match.tmdbId, match.type)
                                // Fetch outside the lock; only the Room write is serialized so two
                                // items for the same title can't both pass the "not in library" check.
                                val details = if (existingId == null) discoverRepository.fetchDetails(match) else null
                                writeLock.withLock {
                                    val id = existingId ?: libraryRepository.findLibraryTitleId(match.tmdbId, match.type)
                                    if (id != null) {
                                        if (mergeInto(id, match.type, item)) updated.incrementAndGet() else unchanged.incrementAndGet()
                                    } else {
                                        addNew(details ?: discoverRepository.fetchDetails(match), item)
                                        added.incrementAndGet()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            unmatched += label
                        } finally {
                            onProgress(done.incrementAndGet(), items.size)
                        }
                    }
                }
            }.awaitAll()
        }
        SyncResult(added.get(), updated.get(), unchanged.get(), unmatched.toList(), isCancelled())
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

    private suspend fun addNew(details: work.kumarfamilynet.cinemarchive.core.model.MediaDetails, item: SyncItem) {
        val dates = item.watchedDates.sorted()
        libraryRepository.addTitle(
            AddTitleRequest(
                details = details,
                status = item.status,
                rating = item.rating,
                notes = null,
                // addTitle seeds one viewing for WATCHED titles; remaining dates are logged below.
                watchedOn = if (item.status == LibraryStatus.WATCHED && item.type == MediaType.MOVIE) dates.lastOrNull() else null,
            ),
        )
        if (item.status == LibraryStatus.WATCHED && item.type == MediaType.MOVIE && dates.size > 1) {
            libraryRepository.findLibraryTitleId(details.tmdbId, details.type)?.let { id ->
                dates.dropLast(1).forEach { libraryRepository.logViewing(id, it) }
            }
        }
    }

    // ─── Connections (owner-only rows; no secrets) ───────────────────────────

    suspend fun connections(): List<IntegrationConnection> = withContext(Dispatchers.IO) {
        val token = authRepository.currentSession()?.accessToken ?: return@withContext emptyList()
        val body = client.get("integration_connections", "select=provider,server_url,account_label,last_synced_at", token)
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
            val session = authRepository.currentSession() ?: return@withContext
            val row = JSONObject()
                .put("user_id", session.userId)
                .put("provider", provider.wire)
                .put("server_url", serverUrl ?: JSONObject.NULL)
                .put("account_label", accountLabel ?: JSONObject.NULL)
                .put("last_synced_at", Instant.now().toString())
            client.upsert("integration_connections", session.accessToken, JSONArray().put(row).toString(), "user_id,provider")
            Unit
        }

    suspend fun removeConnection(provider: SyncProvider) = withContext(Dispatchers.IO) {
        val session = authRepository.currentSession() ?: return@withContext
        client.delete("integration_connections", "user_id=eq.${session.userId}&provider=eq.${provider.wire}", session.accessToken)
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
        ): SyncServices {
            val http = okhttp3.OkHttpClient()
            return SyncServices(
                repository = SyncRepository(library, discover, auth, client),
                simkl = SimklApi(client, auth),
                plex = PlexClient(http, plexClientId),
                emby = EmbyClient(http),
            )
        }
    }
}
