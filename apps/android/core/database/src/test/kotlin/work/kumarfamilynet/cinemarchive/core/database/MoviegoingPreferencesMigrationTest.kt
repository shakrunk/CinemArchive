package work.kumarfamilynet.cinemarchive.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MoviegoingPreferencesMigrationTest {
    @Test fun upgradeKeepsPreferencesAndHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "p.db"
        fun builder() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        val seed = builder().build()
        try {
            val sql = seed.openHelper.writableDatabase
            val schema = JSONObject(File("schemas/work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase/19.json").readText()).getJSONObject("database")
            restoreEmptyFixtureSchema(sql, schema)
            sql.execSQL("INSERT INTO titles (id,tmdbId,type,title,genres,status,addedAt,updatedAt,tags,studios,physicalMediaJson) VALUES ('title',42,'MOVIE','Film','','WATCHLIST','2026-01-01','2026-01-01','','','[]')")
            sql.execSQL("INSERT INTO venue_notes VALUES ('Cinema','Park here','2099-01-01')")
            sql.execSQL("INSERT INTO theater_interest VALUES ('title','2026-01-01')")
            sql.execSQL("INSERT INTO viewings (id,titleId,date,rating,notes,venue,companions,outingId,updatedAt) VALUES ('viewing','title',NULL,NULL,'Memory','Cinema','Alex',NULL,NULL)")
            sql.execSQL("INSERT INTO mutation_outbox (id,entityType,entityId,operation,payloadJson,createdAt,attemptCount) VALUES ('pending','viewing','viewing','review','{\"companions\":[\"Alex\"]}',1,3)")
            sql.version = 19
        } finally { seed.close() }
        val upgraded = builder().addMigrations(LibraryDatabase.MIGRATION_19_20).build()
        try {
            val note = upgraded.venueNoteDao().observeAll().first().single()
            assertEquals("Park here", note.notes); assertEquals("2099-01-01", note.updatedAt)
            assertNull(note.serverId); assertNull(note.serverUpdatedAt); assertNull(note.serverCreatedAt)
            assertNull(upgraded.theaterInterestDao().observeAll().first().single().serverUpdatedAt)
            val viewing = upgraded.viewingDao().getById("viewing")!!
            assertEquals(listOf("Alex"), viewing.companions); assertNull(viewing.companionsJson)
            upgraded.venueNoteDao().upsert(note.copy(serverId = "remote", serverUpdatedAt = "2026-10-08", serverCreatedAt = "2026-01-01"))
            upgraded.viewingDao().upsert(viewing.copy(companionsJson = "[{\"name\":\"Alex\",\"friendUserId\":\"friend\"}]"))
        } finally { upgraded.close() }
        val reopened = builder().build()
        try {
            assertEquals("remote", reopened.venueNoteDao().observeAll().first().single().serverId)
            assertTrue(reopened.viewingDao().getById("viewing")!!.companionsJson!!.contains("friendUserId"))
            val queued = reopened.outboxDao().getPending().single()
            assertEquals("{\"companions\":[\"Alex\"]}", queued.payloadJson); assertEquals(3, queued.attemptCount)
        } finally { reopened.close(); context.deleteDatabase(name) }
    }
}
