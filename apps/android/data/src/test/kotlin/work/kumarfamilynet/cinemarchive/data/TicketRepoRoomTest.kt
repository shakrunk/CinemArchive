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
}
