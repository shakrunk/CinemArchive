package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope
import work.kumarfamilynet.cinemarchive.core.model.TicketAssociation

@RunWith(RobolectricTestRunner::class)
class TicketRepoRoomTest {
    private val fixture = TicketAttachmentFixture
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LibraryDatabase
    private lateinit var root: File
    private lateinit var files: TicketAttachmentFiles
    private var current = true
    private fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, "tickets.db")
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
    private fun repo(transactor: LocalTransactor = RoomTransactor(db)) = TicketAttachmentsRepository(
        fixture.scope, db.ticketAttachmentDao(), transactor, files, { current }, { 1234L },
    )
    private fun original(id: String = fixture.attachment) = files.capture(id, "image/png", fixture.bytes.inputStream()).attachment
    private fun projection() = TicketProjectionRepository(fixture.scope, db.ticketAttachmentDao(), RoomTransactor(db), { current })

    @Before fun setup() = runBlocking {
        current = true
        db = open()
        root = File(context.filesDir, "ticket-tests")
        files = TicketAttachmentFiles(root, fixture.scope, { current }, {})
        db.titleDao().upsertAll(listOf(TitleEntity("title", 42, "MOVIE", "Film", 2026, null, emptyList(),
            null, null, null, 90, null, "WATCHLIST", null, null, "2026-01-01", "2026-01-01")))
        db.cinemaOutingDao().upsert(OutingCommandFixture.entity().copy(id = fixture.outing, titleId = "title", ticketImagePath = "/legacy/photo.png"))
    }

    @After fun close() { db.close(); context.deleteDatabase("tickets.db") }

    @Test fun survivesReopen() = runBlocking {
        val capture = original()
        val command = repo().attach(fixture.outing, capture, fixture.operation)
        val pending = db.outboxDao().getPending().single()
        assertEquals(command.toTicketJson().toString(), pending.payloadJson)
        assertEquals(OutingCommandFixture.baseline, command.expectedUpdatedAt)
        assertEquals(fixture.operation, pending.id)
        assertEquals(TICKET_COMMAND_ENTITY, pending.entityType)
        db.close(); db = open()
        assertEquals(capture, repo().observe(fixture.outing).first()!!.attachment)
        assertArrayEquals(fixture.bytes, repo().readOriginal(fixture.outing)!!.file.readBytes())
        assertEquals(pending, db.outboxDao().getPending().single())
        assertEquals(pending.payloadJson, db.ticketAttachmentDao().intent(fixture.operation)!!.payloadJson)
    }

    @Test fun rollbackKeepsOriginal() = runBlocking {
        val capture = original()
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER reject_ticket BEFORE INSERT ON mutation_outbox BEGIN SELECT RAISE(ABORT, 'simulated quota failure'); END")
        try { repo().attach(fixture.outing, capture, fixture.operation); fail("Write should roll back") } catch (_: android.database.sqlite.SQLiteException) { }
        val dao = db.ticketAttachmentDao()
        assertNull(dao.intent(fixture.operation))
        assertNull(dao.original(fixture.scope.projectId, fixture.scope.ownerId, capture.id))
        assertNull(dao.association(fixture.scope.projectId, fixture.scope.ownerId, fixture.outing))
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertEquals("/legacy/photo.png", db.cinemaOutingDao().getById(fixture.outing)!!.ticketImagePath)
        assertArrayEquals(fixture.bytes, files.read(capture).readBytes())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_ticket")
        repo().attach(fixture.outing, capture, fixture.operation)
        assertEquals(fixture.operation, db.outboxDao().getPending().single().id)
    }

    @Test fun immutableOperation() = runBlocking {
        val capture = original()
        val saved = repo().attach(fixture.outing, capture, fixture.operation)
        assertEquals(saved, repo().attach(fixture.outing, capture, fixture.operation))
        assertEquals(1, db.outboxDao().getPending().size)
        val other = original("30000000-0000-4000-8000-000000000002")
        try { repo().attach(fixture.outing, other, fixture.operation); fail("ID cannot change intent") } catch (_: IllegalArgumentException) { }
        try { repo().detach(fixture.outing, fixture.operation); fail("ID cannot change kind") } catch (_: IllegalArgumentException) { }
        assertEquals(saved.toTicketJson().toString(), db.outboxDao().getPending().single().payloadJson)
        assertEquals(capture, repo().observe(fixture.outing).first()!!.attachment)
    }

    @Test fun preservesManagedClear() = runBlocking {
        val capture = original()
        repo().attach(fixture.outing, capture, fixture.operation)
        val detach = repo().detach(fixture.outing, "40000000-0000-4000-8000-000000000002")
        assertEquals(capture.id, detach.expectedAttachmentId)
        assertNotNull(repo().observe(fixture.outing).first())
        assertNull(repo().observe(fixture.outing).first()!!.attachment)
        val outing = db.cinemaOutingDao().getById(fixture.outing)!!
        db.cinemaOutingDao().upsert(outing.copy(venue = "Refreshed cinema", ticketImagePath = "/remote/old-ticket.jpg"))
        assertNull(repo().observe(fixture.outing).first()!!.attachment)
        assertNull(repo().readOriginal(fixture.outing))
        assertArrayEquals(fixture.bytes, files.read(capture).readBytes())
        assertEquals(2, db.outboxDao().getPending().size)
    }

    @Test fun retiredIdCannotReturn() = runBlocking {
        val capture = original()
        val saved = repo().attach(fixture.outing, capture, fixture.operation)
        repo().detach(fixture.outing, "40000000-0000-4000-8000-000000000002")
        val newOperation = "40000000-0000-4000-8000-000000000003"
        try { repo().attach(fixture.outing, capture, newOperation); fail("New intent cannot reuse a potentially retired attachment") }
        catch (_: IllegalArgumentException) { }
        assertNull(db.ticketAttachmentDao().intent(newOperation))
        assertEquals(saved, repo().attach(fixture.outing, capture, fixture.operation))
        assertNull(repo().observe(fixture.outing).first()!!.attachment)
        assertEquals(2, db.outboxDao().getPending().size)
        assertArrayEquals(fixture.bytes, files.read(capture).readBytes())
        val replacement = original("30000000-0000-4000-8000-000000000002")
        repo().attach(fixture.outing, replacement, newOperation)
        assertEquals(replacement, repo().observe(fixture.outing).first()!!.attachment)
    }

    @Test fun missingOuting() = runBlocking {
        val capture = original()
        db.cinemaOutingDao().deleteById(fixture.outing)
        try { repo().attach(fixture.outing, capture, fixture.operation); fail("Missing outing must not accept capture") } catch (_: IllegalStateException) { }
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertNull(db.ticketAttachmentDao().intent(fixture.operation))
        assertArrayEquals(fixture.bytes, files.read(capture).readBytes())
    }

    @Test fun existingQueueId() = runBlocking {
        val capture = original()
        val unrelated = OutboxEntity(fixture.operation, "title", "title", "update", "{\"notes\":\"Keep\"}", 1)
        db.outboxDao().enqueue(unrelated)
        try { repo().attach(fixture.outing, capture, fixture.operation); fail("Cannot replace another operation") } catch (_: IllegalArgumentException) { }
        assertEquals(unrelated, db.outboxDao().getPending().single())
        assertNull(repo().observe(fixture.outing).first())
    }

    @Test fun immutableOutingBinding() = runBlocking {
        val capture = original()
        repo().attach(fixture.outing, capture, fixture.operation)
        val otherOuting = "20000000-0000-4000-8000-000000000002"
        db.cinemaOutingDao().upsert(db.cinemaOutingDao().getById(fixture.outing)!!.copy(id = otherOuting))
        try { repo().attach(otherOuting, capture, "40000000-0000-4000-8000-000000000002"); fail("Original is bound to its first outing") }
        catch (_: IllegalArgumentException) { }
        assertNull(repo().observe(otherOuting).first())
        assertEquals(1, db.outboxDao().getPending().size)
    }

    @Test fun switchAfterCommit() = runBlocking {
        val capture = original()
        val switchAfterCommit = object : LocalTransactor {
            override suspend fun <T> run(block: suspend () -> T): T {
                val result = RoomTransactor(db).run(block)
                current = false
                return result
            }
        }
        try { repo(switchAfterCommit).attach(fixture.outing, capture, fixture.operation); fail("Do not publish stale success") } catch (_: IllegalStateException) { }
        assertEquals(fixture.operation, db.outboxDao().getPending().single().id)
        assertNull(repo().observe(fixture.outing).first())
        val otherScope = TicketOwnerScope(fixture.scope.projectId, "10000000-0000-4000-8000-000000000002")
        val otherFiles = TicketAttachmentFiles(root, otherScope, { true }, {})
        val other = TicketAttachmentsRepository(otherScope, db.ticketAttachmentDao(), RoomTransactor(db), otherFiles, { true })
        assertNull(other.observe(fixture.outing).first())
        try { other.attach(fixture.outing, capture, fixture.operation); fail("Foreign descriptor cannot be adopted") } catch (_: IllegalArgumentException) { }
        current = true
        assertEquals(capture, repo().observe(fixture.outing).first()!!.attachment)
        assertArrayEquals(fixture.bytes, files.read(capture).readBytes())
    }

    @Test fun transactionFailure() = runBlocking {
        val capture = original()
        val failBeforeCommit = object : LocalTransactor {
            override suspend fun <T> run(block: suspend () -> T): T = RoomTransactor(db).run {
                block()
                throw IllegalStateException("Interrupted before commit")
            }
        }
        try { repo(failBeforeCommit).attach(fixture.outing, capture, fixture.operation); fail("Should roll back") } catch (_: IllegalStateException) { }
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertNull(repo().observe(fixture.outing).first())
        assertNull(db.ticketAttachmentDao().intent(fixture.operation))
        assertArrayEquals(fixture.bytes, files.read(capture).readBytes())
    }

    @Test fun guardedChainSurvivesRetry() = runBlocking {
        val capture = original()
        val first = repo().attach(fixture.outing, capture, fixture.operation)
        val clear = repo().detach(fixture.outing, "40000000-0000-4000-8000-000000000002")
        assertEquals(first.operationId, clear.expectedOperationId)
        assertNull(clear.expectedUpdatedAt)
        val updated = db.cinemaOutingDao().getById(fixture.outing)!!.copy(updatedAt = "2026-10-10T12:00:00Z")
        db.cinemaOutingDao().upsert(updated)
        db.close(); db = open()
        assertEquals(first, repo().attach(fixture.outing, capture, first.operationId))
        assertEquals(clear, repo().detach(fixture.outing, clear.operationId))
        assertNull(repo().observe(fixture.outing).first()!!.attachment)
    }

    @Test fun legacyPendingNeedsReview() = runBlocking {
        val capture = original(); val legacy = fixture.command()
        val payload = legacy.toTicketJson().toString(); val dao = db.ticketAttachmentDao()
        dao.insertOriginal(TicketOriginalEntity(fixture.scope.projectId, fixture.scope.ownerId, capture.id, fixture.outing, capture.toTicketJson().toString(), 1))
        dao.insertIntent(TicketIntentEntity(legacy.operationId, fixture.scope.projectId, fixture.scope.ownerId, fixture.outing, payload, 1))
        dao.insertCommand(OutboxEntity(legacy.operationId, TICKET_COMMAND_ENTITY, fixture.outing, TICKET_COMMAND_OPERATION, payload, 1))
        dao.putAssociation(TicketAssociationEntity(fixture.scope.projectId, fixture.scope.ownerId, fixture.outing, capture.id))
        assertEquals(legacy, repo().attach(fixture.outing, capture, legacy.operationId))
        try { repo().detach(fixture.outing, "40000000-0000-4000-8000-000000000002"); fail("Unproven predecessor cannot authorize new intent") }
        catch (_: IllegalStateException) { }
        assertEquals(payload, dao.queued(legacy.operationId)!!.payloadJson)
        assertEquals(capture, repo().observe(fixture.outing).first()!!.attachment)
    }

    @Test fun descriptorClearIsAuthoritative() = runBlocking {
        val metadata = fixture.descriptor(); val projection = projection()
        assertTrue(projection.apply(TicketDescriptorRead(true, listOf(TicketAssociation(fixture.outing, metadata))), projection.captureToken()))
        assertEquals(metadata, repo().observe(fixture.outing).first()!!.attachment)
        assertFalse(projection.apply(TicketDescriptorRead(false, emptyList()), projection.captureToken()))
        assertEquals(metadata, repo().observe(fixture.outing).first()!!.attachment)
        assertTrue(projection.apply(TicketDescriptorRead(true, listOf(TicketAssociation(fixture.outing, null))), projection.captureToken()))
        assertNotNull(repo().observe(fixture.outing).first())
        assertNull(repo().observe(fixture.outing).first()!!.attachment)
        assertEquals("/legacy/photo.png", db.cinemaOutingDao().getById(fixture.outing)!!.ticketImagePath)
    }

    @Test fun staleRefreshPreservesCapture() = runBlocking {
        val projection = projection(); val token = projection.captureToken(); val capture = original()
        repo().attach(fixture.outing, capture, fixture.operation)
        val cleared = TicketDescriptorRead(true, listOf(TicketAssociation(fixture.outing, null)))
        assertFalse(projection.apply(cleared, token))
        assertEquals(capture, repo().observe(fixture.outing).first()!!.attachment)
        // A fresh read still cannot replace pending optimism before its own receipt is confirmed.
        assertTrue(projection.apply(cleared, projection.captureToken()))
        assertEquals(capture, repo().observe(fixture.outing).first()!!.attachment)
    }

    @Test fun projectionOwnerAndQuota() = runBlocking {
        val projection = projection(); val token = projection.captureToken(); val metadata = fixture.descriptor()
        current = false
        try { projection.apply(TicketDescriptorRead(true, listOf(TicketAssociation(fixture.outing, metadata))), token); fail("Account ended") }
        catch (_: IllegalStateException) { }
        current = true
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER reject_projection BEFORE INSERT ON ticket_associations BEGIN SELECT RAISE(ABORT, 'quota'); END")
        try { projection.apply(TicketDescriptorRead(true, listOf(TicketAssociation(fixture.outing, metadata))), token); fail("All projection changes must roll back") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertNull(db.ticketAttachmentDao().original(fixture.scope.projectId, fixture.scope.ownerId, metadata.id))
        assertNull(repo().observe(fixture.outing).first())
    }

    @Test fun downloadCannotReviveDetach() = runBlocking {
        val metadata = fixture.descriptor(); val projection = projection()
        projection.apply(TicketDescriptorRead(true, listOf(TicketAssociation(fixture.outing, metadata))), projection.captureToken())
        try {
            repo().readOrDownload(fixture.outing) { descriptor ->
                val cached = files.cache(descriptor, fixture.bytes.inputStream())
                repo().detach(fixture.outing, fixture.operation)
                cached
            }
            fail("Do not display a ticket detached during download")
        } catch (error: IllegalStateException) { assertTrue(error.message!!.contains("changed while downloading")) }
        assertNull(repo().observe(fixture.outing).first()!!.attachment)
        assertArrayEquals(fixture.bytes, files.read(metadata).readBytes())
    }

    @Test fun downloadFencesAccountAndCaches() = runBlocking {
        val metadata = fixture.descriptor(); val projection = projection()
        projection.apply(TicketDescriptorRead(true, listOf(TicketAssociation(fixture.outing, metadata))), projection.captureToken())
        try {
            repo().readOrDownload(fixture.outing) { descriptor ->
                val cached = files.cache(descriptor, fixture.bytes.inputStream())
                current = false
                cached
            }
            fail("Late private download must stay hidden")
        } catch (_: IllegalStateException) { }
        current = true
        assertArrayEquals(fixture.bytes, repo().readOrDownload(fixture.outing) { error("Already cached for this owner") }!!.file.readBytes())
    }
}
