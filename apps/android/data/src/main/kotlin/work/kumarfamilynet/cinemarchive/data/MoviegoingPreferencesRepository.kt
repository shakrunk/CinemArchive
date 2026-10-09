package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.*

data class SavedVenueNote(val venue: String, val notes: String, val deviceOnly: Boolean)
data class MoviegoingChange(val id: String, val label: String, val saved: String, val error: String?, val needsReview: Boolean)
data class MoviegoingComparison(val id: String, val label: String, val saved: String, val current: String,
    val canResolve: Boolean, internal val chain: String, internal val remote: String?)

/** Owner-bound preferences use the same durable command journal as the Library. */
class MoviegoingPreferencesRepository(
    private val database: LibraryDatabase,
    private val outbox: MutationOutbox,
    private val ownerId: String,
    private val isCurrent: () -> Boolean,
    private val client: SupabaseRestClient,
    private val session: () -> SupabaseSession?,
    private val archive: OutingRecoveryArchive,
    private val synchronize: suspend () -> Unit,
    private val replayBoundary: suspend (suspend () -> Unit) -> Unit,
    private val onQueued: () -> Unit = {},
) {
    private val transport = MoviegoingPreferenceTransport(client, session)
    val notes = database.venueNoteDao().observeAll().map { rows -> rows.map { SavedVenueNote(it.venue, it.notes, it.serverUpdatedAt == null) } }
    val changes = database.outboxDao().observePending().map { rows -> rows.filter { it.entityType in moviegoingTypes }.map {
        MoviegoingChange(it.id, it.entityId, describe(it), it.lastError, it.operation == "review")
    } }
    val preservedOriginals = archive.records

    private fun checkOwner() { check(isCurrent() && session()?.userId == ownerId) { "This sign-in has ended." } }

    /** The serialized opening is saveable UI state. Neither a retry nor a refreshed Room row rebases it. */
    suspend fun captureVenue(venue: String): String = outbox.atomically {
        checkOwner()
        val key = normalizedVenue(venue)
        val row = database.venueNoteDao().get(key)
        val prior = outbox.pendingEntries().lastOrNull { it.entityType == "venue_note" && it.entityId == key }
        if (prior != null) require(prior.operation == MOVIEGOING_COMMAND) { "Review the saved venue change in Profile first." }
        JSONObject().put("ownerId", ownerId).put("key", key).put("openingId", UUID.randomUUID().toString())
            .put("recordedAt", Instant.now().toString()).put("notes", row?.notes ?: "")
            .put("serverId", row?.serverId ?: JSONObject.NULL)
            .put("revision", row?.serverUpdatedAt ?: JSONObject.NULL)
            .put("prior", prior?.id ?: JSONObject.NULL)
            .put("priorDelete", prior?.let { JSONObject(it.payloadJson).isNull("notes") } ?: false).toString()
    }

    suspend fun saveVenue(opening: String, notes: String?) { outbox.atomically {
        checkOwner()
        val p = JSONObject(opening)
        require(p.getString("ownerId") == ownerId)
        val key = normalizedVenue(p.getString("key"))
        notes?.let { checkedPreferenceText(it, 20_000) }
        val prior = p.nullableString("prior")
        val revision = p.nullableString("revision")
        val insert = p.getBoolean("priorDelete") || (prior == null && revision == null)
        require(notes != null || !insert) { "Save this device note to your account before removing it, or compare it in Profile." }
        val operation = JSONObject().put("table", "venue_notes").put("action", if (notes == null) "delete" else if (insert) "insert" else "update")
            .put("key", JSONObject().put("venue", key))
        if (notes != null) operation.put("values", JSONObject().put("notes", notes))
        if (!insert) {
            if (prior != null) operation.put("expectedOperationId", prior) else operation.put("expectedUpdatedAt", revision)
        }
        val payload = JSONObject().put("version", 1).put("ownerId", ownerId).put("key", key)
            .put("notes", notes ?: JSONObject.NULL).put("recordedAt", p.getString("recordedAt"))
            .put("serverId", p.opt("serverId") ?: JSONObject.NULL).put("operation", operation)
        val bytes = payload.toString()
        val id = UUID.nameUUIDFromBytes((p.getString("openingId") + "\n" + bytes).toByteArray(Charsets.UTF_8)).toString()
        val entry = OutboxEntity(id, "venue_note", key, MOVIEGOING_COMMAND, bytes, 0)
        preferenceOperation(entry, ownerId)
        if (outbox.enqueueCaptured(id, entry.entityType, key, entry.operation, bytes)) {
            if (notes == null) database.venueNoteDao().delete(key)
            else database.venueNoteDao().upsert(database.venueNoteDao().get(key)?.copy(notes = notes)
                ?: VenueNoteEntity(key, notes, p.getString("recordedAt")))
        }
        checkOwner()
    }; onQueued() }

    suspend fun setInterest(titleId: String, present: Boolean) { outbox.atomically {
        checkOwner(); UUID.fromString(titleId)
        require(database.titleDao().getById(titleId) != null) { "This title is no longer in your Library." }
        val existing = database.theaterInterestDao().observeAll().first().firstOrNull { it.titleId == titleId }
        val createdAt = existing?.createdAt ?: Instant.now().toString()
        val id = UUID.randomUUID().toString()
        val operation = JSONObject().put("table", "theater_interest").put("action", if (present) "insert" else "delete")
            .put("key", JSONObject().put("id", titleId))
        if (present) operation.put("values", JSONObject().put("title_id", titleId).put("created_at", createdAt))
        val p = JSONObject().put("version", 1).put("ownerId", ownerId).put("key", titleId).put("present", present)
            .put("createdAt", createdAt).put("operation", operation)
        outbox.enqueueCaptured(id, "theater_interest", titleId, MOVIEGOING_COMMAND, p.toString())
        if (present) database.theaterInterestDao().upsert(existing ?: TheaterInterestEntity(titleId, createdAt))
        else database.theaterInterestDao().deleteByTitleId(titleId)
        checkOwner()
    }; onQueued() }

    /** Explicit admission, never an automatic upload of an unknown-owner legacy archive. */
    suspend fun syncDevicePreferences() {
        checkOwner()
        for (note in database.venueNoteDao().observeAll().first().filter { it.serverUpdatedAt == null }) {
            if (outbox.pendingEntries().none { it.entityType == "venue_note" && it.entityId == note.venue }) saveVenue(captureVenue(note.venue), note.notes)
        }
        for (interest in database.theaterInterestDao().observeAll().first().filter { it.serverUpdatedAt == null }) {
            if (outbox.pendingEntries().none { it.entityType == "theater_interest" && it.entityId == interest.titleId }) setInterest(interest.titleId, true)
        }
        synchronize()
    }

    suspend fun retry() { checkOwner(); synchronize(); checkOwner() }
    suspend fun exportOriginals(): String {
        checkOwner()
        val records = archive.records.first().toMutableMap()
        outbox.pendingEntries().filter { it.entityType in moviegoingTypes }.forEach { records[it.id] = it.originalRecord().toString() }
        checkOwner()
        return JSONObject().put("ownerId", ownerId).put("preservedChanges", JSONArray(records.values.toList())).toString(2)
    }
    suspend fun push(entry: OutboxEntity): PushResult {
        checkOwner()
        return if (entry.operation == "review") PushResult.Review(entry.lastError ?: "Compare this preference in Profile.") else transport.push(entry)
    }
    suspend fun apply(entry: OutboxEntity, envelope: JSONObject) {
        checkOwner(); check(database.inTransaction())
        val queue = database.outboxDao().getPending()
        require(queue.firstOrNull() == entry) { "The saved preference changed before confirmation." }
        checkedPreferenceReceipt(entry, envelope.getJSONObject("receipt"), ownerId)
        require(envelope.has("current"))
        require(envelope.isNull("current") || envelope.opt("current") is JSONObject)
        val later = queue.drop(1)
        applyPreferenceCurrent(database, entry.entityType, entry.entityId, envelope.optJSONObject("current"), later, ownerId)
        checkOwner()
    }
    suspend fun protectionKeys(entries: List<OutboxEntity>): Set<String> = buildSet {
        checkOwner()
        for (row in database.venueNoteDao().observeAll().first()) {
            if (row.serverUpdatedAt == null || entries.any { it.entityType == "venue_note" && it.entityId == row.venue }) {
                add("venue_note_name:${row.venue}"); row.serverId?.let { add("venue_note:$it") }
            }
        }
        database.theaterInterestDao().observeAll().first().filter { it.serverUpdatedAt == null }.forEach { add("theater_interest:${it.titleId}") }
        entries.filter { it.entityType in moviegoingTypes }.forEach {
            add("moviegoing:${it.id}")
            if (it.entityType == "venue_note") {
                add("venue_note_name:${it.entityId}")
                runCatching { JSONObject(it.payloadJson).nullableString("serverId") }.getOrNull()?.let { id -> add("venue_note:$id") }
            } else add("theater_interest:${it.entityId}")
        }
    }

    suspend fun compare(id: String): MoviegoingComparison {
        checkOwner()
        val all = outbox.pendingEntries()
        val entry = all.first { it.id == id && it.entityType in moviegoingTypes }
        val chain = all.filter { it.entityType == entry.entityType && it.entityId == entry.entityId }
        val current = transport.current(entry.entityType, entry.entityId)
        checkOwner()
        val canResolve = chain.first().id == id && entry.operation == "review" && chain.drop(1).all { it.attemptCount == 0 && it.operation == MOVIEGOING_COMMAND }
        return MoviegoingComparison(id, entry.entityId, describe(chain.last()), current?.let {
            if (entry.entityType == "venue_note") it.getString("notes") else "Interested in theaters"
        } ?: "Not saved", canResolve, chainSignature(chain), current?.toString())
    }

    /** Only a definitive rejection and its never-dispatched descendants can be replaced/discarded. */
    suspend fun resolve(comparison: MoviegoingComparison, keepSaved: Boolean) {
        checkOwner(); require(comparison.canResolve) { "Confirm the original attempt with Retry before replacing it." }
        replayBoundary {
            outbox.withFlushPaused {
                checkOwner()
                val all = outbox.pendingEntries()
                val first = all.first { it.id == comparison.id }
                val chain = all.filter { it.entityType == first.entityType && it.entityId == first.entityId }
                require(chainSignature(chain) == comparison.chain)
                require(chain.first().operation == "review" && chain.drop(1).all { it.attemptCount == 0 && it.operation == MOVIEGOING_COMMAND })
                val current = transport.current(first.entityType, first.entityId)
                require(sameCommandJson(current ?: JSONObject.NULL, comparison.remote?.let(::JSONObject) ?: JSONObject.NULL)) { "The current value changed. Compare again." }
                chain.forEach { archive.put(it.id, it.originalRecord().put("state", "prepared").toString()) }
                outbox.atomically {
                    checkOwner()
                    require(chainSignature(database.outboxDao().getPending().filter { it.entityType == first.entityType && it.entityId == first.entityId }) == comparison.chain)
                    chain.forEach { database.outboxDao().remove(it.id) }
                    applyPreferenceCurrent(database, first.entityType, first.entityId, current, emptyList(), ownerId)
                    if (keepSaved) {
                        val desired = JSONObject(chain.last().payloadJson)
                        if (first.entityType == "venue_note") {
                            val note = desired.nullableString("notes")
                            if (note != null || current != null) saveVenue(captureVenue(first.entityId), note)
                        } else if (database.titleDao().getById(first.entityId) != null) setInterest(first.entityId, desired.getBoolean("present"))
                        else error("The title was removed. Discard this saved preference or restore the title first.")
                    }
                    checkOwner()
                }
                chain.forEach { archive.put(it.id, it.originalRecord().put("state", if (keepSaved) "reapplied" else "discarded").toString()) }
            }
        }
    }
    private fun chainSignature(entries: List<OutboxEntity>) = JSONArray(entries.map { it.originalRecord() }).toString()
    private fun describe(entry: OutboxEntity): String = runCatching {
        val p = JSONObject(entry.payloadJson)
        if (entry.entityType == "venue_note") p.nullableString("notes") ?: "Remove saved venue note"
        else if (p.getBoolean("present")) "Interested in theaters" else "Not interested in theaters"
    }.getOrDefault("Preserved preference needs review")
}

private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
