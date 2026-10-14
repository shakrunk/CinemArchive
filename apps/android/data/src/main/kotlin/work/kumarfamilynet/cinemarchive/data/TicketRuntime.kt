package work.kumarfamilynet.cinemarchive.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.database.RoomTransactor
import work.kumarfamilynet.cinemarchive.core.model.CinemaOuting
import work.kumarfamilynet.cinemarchive.core.model.TicketAssociation
import work.kumarfamilynet.cinemarchive.core.model.TicketBarcode
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

data class TicketSavedChange(val id: String, val pending: Boolean, val message: String?, val canReview: Boolean, val hasOriginal: Boolean)
data class TicketViewState(val outing: CinemaOuting?, val association: TicketAssociation?, val changes: List<TicketSavedChange>)
data class TicketSavedOuting(val id: String, val title: String, val pendingCount: Int)
@ConsistentCopyVisibility
data class TicketConflictReview internal constructor(
    val operationId: String, val currentExists: Boolean, val currentManaged: Boolean,
    val currentAttachmentId: String?, val currentRevision: String?, val venue: String?, val showtime: String?,
    val canReapply: Boolean,
)

/** One account generation owns all ticket IO and queue operations. Immutable intent and originals
 * remain available after rejection or resolution; neither is deleted by recovery. */
