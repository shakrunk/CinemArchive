package work.kumarfamilynet.cinemarchive

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.RoomTransactor
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope
import work.kumarfamilynet.cinemarchive.data.TicketAttachmentFiles
import work.kumarfamilynet.cinemarchive.data.TicketAttachmentsRepository

/** Uses the real Android Os.fsync path, not the injected JVM directory-sync test seam. */
@RunWith(AndroidJUnit4::class)
class TicketFilesDeviceTest {
    @Test fun originalAndIntentSurviveReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val databaseName = "ticket-proof-$suffix.db"
        val root = File(context.cacheDir, "ticket-proof-$suffix")
        val scope = TicketOwnerScope("https://ticket-proof.invalid", "10000000-0000-4000-8000-000000000001")
        val outing = "20000000-0000-4000-8000-000000000001"
        val attachment = "30000000-0000-4000-8000-000000000001"
        val operation = "40000000-0000-4000-8000-000000000001"
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, databaseName)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        var database = open()
        try {
            database.titleDao().upsertAll(listOf(TitleEntity("title", 42, "MOVIE", "Film", 2026, null,
                emptyList(), null, null, null, 90, null, "WATCHLIST", null, null, "2026-01-01", "2026-01-01")))
            database.cinemaOutingDao().upsert(CinemaOutingEntity(outing, "title", "2099-10-08T12:00:00Z", 0, 90,
                "2099-10-08T13:30:00Z", "Cinema", emptyList(), null, null, createdAt = "2026-01-01", updatedAt = "2026-01-01"))
            val bytes = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a/dkAAAAASUVORK5CYII=", Base64.DEFAULT)
            val files = TicketAttachmentFiles(root, scope, { true })
            val original = files.capture(attachment, "image/png", bytes.inputStream())
            val repository = TicketAttachmentsRepository(scope, database.ticketAttachmentDao(), RoomTransactor(database), files, { true })
            repository.attach(outing, original.attachment, operation)
            val queued = database.outboxDao().getPending().single()
            database.close(); database = open()
            val reopenedFiles = TicketAttachmentFiles(root, scope, { true })
            val reopened = TicketAttachmentsRepository(scope, database.ticketAttachmentDao(), RoomTransactor(database), reopenedFiles, { true })
            assertArrayEquals(bytes, reopened.readOriginal(outing)!!.file.readBytes())
            assertEquals(queued, database.outboxDao().getPending().single())
            reopened.detach(outing, "40000000-0000-4000-8000-000000000002")
            assertNull(reopened.readOriginal(outing))
            assertArrayEquals(bytes, reopenedFiles.read(original.attachment).readBytes())
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }
}
