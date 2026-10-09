package work.kumarfamilynet.cinemarchive.data

import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.*

/** Immutable capture is saveable as a string, including identity and the exact observed revisions. */
data class EpisodeBulkOpening(val json: String) {
    val count: Int get() = JSONObject(json).getJSONArray("watches").length()
    val marksSeriesWatched: Boolean get() = JSONObject(json).has("titleEffect")
    val coarseCount: Int get() = JSONObject(json).getJSONArray("seasons").length()
    val operationId: String get() = JSONObject(json).getString("operationId")
}
data class EpisodeBulkPending(val id: String, val count: Int, val needsReview: Boolean, val error: String?, val titleName: String = "Series")
data class EpisodeBulkComparison(val original: String, val replacement: EpisodeBulkOpening, val snapshot: EpisodeBulkSnapshot, val localState: String)

class EpisodeBulkRepository(
    private val db: LibraryDatabase,
    private val outbox: MutationOutbox,
    private val owner: String,
    private val isCurrentOwner: () -> Boolean,
    private val remote: EpisodeBulkRemote,
    private val synchronize: suspend () -> Unit,
    private val replay: suspend (suspend () -> Unit) -> Unit,
    private val requestSync: suspend () -> Unit = synchronize,
) {
    private suspend fun active() { currentCoroutineContext().ensureActive(); check(isCurrentOwner()) { "This sign-in has ended." } }
    val savedRequests = db.outboxDao().observePending().map { rows ->
        if (!isCurrentOwner()) emptyList() else rows.filter { it.entityType == EPISODE_BULK }.map {
            EpisodeBulkPending(it.id, EpisodeBulkOpening(it.payloadJson).count, it.operation == "review", it.lastError, JSONObject(it.payloadJson).optString("titleName", "Series"))
        }
    }
    val retainedCount = db.episodeBulkAdmissionDao().observeAll().map { if (isCurrentOwner()) it.size else 0 }
    fun isActive(): Boolean = isCurrentOwner()

    /** The raw payload remains a string so exporting never rewrites the original admission bytes. */
    suspend fun exportOriginals(): String {
        active()
        val records = db.episodeBulkAdmissionDao().all()
        records.forEach { require(JSONObject(it.payloadJson).getString("ownerId") == owner) }
        active()
        return JSONObject().put("version", 1).put("originalRequests", JSONArray(records.map {
            JSONObject().put("operationId", it.operationId).put("payloadJson", it.payloadJson)
        })).toString()
    }

    fun pending(titleId: String) = db.outboxDao().observePending().map { rows ->
        rows.filter { it.entityType == EPISODE_BULK && it.entityId == titleId }.map {
            EpisodeBulkPending(it.id, EpisodeBulkOpening(it.payloadJson).count, it.operation == "review", it.lastError)
        }
    }
    suspend fun retry() { active(); synchronize(); active() }

    suspend fun prepare(titleId: String, seasonNumber: Int?): EpisodeBulkOpening = outbox.atomically {
        active()
        check(db.outboxDao().getPending().none { it.entityType == EPISODE_BULK && it.entityId == titleId }) {
            "Sync or review this series' saved pre-platform watches first."
        }
        capture(titleId, seasonNumber)
    }

    private suspend fun capture(titleId: String, seasonNumber: Int?, review: Pair<JSONObject, EpisodeBulkSnapshot>? = null): EpisodeBulkOpening {
        val title = checkNotNull(db.titleDao().getById(titleId)) { "This title was removed." }
        require(title.type == "TV")
        val seasons = db.seasonDao().observeSeasons(titleId).first().filter { if (seasonNumber == null) it.seasonNumber != 0 else it.seasonNumber == seasonNumber }
        val episodes = db.episodeDao().observeEpisodes(titleId).first().groupBy { it.seasonId }
        val watches = db.episodeWatchEventDao().observeAllWatchEvents().first()
        val watched = watches.map { it.episodeId }.toSet()
        val requests = JSONArray()
        if (review == null) {
            seasons.flatMap { episodes[it.id].orEmpty() }.filter { it.id !in watched }.forEach {
                requests.put(JSONObject().put("id", UUID.randomUUID().toString()).put("episodeId", it.id))
            }
        } else {
            val original = review.first.getJSONArray("watches")
            for (i in 0 until original.length()) {
                val row = original.getJSONObject(i)
                // A subsequent local deletion or independently logged watch is never undone by recovery.
                if (watches.any { it.id == row.getString("id") } && watches.none { it.episodeId == row.getString("episodeId") && it.id != row.getString("id") } &&
                    row.getString("episodeId") !in review.second.watchedEpisodeIds && db.episodeDao().getById(row.getString("episodeId")) != null) {
                    requests.put(JSONObject().put("id", UUID.randomUUID().toString()).put("episodeId", row.getString("episodeId")))
                }
            }
        }
        val coarse = JSONArray()
        seasons.filter { episodes[it.id].isNullOrEmpty() }.forEach { season ->
            val fresh = review?.second?.seasons?.firstOrNull { it.getString("id") == season.id }
            if (review == null || fresh != null) coarse.put(JSONObject().put("id", season.id).put("count", fresh?.getInt("episode_count") ?: season.episodeCount)
                .put("before", fresh?.getInt("episodes_watched") ?: season.episodesWatched)
                .put("baseline", fresh?.getString("updated_at") ?: season.updatedAt ?: JSONObject.NULL))
        }
        val payload = JSONObject().put("version", 1).put("operationId", UUID.randomUUID().toString()).put("ownerId", owner)
            .put("titleId", titleId).put("titleName", title.title).put("seasonNumber", seasonNumber ?: JSONObject.NULL).put("watches", requests).put("seasons", coarse)
        if (seasonNumber == null) {
            val queue = db.outboxDao().getPending()
            // Reviewed replacement retains its FIFO slot: later title drafts are overlays,
            // never predecessors of the operation being replaced.
            val before = if (review == null) queue else queue.takeWhile { it.id != review.first.getString("operationId") }
            val pending = pendingTitleIntents(before, titleId).filterNot { it.entityType == EPISODE_BULK }
            val predecessor = pending.lastOrNull()
            val accepted = predecessor?.takeIf { runCatching { checkedTitlePredecessor(it, owner) }.isSuccess }
            val baseline = if (predecessor == null) review?.second?.title?.getString("updated_at") ?: title.updatedAt else null
            payload.put("titleSnapshot", title.toString())
            payload.put("titleQueue", JSONArray(pending.map { "${it.id}:${it.operation}:${it.payloadJson}" }))
            payload.put("titleBefore", review?.second?.title?.getString("status")?.uppercase() ?: title.status)
            payload.put("titleEffect", titleMetadataPayload(owner, titleId, JSONObject().put("status", "watched"), baseline, accepted?.id))
        }
        active()
        val coarseRemaining = (0 until coarse.length()).any { coarse.getJSONObject(it).getInt("before") < coarse.getJSONObject(it).getInt("count") }
        check(requests.length() > 0 || coarseRemaining || (seasonNumber == null && title.status != "WATCHED") || review != null) { "Every episode in this scope already has a watch." }
        return EpisodeBulkOpening(payload.toString())
    }

    private fun entry(opening: EpisodeBulkOpening): OutboxEntity {
        val payload = JSONObject(opening.json)
        val entry = OutboxEntity(payload.getString("operationId"), EPISODE_BULK, payload.getString("titleId"), EPISODE_BULK_COMMAND, opening.json, 0)
        bulkPayload(entry, owner)
        return entry
    }

    suspend fun save(opening: EpisodeBulkOpening) = outbox.atomically {
        active()
        val entry = entry(opening)
        db.episodeBulkAdmissionDao().payload(entry.id)?.let { check(it == entry.payloadJson); return@atomically }
        check(db.outboxDao().getPending().none { it.entityType == EPISODE_BULK && it.entityId == entry.entityId }) { "Review the saved watches first." }
        bulkOperations(entry, owner, requireGuards = false)
        project(entry, initial = true)
        val guarded = runCatching { bulkOperations(entry, owner) }.isSuccess
        outbox.enqueueCaptured(entry.id, entry.entityType, entry.entityId, if (guarded) EPISODE_BULK_COMMAND else "review", entry.payloadJson)
        db.episodeBulkAdmissionDao().insert(EpisodeBulkAdmissionEntity(entry.id, entry.payloadJson))
        active()
    }

    private suspend fun project(entry: OutboxEntity, initial: Boolean) {
        val payload = bulkPayload(entry, owner)
        val title = checkNotNull(db.titleDao().getById(entry.entityId)) { "This title was removed." }
        if (initial && payload.has("titleEffect")) {
            val queue = pendingTitleIntents(db.outboxDao().getPending(), entry.entityId).filterNot { it.entityType == EPISODE_BULK }
            check(title.toString() == payload.getString("titleSnapshot") && sameCommandJson(
                JSONArray(queue.map { "${it.id}:${it.operation}:${it.payloadJson}" }), payload.getJSONArray("titleQueue"))) {
                "This title changed while confirming. Reopen the action to preserve the newer edit."
            }
        }
        val watches = payload.getJSONArray("watches")
        val existing = db.episodeWatchEventDao().observeAllWatchEvents().first()
        for (i in 0 until watches.length()) {
            val row = watches.getJSONObject(i)
            val episode = checkNotNull(db.episodeDao().getById(row.getString("episodeId"))) { "An episode was removed. Reopen this action." }
            require(episode.titleId == entry.entityId)
            require(existing.none { it.id == row.getString("id") }) { "This watch identity already exists." }
            if (initial) check(existing.none { it.episodeId == episode.id }) { "An episode was watched while confirming. Reopen this action." }
            db.episodeWatchEventDao().upsertAll(listOf(EpisodeWatchEventEntity(row.getString("id"), episode.id, null)))
        }
        val seasons = payload.getJSONArray("seasons")
        val local = db.seasonDao().observeSeasons(entry.entityId).first().associateBy { it.id }
        for (i in 0 until seasons.length()) {
            val row = seasons.getJSONObject(i)
            val season = checkNotNull(local[row.getString("id")])
            if (initial) check(db.episodeDao().observeEpisodes(entry.entityId).first().none { it.seasonId == season.id } && season.episodeCount == row.getInt("count") && season.episodesWatched == row.getInt("before") && (season.updatedAt ?: JSONObject.NULL) == row.get("baseline")) {
                "Season progress changed while confirming. Reopen this action."
            }
            db.seasonDao().upsertAll(listOf(season.copy(episodesWatched = row.getInt("count"))))
        }
        if (payload.has("titleEffect")) db.titleDao().getById(entry.entityId)?.let { db.titleDao().upsertAll(listOf(it.copy(status = "WATCHED"))) }
    }

    suspend fun compare(id: String): EpisodeBulkComparison = outbox.withFlushPaused {
        active()
        val entry = checkNotNull(db.outboxDao().getPending().find { it.id == id && it.entityType == EPISODE_BULK })
        check(entry.operation == "review") { "Retry sync to confirm the original request first." }
        val payload = bulkPayload(entry, owner)
        val snapshot = remote.current(entry.entityId)
        active()
        checkNotNull(snapshot.title) { "This series was removed on the server. Discard the saved request." }
        val number = if (payload.isNull("seasonNumber")) null else payload.getInt("seasonNumber")
        EpisodeBulkComparison(entry.payloadJson, capture(entry.entityId, number, payload to snapshot), snapshot, fingerprint(entry.entityId))
    }

    suspend fun applyReviewed(comparison: EpisodeBulkComparison) {
        replay { outbox.withFlushPaused {
        active()
        outbox.atomically {
            val old = reviewed(comparison.original)
            check(fingerprint(old.entityId) == comparison.localState) { "Local watches changed. Compare the saved request again." }
            val replacement = entry(comparison.replacement)
            bulkOperations(replacement, owner)
            rollback(old, comparison.snapshot)
            project(replacement, initial = false)
            if (JSONObject(replacement.payloadJson).has("titleEffect")) {
                val later = pendingTitleIntents(db.outboxDao().getPending().dropWhile { it.id != old.id }.drop(1), old.entityId)
                db.titleDao().getById(old.entityId)?.let { db.titleDao().upsertAll(listOf(overlayTitleIntents(it, later, owner))) }
            }
            check(db.episodeBulkAdmissionDao().replaceReviewed(old.id, old.payloadJson, replacement.id, EPISODE_BULK_COMMAND, replacement.payloadJson) == 1)
            db.episodeBulkAdmissionDao().insert(EpisodeBulkAdmissionEntity(replacement.id, replacement.payloadJson))
            active()
        }
        } }
        active(); requestSync()
    }

    suspend fun discard(id: String) {
        replay { outbox.withFlushPaused {
        active()
        val old = checkNotNull(db.outboxDao().getPending().find { it.id == id && it.entityType == EPISODE_BULK })
        check(old.operation == "review") { "Retry sync to confirm the original request before discarding." }
        val current = remote.current(old.entityId)
        active()
        outbox.atomically {
            reviewed(old.payloadJson)
            rollback(old, current)
            db.outboxDao().remove(old.id)
            active()
        }
        } }
        // Both replay and FIFO locks must be released before scheduling the next pull/push.
        active(); requestSync()
    }

    private suspend fun fingerprint(titleId: String): String {
        val ids = db.episodeDao().observeEpisodes(titleId).first().map { it.id }.toSet()
        val title = db.titleDao().getById(titleId)
        return JSONObject().put("status", title?.status).put("revision", title?.updatedAt)
            .put("seasons", JSONArray(db.seasonDao().observeSeasons(titleId).first().map { "${it.id}:${it.episodesWatched}:${it.updatedAt}" }.sorted()))
            .put("watches", JSONArray(db.episodeWatchEventDao().observeAllWatchEvents().first().filter { it.episodeId in ids }.map { "${it.id}:${it.episodeId}" }.sorted())).toString()
    }

    private suspend fun reviewed(original: String): OutboxEntity {
        active()
        val id = JSONObject(original).getString("operationId")
        return checkNotNull(db.outboxDao().getPending().find { it.id == id && it.operation == "review" && it.payloadJson == original }) {
            "This saved request changed. Compare it again."
        }
    }

    private suspend fun rollback(entry: OutboxEntity, snapshot: EpisodeBulkSnapshot) {
        val payload = bulkPayload(entry, owner)
        val watches = payload.getJSONArray("watches")
        for (i in 0 until watches.length()) db.episodeWatchEventDao().deleteById(watches.getJSONObject(i).getString("id"))
        val seasons = payload.getJSONArray("seasons")
        val local = db.seasonDao().observeSeasons(entry.entityId).first().associateBy { it.id }
        for (i in 0 until seasons.length()) {
            val row = seasons.getJSONObject(i)
            local[row.getString("id")]?.let { season ->
                val fresh = snapshot.seasons.find { it.getString("id") == season.id }
                db.seasonDao().upsertAll(listOf(season.copy(episodesWatched = fresh?.getInt("episodes_watched") ?: row.getInt("before"), updatedAt = fresh?.getString("updated_at") ?: season.updatedAt)))
            }
        }
        if (payload.has("titleEffect")) db.titleDao().getById(entry.entityId)?.let { title ->
            val later = pendingTitleIntents(db.outboxDao().getPending().dropWhile { it.id != entry.id }.drop(1), entry.entityId)
            val base = title.copy(status = snapshot.title?.getString("status")?.uppercase() ?: payload.getString("titleBefore"))
            db.titleDao().upsertAll(listOf(overlayTitleIntents(base, later, owner)))
        }
    }
}

