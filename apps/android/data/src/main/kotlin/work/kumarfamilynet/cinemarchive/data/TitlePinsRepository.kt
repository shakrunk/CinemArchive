package work.kumarfamilynet.cinemarchive.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.*

data class TitlePinsState(
    val loaded: Boolean = false,
    val pins: Map<String, String> = emptyMap(),
    val pending: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
)

interface TitlePinsSource {
    val state: Flow<TitlePinsState>
    suspend fun set(titleId: String, mode: String?)
    fun retry()
}

/** Confirmed preferences live in the owner's DataStore; all optimistic values derive from Room's FIFO. */
class TitlePinsRepository(
    private val db: LibraryDatabase,
    private val store: DataStore<Preferences>,
    private val outbox: MutationOutbox,
    private val owner: String,
    private val isCurrent: () -> Boolean,
    private val remote: TitlePinRemote,
    private val requestSync: () -> Unit,
) : TitlePinsSource {
    private val key = stringPreferencesKey("confirmed_noir_pins")
    private suspend fun active() { currentCoroutineContext().ensureActive(); check(isCurrent()) { "This sign-in has ended." } }
    private fun decode(raw: String?): Map<String, String> {
        if (raw == null) return emptyMap()
        val json = JSONObject(raw)
        return json.keys().asSequence().associateWith { json.getString(it).also { mode -> require(mode in NOIR_MODES) } }
    }
    override val state: Flow<TitlePinsState> = combine(store.data, db.outboxDao().observePending()) { prefs, queue ->
        if (!isCurrent()) TitlePinsState() else {
            val pins = decode(prefs[key]).toMutableMap()
            val pending = queue.filter { it.entityType == TITLE_PIN }
            pending.forEach { entry ->
                val payload = titlePinPayload(entry, owner)
                if (payload.isNull("variant")) pins.remove(entry.entityId) else pins[entry.entityId] = payload.getString("variant")
            }
            TitlePinsState(true, pins, pending.map { it.entityId }.toSet(), pending.mapNotNull { it.lastError?.let { error -> it.entityId to error } }.toMap())
        }
    }

    override suspend fun set(titleId: String, mode: String?) {
        require(mode == null || mode in NOIR_MODES)
        outbox.atomically {
            active()
            val title = checkNotNull(db.titleDao().getById(titleId)) { "This title was removed." }
            require(title.tmdbId == SPIDER_NOIR_TMDB_ID && title.type == "TV")
            if (mode != null) {
                val seasons = db.seasonDao().observeSeasons(titleId).first().filter { it.seasonNumber != 0 }.map { it.id }.toSet()
                val episodes = db.episodeDao().observeEpisodes(titleId).first().filter { it.seasonId in seasons }
                val watches = db.episodeWatchEventDao().observeAllWatchEvents().first().filter { it.colorMode == mode }.map { it.episodeId }.toSet()
                check(episodes.isNotEmpty() && episodes.all { it.id in watches }) { "Watch every main-season episode in this mode to pin it." }
            }
            val payload = JSONObject().put("version", 1).put("ownerId", owner).put("titleId", titleId).put("variant", mode ?: JSONObject.NULL)
            outbox.enqueueCaptured(UUID.randomUUID().toString(), TITLE_PIN, titleId, TITLE_PIN_COMMAND, payload.toString())
            active()
        }
        requestSync()
    }
    override fun retry() { check(isCurrent()); requestSync() }
    suspend fun push(entry: OutboxEntity): PushResult { active(); return remote.push(entry).also { active() } }

    /** Full snapshots and ACKs share FIFO serialization: an earlier fetch can never land after a newer ACK. */
    suspend fun refresh() = outbox.withFlushPaused {
        active()
        val fresh = remote.fetch()
        active()
        store.edit { active(); it[key] = JSONObject(fresh).toString() }
        active()
    }

    /** DataStore commits before Room removes this head. Interrupted ACK retains the same queued overlay. */
    suspend fun apply(entry: OutboxEntity, envelope: JSONObject) {
        check(db.inTransaction())
        require(db.outboxDao().getPending().firstOrNull() == entry)
        active(); titlePinPayload(entry, owner)
        require(envelope.getString("ownerId") == owner && envelope.getString("titleId") == entry.entityId)
        val mode = if (envelope.optBoolean("missingParent")) {
            require(!envelope.has("receipt") && envelope.getString("rejectionCode") in setOf("P0002", "23503"))
            null
        } else {
            checkedPinReceipt(entry, owner, envelope.getJSONObject("receipt"))
            require(envelope.has("current"))
            if (envelope.isNull("current")) null else checkedPinRow(envelope.getJSONObject("current"), owner, entry.entityId)
        }
        store.edit { preferences ->
            active()
            val pins = decode(preferences[key]).toMutableMap()
            if (mode == null) pins.remove(entry.entityId) else pins[entry.entityId] = mode
            preferences[key] = JSONObject(pins).toString()
        }
        active()
    }
}
