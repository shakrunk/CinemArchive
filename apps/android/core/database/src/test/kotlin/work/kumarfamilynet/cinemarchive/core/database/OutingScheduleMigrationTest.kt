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
class OutingScheduleMigrationTest {
    @Test fun additiveUpgradePreservesQueueHistoryAndOtherAdmissionReceiptsAcrossReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "os.db"
        fun builder() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        val seed = builder().build()
        try {
            val sql = seed.openHelper.writableDatabase
            val schema = JSONObject(File("schemas/work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase/21.json").readText()).getJSONObject("database")
            restoreEmptyFixtureSchema(sql, schema)
            sql.execSQL("INSERT INTO titles (id,tmdbId,type,title,genres,status,addedAt,updatedAt,tags,studios) VALUES ('title',42,'MOVIE','Film','','WATCHED','2026-01-01','2026-01-01','','')")
            sql.execSQL("INSERT INTO viewings (id,titleId,date,companions) VALUES ('history','title','2026-01-01','Sam')")
            sql.execSQL("INSERT INTO mutation_outbox (id,entityType,entityId,operation,payloadJson,createdAt,attemptCount) VALUES ('pending','viewing','history','review','original',1,3)")
            sql.execSQL("INSERT INTO episode_bulk_admissions VALUES ('bulk','immutable-bulk')")
            sql.version = 21
        } finally { seed.close() }
        val upgraded = builder().addMigrations(LibraryDatabase.MIGRATION_21_22).build()
        try {
            assertEquals("2026-01-01", upgraded.viewingDao().getById("history")!!.date)
            assertEquals(3, upgraded.outboxDao().getPending().single().attemptCount)
            assertEquals("immutable-bulk", upgraded.episodeBulkAdmissionDao().payload("bulk"))
            upgraded.outingScheduleAdmissionDao().insert(OutingScheduleAdmissionEntity("schedule", "immutable-schedule"))
        } finally { upgraded.close() }
        val reopened = builder().build()
        try {
            assertEquals("immutable-schedule", reopened.outingScheduleAdmissionDao().payload("schedule"))
            assertEquals("original", reopened.outboxDao().getPending().single().payloadJson)
        } finally { reopened.close(); context.deleteDatabase(name) }
    }
}
