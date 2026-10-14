package work.kumarfamilynet.cinemarchive.data

import java.io.OutputStream
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.RoomTransactor
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

/** Bytes belong to this exact prepared snapshot and runtime, not to a later selected account. */
class PreparedLibraryExport internal constructor(
    val fileName: String,
    val titleCount: Int,
    val outingCount: Int,
    internal val bytes: ByteArray,
    internal val runtimeToken: Any,
)

interface LibraryBackupSource {
    fun isCurrent(): Boolean
    suspend fun prepareExport(): PreparedLibraryExport
    suspend fun writeExport(prepared: PreparedLibraryExport, open: () -> OutputStream)
    suspend fun syncBeforeExport()
}

class LibraryBackupRepository(
    private val database: LibraryDatabase,
    private val owner: TicketOwnerScope,
    private val current: () -> Boolean,
    private val synchronize: suspend () -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) : LibraryBackupSource {
    private val runtimeToken = Any()
    override fun isCurrent() = current()
    private fun requireCurrent() = check(isCurrent()) { "The account changed. Open export again for the current account." }

    override suspend fun syncBeforeExport() {
        requireCurrent(); synchronize(); requireCurrent()
    }

    override suspend fun prepareExport(): PreparedLibraryExport = withContext(Dispatchers.IO) {
        requireCurrent()
        val graph = RoomTransactor(database).run {
            requireCurrent()
            val titles = database.titleDao().observeAllTitles().first()
            val outings = database.cinemaOutingDao().observeAllOutings().first()
            val outingIds = outings.mapTo(mutableSetOf()) { it.id }
            val tickets = database.ticketAttachmentDao()
            val codes = tickets.associations(owner.projectId, owner.ownerId).filter { it.outingId in outingIds }.associate { association ->
                association.outingId to association.attachmentId?.let { id ->
                    val original = checkNotNull(tickets.original(owner.projectId, owner.ownerId, id)) {
                        "A saved ticket is still loading. Sync, then export again."
                    }
                    check(original.outingId == association.outingId) { "Saved ticket does not match its outing." }
                    ticketAttachmentFromJson(owner, JSONObject(original.descriptorJson)).also {
                        check(it.id == id) { "Saved ticket identity changed." }
                    }.barcode?.let {
                        JSONObject().put("payload", it.payload).put("format", it.format.name)
                    }
                }
            }
            LibraryExportGraph(titles,
                database.seasonDao().observeAllSeasons().first(), database.episodeDao().observeAllEpisodes().first(),
                database.episodeWatchEventDao().observeAllWatchEvents().first(),
                titles.flatMap { database.episodeRatingDao().observeRatings(it.id).first() },
                titles.flatMap { database.episodeReviewDao().observeReviews(it.id).first() },
                database.viewingDao().observeAllViewings().first(), database.titleCastDao().observeAllCast().first(),
                database.titleCrewDao().observeAllCrew().first(), database.personCreditsDao().observeSeasonCast().first(),
                database.personCreditsDao().observeEpisodeCrew().first(), outings, codes,
            ).also { requireCurrent() }
        }
        val day = LocalDate.now(clock).toString()
        val bytes = (LibraryBackupCodec.encodeJsonValue(graph.exportDocument(day)) + "\n").toByteArray(Charsets.UTF_8)
        check(bytes.size <= LibraryBackupCodec.MAX_BYTES) { "This library exceeds the 64 MiB JSON export limit. No file was written." }
        // A file produced here must be readable by the bounded cross-client parser too.
        val parsed = LibraryBackupCodec.parseBytes(bytes)
        check(parsed is LibraryBackupCodec.ParseResult.Success) { "This library could not be encoded as a readable backup." }
        requireCurrent()
        PreparedLibraryExport("cinemarchive-$day.json", graph.titles.size, graph.outings.size, bytes, runtimeToken)
    }

    override suspend fun writeExport(prepared: PreparedLibraryExport, open: () -> OutputStream) = withContext(Dispatchers.IO) {
        requireCurrent()
        check(prepared.runtimeToken === runtimeToken) { "This export belongs to another account session." }
        open().use { output ->
            var offset = 0
            while (offset < prepared.bytes.size) {
                currentCoroutineContext().ensureActive(); requireCurrent()
                val count = minOf(64 * 1024, prepared.bytes.size - offset)
                output.write(prepared.bytes, offset, count)
                offset += count
            }
            output.flush()
        }
        requireCurrent()
    }
}
