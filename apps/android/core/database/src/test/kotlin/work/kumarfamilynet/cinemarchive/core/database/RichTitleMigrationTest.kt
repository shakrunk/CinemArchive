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
class RichTitleMigrationTest {
    @Test fun preserves17HistoryQueueReceiptsAliasesAndTicketOriginals() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "rich-title.db"
        fun builder() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        val fixture = builder().build()
        try {
            val old = fixture.openHelper.writableDatabase
            val schema = JSONObject(File("schemas/work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase/17.json").readText()).getJSONObject("database")
            restoreEmptyFixtureTable(old, schema, "titles")
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO titles (id,tmdbId,type,title,genres,status,addedAt,updatedAt,tags,studios) VALUES ('title',42,'MOVIE','Film','','WATCHED','2026-01-01','2026-01-01','Keep tag','Studio')")
            old.execSQL("INSERT INTO viewings (id,titleId,date,rating,notes,venue,companions,outingId,updatedAt) VALUES ('viewing','title',NULL,4.5,'Keep history','Cinema','','outing','2026-10-08T13:30:00Z')")
            old.execSQL("INSERT INTO viewing_completion_aliases VALUES ('provisional','viewing','title','outing','completion-op','2026-10-08T13:30:00Z')")
            old.execSQL("INSERT INTO mutation_outbox (id,entityType,entityId,operation,payloadJson,createdAt,attemptCount) VALUES ('pending','title','title','metadata_v2','{\"immutable\":true}',1,3)")
            old.execSQL("INSERT INTO legacy_restore_receipt (`key`,archiveId,kind,restoredAt) VALUES ('entry:old','archive','entry','2026-01-01')")
            old.execSQL("INSERT INTO ticket_originals VALUES ('project','owner','attachment','outing','{\"path\":\"original\"}',1)")
            old.execSQL("INSERT INTO ticket_associations VALUES ('project','owner','outing','attachment')")
            old.execSQL("INSERT INTO ticket_intents VALUES ('ticket-op','project','owner','outing','{\"unchanged\":true}',1,NULL)")
            old.version = 17
        } finally { fixture.close() }
        val upgraded = builder().addMigrations(LibraryDatabase.MIGRATION_17_18).build()
        try {
            val row = upgraded.titleDao().getById("title")!!
            assertEquals(listOf("Keep tag"), row.tags)
            assertNull(row.contentRating); assertNull(row.physicalMediaJson); assertNull(row.inHomeCollection)
            assertEquals("Keep history", upgraded.viewingDao().getById("viewing")!!.notes)
            assertEquals("viewing", upgraded.viewingCompletionAliasDao().byProvisionalId("provisional")!!.canonicalViewingId)
            assertEquals("{\"immutable\":true}", upgraded.outboxDao().getPending().single().payloadJson)
            assertEquals(3, upgraded.outboxDao().getPending().single().attemptCount)
            assertEquals(listOf("entry:old"), upgraded.legacyRestoreReceiptDao().keysFor("archive"))
            val sql = upgraded.openHelper.writableDatabase
            for (table in listOf("ticket_originals", "ticket_associations", "ticket_intents")) {
                sql.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            }
            val shelf = """[{"id":"copy","format":"Blu-ray","notes":"Region B","extension":{"keep":true}}]"""
            upgraded.titleDao().upsertAll(listOf(row.copy(contentRating = "PG-13", imdbId = "tt42", rtScore = 0,
                metacriticScore = 0, inHomeCollection = false, physicalMediaJson = shelf, awardsCount = 0, bechdelOutcome = "fail", bechdelScore = "1/3")))
            assertEquals(shelf, upgraded.titleDao().getById("title")!!.physicalMediaJson)
        } finally { upgraded.close() }
        val reopened = builder().build()
        try {
            val row = reopened.titleDao().getById("title")!!
            assertEquals(false, row.inHomeCollection); assertEquals(0, row.rtScore); assertEquals(0, row.awardsCount)
            assertEquals("Region B", org.json.JSONArray(row.physicalMediaJson).getJSONObject(0).getString("notes"))
            assertEquals(1, reopened.outboxDao().getPending().size)
        } finally { reopened.close(); context.deleteDatabase(name) }
    }
}