class TicketRuntime internal constructor(
    private val database: LibraryDatabase,
    private val scope: TicketOwnerScope,
    private val files: TicketAttachmentFiles,
    private val transport: TicketAttachmentTransport,
    private val outbox: () -> MutationOutbox,
    private val isCurrentOwner: () -> Boolean,
    private val replayBoundary: suspend (suspend () -> Unit) -> Unit,
) {
    private val dao = database.ticketAttachmentDao()
    private val transactor = RoomTransactor(database)
    private val repository = TicketAttachmentsRepository(scope, dao, transactor, files, isCurrentOwner)
    private val projection = TicketProjectionRepository(scope, dao, transactor, isCurrentOwner)
    private val applier = TicketCommandApplier(database, scope, isCurrentOwner)
    val accountKey: String = "${scope.projectId}|${scope.ownerId}"
    /** Activity results from an ended sign-in must not be adopted by a replacement runtime. */
    val captureSessionKey: String = "$accountKey|${UUID.randomUUID()}"

    fun observeSavedOutings(): Flow<List<TicketSavedOuting>> = combine(database.cinemaOutingDao().observeAllOutings(),
        dao.observeAllIntents(scope.projectId, scope.ownerId), database.outboxDao().observePending()) { outings, intents, pending ->
        if (!isCurrentOwner()) emptyList() else intents.groupBy { it.outingId }.map { (id, _) ->
            val outing = outings.firstOrNull { it.id == id }
            TicketSavedOuting(id, outing?.let { database.titleDao().getById(it.titleId)?.title } ?: "Removed cinema outing",
                pending.count { it.entityId == id && it.entityType == TICKET_COMMAND_ENTITY })
        }.sortedByDescending { it.pendingCount }
    }

    suspend fun legacyPhoto(outingId: String, legacyRoot: File, confirmed: Boolean): File = withContext(Dispatchers.IO) {
        current(); require(confirmed) { "Confirm this original belongs to this account." }
        val outing = checkNotNull(dao.outing(outingId))
        val file = File(checkNotNull(outing.ticketImagePath)).canonicalFile
        require(file.parentFile == legacyRoot.canonicalFile && file.name.substringBeforeLast('.') == outingId && file.isFile) {
            "The device-local original is not available on this device."
        }
        current(); file
    }

    fun observe(outingId: String): Flow<TicketViewState> = combine(
        database.cinemaOutingDao().observeAllOutings(), repository.observe(outingId),
        database.outboxDao().observePending(), dao.observeIntents(scope.projectId, scope.ownerId, outingId),
    ) { outings, association, queue, intents ->
        if (!isCurrentOwner()) TicketViewState(null, null, emptyList()) else TicketViewState(
            outings.firstOrNull { it.id == outingId }?.toDomain(), association,
            intents.map { intent ->
                val entry = queue.firstOrNull { it.id == intent.operationId }
                val command = runCatching { ticketCommandFromJson(JSONObject(intent.payloadJson)) }.getOrNull()
                TicketSavedChange(intent.operationId, entry != null, entry?.let(::ticketStatusMessage),
                    entry?.let(::checkedTicketRejection) != null, command?.attachment != null)
            }.sortedByDescending { it.pending },
        )
    }

    suspend fun capture(outingId: String, mimeType: String, input: InputStream,
        decode: (File) -> TicketBarcode?): Unit = withContext(Dispatchers.IO) {
        current()
        val original = files.capture(UUID.randomUUID().toString(), mimeType, input)
        val descriptor = original.attachment.copy(barcode = decode(original.file))
        current(); repository.attach(outingId, descriptor, UUID.randomUUID().toString()); current()
    }

    suspend fun migrateLegacy(outingId: String, legacyRoot: File, confirmed: Boolean,
        decode: (File) -> TicketBarcode?): Unit = withContext(Dispatchers.IO) {
        current()
        val outing = checkNotNull(dao.outing(outingId)) { "This outing is no longer available." }
        val path = checkNotNull(outing.ticketImagePath) { "No device-local original is recorded." }
        require(confirmed) { "Confirm this original belongs to this account." }
        val legacy = File(path).canonicalFile
        require(legacy.parentFile == legacyRoot.canonicalFile && legacy.name.substringBeforeLast('.') == outingId && legacy.isFile)
        val mime = legacy.inputStream().use { input ->
            val header = ByteArray(12)
            var size = 0
            while (size < header.size) {
                val count = input.read(header, size, header.size - size)
                if (count < 0) break
                size += count
            }
            ticketMime(header.copyOf(size))
        } ?: error("Unsupported original image format.")
        val original = files.captureLegacy(outingId, path, legacyRoot, UUID.randomUUID().toString(), mime, true)
        val descriptor = original.attachment.copy(barcode = decode(original.file))
        current(); repository.attach(outingId, descriptor, UUID.randomUUID().toString()); current()
    }

    suspend fun remove(outingId: String) { current(); repository.detach(outingId, UUID.randomUUID().toString()); current() }
    suspend fun photo(outingId: String): StoredTicketOriginal? { current(); return repository.readOrDownload(outingId, transport::download).also { current() } }

    /** Export is explicit and may select a retained historical original, never an unscoped path. */
    suspend fun original(operationId: String): StoredTicketOriginal = withContext(Dispatchers.IO) {
        val command = savedCommand(operationId)
        val attachment = checkNotNull(command.attachment) { "This saved change removes a photo." }
        StoredTicketOriginal(attachment, files.read(attachment)).also { current() }
    }

    suspend fun exportOriginal(operationId: String, output: OutputStream) = withContext(Dispatchers.IO) {
        val original = original(operationId)
        original.file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                current()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
        output.flush(); current()
    }

    suspend fun currentPhoto(review: TicketConflictReview): StoredTicketOriginal? = outbox().withFlushPaused {
        val entry = rejected(review.operationId)
        val state = transport.readRejectedCurrent(savedCommand(entry.id))
        check(comparison(entry, state) == review) { "The ticket changed again. Refresh the comparison." }
        state.association?.attachment?.let { attachment ->
            val file = withContext(Dispatchers.IO) { runCatching { files.read(attachment) }.getOrNull() }
            if (file != null) StoredTicketOriginal(attachment, file) else transport.download(attachment)
        }.also { current() }
    }

    suspend fun push(entry: OutboxEntity): PushResult {
        current()
        if (entry.operation == "review") return PushResult.Review(entry.lastError ?: "Open this ticket to review its saved change.")
        return transport.push(entry)
    }
    suspend fun apply(entry: OutboxEntity, envelope: JSONObject) = applier.apply(entry, envelope)
    fun protectionKeys(entries: List<OutboxEntity>) = applier.protectionKeys(entries)

    suspend fun refresh() = outbox().withFlushPaused {
        current()
        val token = projection.captureToken()
        val read = transport.readDescriptors()
        current(); projection.apply(read, token)
    }

    /** Clear rejection proof in a committed transaction BEFORE an exact retry can reach HTTP. */
    suspend fun retry(operationId: String) = replayBoundary {
        outbox().withFlushPaused {
            transactor.run {
                val command = savedCommand(operationId)
                val entry = checkNotNull(dao.queued(operationId)) { "This change is no longer pending." }
                require(entry.payloadJson == command.toTicketJson().toString())
                check(dao.retry(operationId, entry.payloadJson) == 1)
                current()
            }
        }
        current(); outbox().flush(); current()
    }

    suspend fun review(operationId: String): TicketConflictReview = outbox().withFlushPaused {
        val entry = rejected(operationId)
        val command = savedCommand(operationId)
        val state = transport.readRejectedCurrent(command)
        current()
        comparison(entry, state)
    }

    /** A comparison is user-visible, generation scoped, and checked again against the server.
     * Reapply uses new immutable IDs; the old original and intent survive either choice. */
    suspend fun resolve(review: TicketConflictReview, reapply: Boolean) = replayBoundary { withContext(Dispatchers.IO) {
        outbox().withFlushPaused {
            val entry = rejected(review.operationId)
            val command = savedCommand(entry.id)
            val state = transport.readRejectedCurrent(command)
            val fresh = comparison(entry, state)
            check(fresh == review) { "The ticket changed again. Review its current state before resolving." }
            check(!reapply || review.canReapply) { "Resolve later saved changes before saving this original again." }
            val replacement = if (reapply) command.attachment?.let { descriptor ->
                files.read(descriptor).inputStream().use { input ->
                    files.capture(UUID.randomUUID().toString(), descriptor.mimeType, input, descriptor.barcode).attachment
                }
            } else null
            transactor.run {
                current()
                check(rejected(entry.id) == entry) { "This saved change changed during review." }
                val later = relatedAfter(entry)
                check(!reapply || later.isEmpty()) { "Resolve later saved changes first." }
                applyCurrentTicketProjection(database, scope, command.outingId, state, later)
                database.outboxDao().remove(entry.id)
                if (reapply) {
                    if (replacement != null) repository.attach(command.outingId, replacement, UUID.randomUUID().toString())
                    else repository.detach(command.outingId, UUID.randomUUID().toString())
                }
                current()
            }
        }
        current()
    } }

    private suspend fun comparison(entry: OutboxEntity, state: TicketAcknowledgment): TicketConflictReview {
        val row = state.currentOuting
        return TicketConflictReview(entry.id, row != null, row?.optBoolean("ticket_attachment_managed") == true,
            state.association?.attachment?.id, row?.ticketString("updated_at"), row?.ticketNullableString("venue"),
            row?.ticketString("showtime"), row != null && relatedAfter(entry).isEmpty())
    }
    private suspend fun relatedAfter(entry: OutboxEntity): List<OutboxEntity> {
        val pending = dao.pending()
        val index = pending.indexOfFirst { it.id == entry.id }
        check(index >= 0)
        return pending.drop(index + 1).filter { it.entityId == entry.entityId && it.entityType in setOf(TICKET_COMMAND_ENTITY, "cinema_outing", "outing_completion") }
    }
    private suspend fun rejected(id: String): OutboxEntity {
        val command = savedCommand(id)
        return checkNotNull(dao.queued(id)).also {
            require(it.payloadJson == command.toTicketJson().toString() && checkedTicketRejection(it) != null) {
                "Delivery is not known to be rejected. Retry the same saved operation to confirm it."
            }
        }
    }
    private suspend fun savedCommand(id: String): TicketAttachmentCommand {
        current(); checkedTicketUuid(id)
        val intent = checkNotNull(dao.intent(id)) { "This saved ticket change is unavailable." }
        require(intent.projectId == scope.projectId && intent.ownerId == scope.ownerId)
        return ticketCommandFromJson(JSONObject(intent.payloadJson)).also { require(it.operationId == id && it.scope == scope); current() }
    }
    private fun current() { check(isCurrentOwner()) { "This sign-in has ended. Reopen the ticket in its original account." } }

    companion object {
        fun create(database: LibraryDatabase, scope: TicketOwnerScope, root: File, anonKey: String,
            sessionProvider: () -> SupabaseSession?, outbox: () -> MutationOutbox, isCurrentOwner: () -> Boolean,
            replayBoundary: suspend (suspend () -> Unit) -> Unit): TicketRuntime {
            val files = TicketAttachmentFiles(root, scope, isCurrentOwner)
            return TicketRuntime(database, scope, files, TicketAttachmentTransport(scope,
                SupabaseTicketAttachmentRemote(scope.projectId, anonKey), files, sessionProvider, isCurrentOwner), outbox, isCurrentOwner, replayBoundary)
        }
    }
}