/** Historical receipt validates the command only. Project fresh owned rows, never resurrect absent parents. */
class EpisodeBulkApplier(private val db: LibraryDatabase, private val owner: String) {
    suspend fun apply(entry: OutboxEntity, envelope: JSONObject) {
        check(db.inTransaction())
        val queue = db.outboxDao().getPending()
        require(queue.firstOrNull() == entry)
        checkedBulkReceipt(entry, envelope.getJSONObject("receipt"), owner)
        require(envelope.has("title"))
        if (!envelope.isNull("title")) applyCurrentViewingTitle(db, entry.entityId, envelope.getJSONObject("title"), queue.drop(1), owner)
        val protected = bulkProtectionKeys(queue.drop(1))
        val current = envelope.getJSONArray("seasons")
        val local = db.seasonDao().observeSeasons(entry.entityId).first().associateBy { it.id }
        for (i in 0 until current.length()) {
            val row = current.getJSONObject(i)
            require(row.getString("user_id") == owner && row.getString("title_id") == entry.entityId)
            java.time.Instant.parse(row.getString("updated_at"))
            local[row.getString("id")]?.takeIf { "season:${it.id}" !in protected }?.let {
                db.seasonDao().upsertAll(listOf(it.copy(episodesWatched = row.getInt("episodes_watched"), updatedAt = row.getString("updated_at"))))
            }
        }
    }
}
