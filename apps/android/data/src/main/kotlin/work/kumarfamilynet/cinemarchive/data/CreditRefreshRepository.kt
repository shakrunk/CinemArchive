package work.kumarfamilynet.cinemarchive.data

import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.core.model.MediaDetails

fun interface CreditMetadataFetcher { suspend fun fetch(title: TitleEntity): MediaDetails }

data class CatalogRefreshProgress(val completed: Int, val total: Int, val title: String)
data class CatalogRefreshReport(val refreshed: Int, val unchanged: Int, val failures: List<String>)

/** Refreshes catalog metadata without changing tracking history or existing parent IDs. */
class CreditRefreshRepository(
    private val database: LibraryDatabase,
    private val outbox: MutationOutbox,
    private val fetcher: CreditMetadataFetcher,
    private val ownerId: String,
    private val isCurrentOwner: () -> Boolean,
) {
    private val refreshMutex = Mutex()

    /** Returns true when a changed credit set was saved locally and durably queued. */
    suspend fun refresh(titleId: String): Boolean = refresh(titleId, false)
    suspend fun refreshMetadata(titleId: String): Boolean = refresh(titleId, true)
    suspend fun refreshAll(progress: (CatalogRefreshProgress) -> Unit = {}): CatalogRefreshReport {
        check(isCurrentOwner()) { "This sign-in has ended" }
        val titles = database.titleDao().observeAllTitles().first()
        var changed = 0; var unchanged = 0
        val failures = mutableListOf<String>()
        titles.forEachIndexed { index, title ->
            check(isCurrentOwner()) { "This sign-in has ended" }
            progress(CatalogRefreshProgress(index, titles.size, title.title))
            try { if (refreshMetadata(title.id)) changed++ else unchanged++ }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                check(isCurrentOwner()) { "This sign-in has ended" }
                failures += "${title.title}: ${failure.message ?: "Could not refresh"}"
            }
            progress(CatalogRefreshProgress(index + 1, titles.size, title.title))
        }
        return CatalogRefreshReport(changed, unchanged, failures)
    }
    private suspend fun refresh(titleId: String, full: Boolean): Boolean = refreshMutex.withLock {
        check(isCurrentOwner()) { "This sign-in has ended" }
        val beforeFetch = checkNotNull(database.titleDao().getById(titleId)) { "Title is no longer in your library" }
        val fresh = fetcher.fetch(beforeFetch)
        check(isCurrentOwner()) { "This sign-in has ended" }
        require(fresh.tmdbId == beforeFetch.tmdbId && fresh.type.name == beforeFetch.type) { "Catalog returned another title" }
        outbox.atomically {
            check(isCurrentOwner()) { "This sign-in has ended" }
            val current = checkNotNull(database.titleDao().getById(titleId)) { "Title is no longer in your library" }
            require(current.tmdbId == beforeFetch.tmdbId && current.type == beforeFetch.type) { "Title identity changed during refresh" }
            var titleChanged = false
            if (full) {
                check(current == beforeFetch) { "This title changed while refreshing. Try again to keep your newer edits." }
                val patch = catalogMetadataPatch(current, fresh)
                if (patch.length() > 0) {
                    val guard = captureViewingTitleGuard(current, outbox.pendingEntries(), ownerId)
                    check(guard.known) { "Sync or review earlier title changes before refreshing metadata." }
                    outbox.enqueueTitleMetadata(current, patch, ownerId)
                    database.titleDao().upsertAll(listOf(current.withTitleMetadata(patch)))
                    titleChanged = true
                }
            }
            val old = readCreditRows(database, titleId)
            val queuedParents = enqueueMissingEpisodeCatalog(database, outbox, titleId, ownerId, fresh)
            val updatedEpisodes = full && enqueueKnownEpisodeMetadata(database, outbox, titleId, ownerId, fresh)
            val oldByIdentity = old.associateBy { it.identity }
            val next = mutableListOf<CreditRow>()
            val fetchedProfiles = mutableSetOf<String>()
            val fetchedCounts = mutableSetOf<String>()
            fun add(row: CreditRow) {
                val previous = oldByIdentity[row.identity]
                if (row.profileUrl != null) fetchedProfiles += row.identity
                if (row.episodeCount != null) fetchedCounts += row.identity
                next += row.copy(id = previous?.id ?: UUID.randomUUID().toString(),
                    profileUrl = row.profileUrl ?: previous?.profileUrl, episodeCount = row.episodeCount ?: previous?.episodeCount)
            }
            fresh.cast.distinctBy { it.tmdbPersonId }.forEach {
                add(CreditRow("title_cast", "", titleId, titleId, it.tmdbPersonId, it.name, it.characterName, it.order,
                    profileUrl = it.profileUrl, episodeCount = it.episodeCount))
            }
            if (full && fresh.cast.isEmpty()) next += old.filter { it.table == "title_cast" }
            fresh.crew.distinctBy { it.tmdbPersonId to it.job }.forEach {
                add(CreditRow("title_crew", "", titleId, titleId, it.tmdbPersonId, it.name, it.job, department = it.department, profileUrl = it.profileUrl))
            }
            if (full && fresh.crew.isEmpty()) next += old.filter { it.table == "title_crew" }
            val episodes = database.episodeDao().observeEpisodes(titleId).first()
            database.seasonDao().observeSeasons(titleId).first().forEach { season ->
                val remoteSeason = fresh.seasons.firstOrNull { it.seasonNumber == season.seasonNumber }
                // A failed/empty season fetch must never erase the previously known credits.
                if (remoteSeason != null && remoteSeason.episodes.isNotEmpty() && remoteSeason.cast.isNotEmpty()) {
                    remoteSeason.cast.distinctBy { it.tmdbPersonId }.forEach {
                        add(CreditRow("season_cast", "", titleId, season.id, it.tmdbPersonId, it.name, it.characterName, it.order,
                            profileUrl = it.profileUrl, episodeCount = it.episodeCount))
                    }
                } else next += old.filter { it.table == "season_cast" && it.parentId == season.id }
                episodes.filter { it.seasonId == season.id }.forEach { episode ->
                    val remote = remoteSeason?.episodes?.firstOrNull { it.episodeNumber == episode.episodeNumber }
                    if (remote != null && remote.crew.isNotEmpty()) {
                        remote.crew.distinctBy { it.tmdbPersonId to it.job }.forEach {
                            add(CreditRow("episode_crew", "", titleId, episode.id, it.tmdbPersonId, it.name, it.job))
                        }
                    } else next += old.filter { it.table == "episode_crew" && it.parentId == episode.id }
                }
            }
            val nextIdentities = next.map { it.identity }.toSet()
            val removed = old.filter { it.identity !in nextIdentities }
            val changed = next.filter { oldByIdentity[it.identity] != it }
            if (removed.isEmpty() && changed.isEmpty()) return@atomically queuedParents || updatedEpisodes || titleChanged
            val operations = JSONArray()
            // Ownership/existence barrier, with no tracking columns or timestamp changes.
            operations.put(JSONObject().put("table", "titles").put("action", "update")
                .put("key", JSONObject().put("id", titleId)).put("values", JSONObject()))
            removed.forEach { operations.put(JSONObject().put("table", it.table).put("action", "delete").put("key", it.key())) }
            changed.forEach { operations.put(JSONObject().put("table", it.table).put("action", "put").put("key", it.key())
                .put("values", it.values(it.identity in fetchedProfiles, it.identity in fetchedCounts))) }
            removed.forEach { deleteCreditRow(database, it) }
            writeCreditRows(database, changed)
            outbox.enqueue("title_credits", titleId, "refresh", JSONObject().put("ownerId", ownerId).put("titleId", titleId)
                .put("operations", operations).put("protectedKeys", JSONArray((old + next).map { "${it.table}:${it.id}" }.distinct())))
            true
        }
    }
}
