package work.kumarfamilynet.cinemarchive.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TicketMigrationTest {
    @Test fun preserves16() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "ticket-migrate.db"
        fun builder() = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        val fixture = builder().build()
        try {
            val old = fixture.openHelper.writableDatabase
            val schema = JSONObject(File("schemas/work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase/16.json").readText()).getJSONObject("database")
            restoreEmptyFixtureSchema(old, schema)
            old.execSQL("INSERT INTO titles (id,tmdbId,type,title,genres,status,addedAt,updatedAt,tags,studios) VALUES ('title',42,'MOVIE','Film','','WATCHED','2026-01-01','2026-01-01','','')")
            old.execSQL("INSERT INTO cinema_outings (id,titleId,showtime,previewsMinutes,runtimeMinutes,endsAt,companions,seats,status,createdAt,updatedAt,ticketImagePath,ticketBarcodePayload,ticketBarcodeFormat) VALUES ('outing','title','2026-10-08T12:00:00Z',0,90,'2026-10-08T13:30:00Z','','','COMPLETED','2026-01-01','2026-01-01','/legacy/tickets/original.png','Real payload','QR_CODE')")
            old.execSQL("INSERT INTO viewings (id,titleId,date,rating,notes,venue,companions,outingId,updatedAt) VALUES ('canonical','title',NULL,4.5,'Keep history','Cinema','','outing','2026-10-08T13:30:00Z')")
            old.execSQL("INSERT INTO viewing_completion_aliases VALUES ('provisional','canonical','title','outing','completion-op','2026-10-08T13:30:00Z')")
            old.execSQL("INSERT INTO mutation_outbox (id,entityType,entityId,operation,payloadJson,createdAt,attemptCount) VALUES ('pending','cinema_outing','outing','review','{\"original\":true}',1,3)")
            old.execSQL("INSERT INTO legacy_restore_receipt (`key`,archiveId,kind,restoredAt) VALUES ('entry:old','archive','entry','2026-01-01')")
            old.version = 16
        } finally { fixture.close() }
        val upgraded = builder().addMigrations(LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19, LibraryDatabase.MIGRATION_19_20, LibraryDatabase.MIGRATION_20_21).build()
        try {
            assertEquals("/legacy/tickets/original.png", upgraded.cinemaOutingDao().getById("outing")!!.ticketImagePath)
            assertEquals("Real payload", upgraded.cinemaOutingDao().getById("outing")!!.ticketBarcodePayload)
            assertEquals("Keep history", upgraded.viewingDao().getById("canonical")!!.notes)
            assertEquals("2026-10-08T13:30:00Z", upgraded.viewingDao().getById("canonical")!!.updatedAt)
            assertEquals("canonical", upgraded.viewingCompletionAliasDao().byProvisionalId("provisional")!!.canonicalViewingId)
            assertEquals("completion-op", upgraded.viewingCompletionAliasDao().byProvisionalId("provisional")!!.completionOperationId)
            assertEquals("{\"original\":true}", upgraded.outboxDao().getPending().single().payloadJson)
            assertEquals(3, upgraded.outboxDao().getPending().single().attemptCount)
            assertEquals(listOf("entry:old"), upgraded.legacyRestoreReceiptDao().keysFor("archive"))
            assertNull(upgraded.ticketAttachmentDao().association("project", "owner", "outing"))
            assertNull(upgraded.ticketAttachmentDao().intent("pending"))
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
