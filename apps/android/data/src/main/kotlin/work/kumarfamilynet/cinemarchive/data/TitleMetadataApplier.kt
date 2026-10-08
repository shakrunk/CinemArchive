package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.core.model.MediaType

/** ACK runs under the outbox Room transaction, never performs network IO and never uses historical values. */
class TitleMetadataApplier(private val database: LibraryDatabase, private val ownerId: String) {
    suspend fun apply(entry: OutboxEntity, envelope: JSONObject) {
        check(database.inTransaction()) { "Title metadata ACK requires a transaction." }
        val queue = database.outboxDao().getPending()
        require(queue.firstOrNull() == entry) { "Title metadata ACK no longer matches the queue head." }
        checkedTitleMetadataReceipt(entry, envelope.getJSONObject("receipt"), ownerId)
        require(envelope.has("current"))
        val local = database.titleDao().getById(entry.entityId) ?: return
        if (envelope.isNull("current")) return // The durable epoch rewind recovers the authoritative tombstone.
        val current = checkedCurrentTitle(envelope.getJSONObject("current"), entry.entityId, ownerId)
        val later = queue.drop(1).filter { it.entityType == "title" && it.entityId == entry.entityId }
        val projected = overlayTitleIntents(current.toMetadataTitle(local), later, ownerId)
        database.titleDao().upsertAll(listOf(projected))
    }
}

internal fun overlayTitleIntents(base: TitleEntity, pending: List<OutboxEntity>, ownerId: String): TitleEntity =
    pending.fold(base) { row, entry ->
        val payload = JSONObject(entry.payloadJson)
        if (payload.has(TITLE_METADATA_DATA)) row.withTitleMetadata(titleMetadataPatch(entry, ownerId))
        else if (entry.operation == "update") {
            val patch = JSONObject()
            if (payload.has("status")) patch.put("status", payload.getString("status").lowercase())
            if (payload.has("rating")) patch.put("rating", payload.get("rating"))
            if (patch.length() == 0) row else row.withTitleMetadata(patch)
        } else row
    }

/** Full current owned row prevents a skipped protected sync row losing unrelated catalog changes. */
internal fun JSONObject.toMetadataTitle(local: TitleEntity): TitleEntity {
    fun text(key: String): String? = if (isNull(key)) null else getString(key)
    fun int(key: String): Int? = if (isNull(key)) null else getInt(key)
    fun strings(key: String): List<String> = if (isNull(key)) emptyList() else getJSONArray(key).let { values -> (0 until values.length()).map(values::getString) }
    val mediaType = MediaType.valueOf(getString("type").uppercase())
    require(mediaType.name == local.type) { "Title media type changed." }
    return local.copy(
        tmdbId = getInt("tmdb_id"), title = getString("title"), year = int("year"),
        director = text("director"), genres = strings("genres"), posterUrl = text("poster_url"),
        backdropUrl = text("backdrop_url"), synopsis = text("synopsis"), runtime = int("runtime"),
        network = text("network"), notes = text("notes"), addedAt = getString("added_at"),
        updatedAt = getString("updated_at"), imdbRating = if (isNull("imdb_rating")) null else getDouble("imdb_rating"),
        originalLanguage = text("original_language"), releaseDate = text("release_date"), studios = strings("studios"),
        collectionId = int("collection_id"), collectionName = text("collection_name"),
    ).withRichMetadata(this, snakeCase = true)
        .withTitleMetadata(JSONObject().put("tags", getJSONArray("tags")).put("status", getString("status")).put("rating", get("rating")))
}
