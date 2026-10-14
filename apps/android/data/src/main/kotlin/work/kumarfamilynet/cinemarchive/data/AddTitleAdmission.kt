package work.kumarfamilynet.cinemarchive.data

import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.model.AddTitleRequest
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

/** Ordinary additions use the same atomic graph command and recovery as restored titles. */
class AddTitleAdmission(
    private val database: LibraryDatabase,
    private val outbox: MutationOutbox,
    private val owner: TicketOwnerScope,
    private val current: () -> Boolean,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun add(request: AddTitleRequest): String = withContext(Dispatchers.IO) {
        fun fence() = check(current()) { "The account changed during addition." }
        fence()
        val at = Instant.now(clock).toString()
        val title = catalogImportTitle(request.details, request.status, request.rating, at)
            .put("notes", request.notes ?: org.json.JSONObject.NULL)
            .put("tags", JSONArray(request.tags.map(String::trim).filter(String::isNotEmpty).distinct()))
        val titleId = title.getString("id")
        if (request.status == LibraryStatus.WATCHED) {
            title.put("viewings", JSONArray().put(importValues("id" to UUID.randomUUID().toString(),
                "titleId" to titleId, "date" to request.watchedOn, "rating" to request.rating,
                "notes" to request.notes, "companions" to JSONArray())))
        }
        title.getJSONArray("seasons").importObjects().forEach { season ->
            val number = season.getInt("seasonNumber")
            val count = request.seasonProgress[number] ?: 0
            require(count in 0..season.getInt("episodeCount")) { "Invalid season progress." }
            season.put("episodesWatched", count)
            season.getJSONArray("episodes").importObjects().sortedBy { it.getInt("episodeNumber") }
                .take(count).forEach { episode ->
                    episode.put("watchEvents", JSONArray().put(importValues("id" to UUID.randomUUID().toString(),
                        "watchedAt" to null)))
                }
        }
        require(request.seasonProgress.keys.all { number ->
            request.details.seasons.any { it.seasonNumber == number }
        }) { "Unknown season progress." }
        val entry = OutboxEntity(UUID.randomUUID().toString(), "title", titleId, BACKUP_IMPORT_COMMAND,
            importPayload(owner, title, emptyList(), at), clock.millis())
        val command = checkedImportCommand(entry, owner)
        outbox.atomically {
            fence()
            val existing = database.titleDao().findIdByTmdbKey(request.details.tmdbId, request.details.type.name)
            if (existing != null) existing else {
                require(database.outboxDao().getPending().filter(::isBackupImport).none { pending ->
                    val original = checkedImportCommand(pending, owner).title
                    original.getInt("tmdbId") == request.details.tmdbId &&
                        original.getString("type") == request.details.type.name.lowercase()
                }) { "This title has a pending addition. Review its original request before adding it again." }
                writeImportGraph(database, command.mapping.graph)
                outbox.enqueueCaptured(entry.id, entry.entityType, entry.entityId, entry.operation, entry.payloadJson)
                fence()
                titleId
            }
        }
    }
}
