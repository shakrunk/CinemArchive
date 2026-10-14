package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.edit
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.TicketAttachment

@RunWith(RobolectricTestRunner::class)
class TicketRuntimeRoomTest {
    private val f = TicketAttachmentFixture
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LibraryDatabase
    private lateinit var files: TicketAttachmentFiles
    private lateinit var outbox: MutationOutbox
    private lateinit var runtime: TicketRuntime
    private var active = true
    private var replayBoundary: suspend (suspend () -> Unit) -> Unit = { action -> action() }
    private var rpcCalls = 0
    private var beforeRpc: () -> Unit = {}
    private val replies = ArrayDeque<String>()
    private var currentRow: JSONObject? = null
    private val remote = object : TicketAttachmentRemote {
        override val projectId = f.scope.projectId
        override fun rpc(name: String, args: String, token: String, current: () -> Unit): String {
            current(); beforeRpc(); rpcCalls++; return replies.removeFirst().also { current() }
        }
        override fun outing(id: String, owner: String, token: String, current: () -> Unit): String {
            current(); return JSONArray().also { currentRow?.let(it::put) }.toString()
        }
        override fun upload(attachment: TicketAttachment, file: File, token: String, current: () -> Unit) = error("No upload expected")
        override fun <T> download(attachment: TicketAttachment, token: String, current: () -> Unit, consume: (InputStream) -> T): T =
            consume(f.bytes.inputStream())
    }
    private fun row(attachment: TicketAttachment? = f.descriptor(), managed: Boolean = true) = OutingCommandFixture.row()
        .put("id", f.outing).put("ticket_attachment_managed", managed).put("ticket_attachment_id", attachment?.id ?: JSONObject.NULL)
        .put("updated_at", "2026-10-08T15:00:00Z")
    private fun descriptors(attachment: TicketAttachment? = f.descriptor(), managed: Boolean = true) = JSONArray().also {
        if (managed) it.put(JSONObject().put("outingId", f.outing).put("managed", true).put("attachment", attachment?.toTicketJson() ?: JSONObject.NULL))
    }
    private fun receipt(command: TicketAttachmentCommand) = JSONObject().put("operationId", command.operationId)
        .put("outingId", f.outing).put("request", command.receiptRequest()).put("outingRevisionGuarded", true)
        .put("attachment", command.attachment?.toTicketJson() ?: JSONObject.NULL).put("outingUpdatedAt", "2026-10-08T15:00:00Z")
        .put("rows", JSONArray().put(JSONObject().put("table", "cinema_outings").put("key", JSONObject().put("id", f.outing)).put("row", row(command.attachment))))
    private suspend fun capture(): TicketAttachmentCommand {
        val attachment = files.capture(f.attachment, "image/png", f.bytes.inputStream()).attachment
        return TicketAttachmentsRepository(f.scope, db.ticketAttachmentDao(), RoomTransactor(db), files, { active })
            .attach(f.outing, attachment, f.operation)
    }
    private suspend fun reject() {
        val entry = db.outboxDao().getPending().first()
        db.outboxDao().markForReview(entry.id, ticketRejection(entry, "40001"))
    }
    private fun comparisonReply(attachment: TicketAttachment? = null, managed: Boolean = false) {
        replies += "null"; replies += descriptors(attachment, managed).toString()
        currentRow = row(attachment, managed)
    }
    @Before fun setup() = runBlocking {
        db = Room.databaseBuilder(context, LibraryDatabase::class.java, "ticket-runtime.db")
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        files = TicketAttachmentFiles(File(context.filesDir, "runtime-originals"), f.scope, { active }, {})
        runtime = TicketRuntime(db, f.scope, files, TicketAttachmentTransport(f.scope, remote, files,
            { SupabaseSession("token", f.scope.ownerId) }, { active }), { outbox }, { active }, { action -> replayBoundary(action) })
        outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity) = runtime.push(entry)
        }, ConflictHandler { _, _, _ -> error("Unexpected conflict") },
            RoomTransactor(db), AppliedMutationHandler(runtime::apply), runtime::protectionKeys, f.scope)
        db.titleDao().upsertAll(listOf(TitleEntity(OutingCommandFixture.title, 42, "MOVIE", "Film", 2026, null, emptyList(),
            null, null, null, 90, null, "WATCHLIST", null, null, OutingCommandFixture.baseline, OutingCommandFixture.baseline)))
        db.cinemaOutingDao().upsert(OutingCommandFixture.entity().copy(id = f.outing))
    }
    @After fun close() { db.close(); context.deleteDatabase("ticket-runtime.db") }

    @Test fun ackUsesCurrentAndKeepsOriginal() = runBlocking {
        val command = capture()
        replies += receipt(command).toString(); replies += descriptors(null).toString(); currentRow = row(null).put("venue", "New cinema")
        outbox.flush()
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertNotNull(db.ticketAttachmentDao().intent(f.operation)!!.acknowledgedAt)
        assertNull(db.ticketAttachmentDao().association(f.scope.projectId, f.scope.ownerId, f.outing)!!.attachmentId)
        assertEquals("New cinema", db.cinemaOutingDao().getById(f.outing)!!.venue)
        assertArrayEquals(f.bytes, runtime.original(f.operation).file.readBytes())
    }

    @Test fun ackRollbackRetainsQueueAndProjection() = runBlocking {
        val command = capture()
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER reject_ack BEFORE DELETE ON mutation_outbox BEGIN SELECT RAISE(ABORT, 'quota'); END")
        replies += receipt(command).toString(); replies += descriptors(null).toString(); currentRow = row(null)
        outbox.flush()
        assertEquals(1, db.outboxDao().getPending().size)
        assertNull(db.ticketAttachmentDao().intent(f.operation)!!.acknowledgedAt)
        assertEquals(f.attachment, db.ticketAttachmentDao().association(f.scope.projectId, f.scope.ownerId, f.outing)!!.attachmentId)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_ack")
        replies += receipt(command).toString(); replies += descriptors(null).toString()
        outbox.flush()
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun laterDetachRemainsOptimistic() = runBlocking {
        val command = capture()
        runtime.remove(f.outing)
        val later = db.outboxDao().getPending().last()
        val envelope = JSONObject().put("receipt", receipt(command)).put("currentOuting", row()).put("associations", descriptors())
        RoomTransactor(db).run { runtime.apply(db.outboxDao().getPending().first(), envelope); db.outboxDao().remove(f.operation) }
        assertEquals(later, db.outboxDao().getPending().single())
        assertNull(db.ticketAttachmentDao().association(f.scope.projectId, f.scope.ownerId, f.outing)!!.attachmentId)
    }

    @Test fun rejectionMustClearBeforeRetry() = runBlocking {
        val command = capture(); reject()
        beforeRpc = { runBlocking { assertNull(db.outboxDao().getPending().first().lastError); assertEquals(TICKET_COMMAND_OPERATION, db.outboxDao().getPending().first().operation) } }
        replies += receipt(command).toString(); replies += descriptors().toString(); currentRow = row()
        runtime.retry(f.operation)
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun retryClearFailureNeverSends() = runBlocking {
        capture(); reject()
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER reject_retry BEFORE UPDATE ON mutation_outbox BEGIN SELECT RAISE(ABORT, 'quota'); END")
        try { runtime.retry(f.operation); fail("Must not send") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(0, rpcCalls)
        assertNotNull(checkedTicketRejection(db.outboxDao().getPending().single()))
    }

    @Test fun unknownCannotResolve() = runBlocking {
        capture()
        db.outboxDao().recordFailure(f.operation, "Response lost")
        try { runtime.review(f.operation); fail("Unknown outcome is not rejection proof") } catch (_: IllegalArgumentException) { }
        assertEquals(0, rpcCalls)
        assertEquals(1, db.outboxDao().getPending().size)
    }

    @Test fun useCurrentRetainsExport() = runBlocking {
        capture(); reject(); comparisonReply()
        val review = runtime.review(f.operation)
        comparisonReply(); runtime.resolve(review, false)
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertNull(db.ticketAttachmentDao().association(f.scope.projectId, f.scope.ownerId, f.outing))
        assertArrayEquals(f.bytes, runtime.original(f.operation).file.readBytes())
        assertNotNull(db.ticketAttachmentDao().intent(f.operation))
    }

    @Test fun reapplyUsesFreshIdsAndGuard() = runBlocking {
        capture(); reject(); comparisonReply()
        val review = runtime.review(f.operation)
        comparisonReply(); runtime.resolve(review, true)
        val replacement = ticketCommand(db.outboxDao().getPending().single())
        assertNotEquals(f.operation, replacement.operationId)
        assertNotEquals(f.attachment, replacement.attachment!!.id)
        assertEquals("2026-10-08T15:00:00Z", replacement.expectedUpdatedAt)
        assertNull(replacement.expectedOperationId)
        assertArrayEquals(f.bytes, runtime.original(f.operation).file.readBytes())
    }

    @Test fun changedComparisonAndAccountPreventResolution() = runBlocking {
        capture(); reject(); comparisonReply()
        val review = runtime.review(f.operation)
        comparisonReply(); currentRow!!.put("updated_at", "2026-10-08T16:00:00Z")
        try { runtime.resolve(review, false); fail("Review changed state") } catch (_: IllegalStateException) { }
        active = false
        try { runtime.original(f.operation); fail("No stale owner bytes") } catch (_: IllegalStateException) { }
        assertEquals(1, db.outboxDao().getPending().size)
    }

    @Test fun reapplyRollbackKeepsOldIntent() = runBlocking {
        capture(); reject(); comparisonReply()
        val review = runtime.review(f.operation)
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER reject_new BEFORE INSERT ON mutation_outbox BEGIN SELECT RAISE(ABORT, 'quota'); END")
        comparisonReply()
        try { runtime.resolve(review, true); fail("All changes must roll back") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(f.operation, db.outboxDao().getPending().single().id)
        assertEquals(f.attachment, db.ticketAttachmentDao().association(f.scope.projectId, f.scope.ownerId, f.outing)!!.attachmentId)
        assertArrayEquals(f.bytes, runtime.original(f.operation).file.readBytes())
    }
    @Test fun replayFailureRetainsProofAndNeverSends() = runBlocking {
        capture(); reject()
        replayBoundary = { error("Could not persist replay cursor") }
        try { runtime.retry(f.operation); fail("Must not clear proof or send") } catch (_: IllegalStateException) { }
        assertEquals(0, rpcCalls)
        assertNotNull(checkedTicketRejection(db.outboxDao().getPending().single()))
        comparisonReply()
        val review = runtime.review(f.operation)
        val reads = rpcCalls
        try { runtime.resolve(review, false); fail("Must not remove protection") } catch (_: IllegalStateException) { }
        assertEquals(reads, rpcCalls)
        assertNotNull(checkedTicketRejection(db.outboxDao().getPending().single()))
        assertArrayEquals(f.bytes, runtime.original(f.operation).file.readBytes())
    }

    @Test fun recoveryReplaysSkippedDeletion() = runBlocking {
        capture(); reject(); comparisonReply()
        val review = runtime.review(f.operation)
        val job = kotlinx.coroutines.SupervisorJob()
        val storageScope = kotlinx.coroutines.CoroutineScope(job + kotlinx.coroutines.Dispatchers.IO)
        try {
            val prefs = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = storageScope) {
                File(context.filesDir, "ticket-replay.preferences_pb")
            }
            val cursor = androidx.datastore.preferences.core.stringPreferencesKey("last_synced_at")
            prefs.edit { it[cursor] = "2026-10-09T00:00:00Z" }
            var requestedSince: String? = null
            val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                val body = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                requestedSince = JSONObject(body).getString("p_since")
                val tombstone = JSONObject().put("entity_type", "tombstone").put("entity_id", f.outing)
                    .put("updated_at", "2026-10-08T17:00:00Z").put("payload", JSONObject().put("entityType", "cinema_outing"))
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(JSONArray().put(tombstone).toString().toResponseBody()).build()
            }.build()
            val sync = LibrarySyncRepository(prefs, SupabaseRestClient(f.scope.projectId, "key", http),
                SessionSource { SupabaseSession("token", f.scope.ownerId) }, db.titleDao(), db.seasonDao(), db.episodeDao(),
                db.episodeWatchEventDao(), db.episodeRatingDao(), db.episodeReviewDao(), db.viewingDao(), db.cinemaOutingDao(),
                db.titleCastDao(), db.titleCrewDao(), db.listDao(), db.listItemDao(), db.personCreditsDao(),
                pendingKeys = outbox::pendingEntityKeys, transactor = RoomTransactor(db))
            replayBoundary = { action -> sync.withDurableReplay {
                assertEquals("1970-01-01T00:00:00Z", prefs.data.first()[cursor])
                assertEquals(f.operation, db.outboxDao().getPending().single().id)
                action()
            } }
            comparisonReply(); runtime.resolve(review, false)
            assertTrue(db.outboxDao().getPending().isEmpty())
            assertEquals("1970-01-01T00:00:00Z", prefs.data.first()[cursor])
            // A restart before the next pull retains this epoch, so the previously skipped tombstone is reachable.
            sync.syncNow()
            assertEquals("1970-01-01T00:00:00Z", requestedSince)
            assertNull(db.cinemaOutingDao().getById(f.outing))
            assertArrayEquals(f.bytes, runtime.original(f.operation).file.readBytes())
        } finally { job.cancel() }
    }

}
