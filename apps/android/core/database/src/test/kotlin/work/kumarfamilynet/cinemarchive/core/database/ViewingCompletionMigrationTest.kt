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
class ViewingCompletionMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun builder(name: String) = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)

    @Test fun version15PreservesHistoryAndPendingIntents() = runBlocking {
        val name = "viewing.db"
        val fixture = builder(name).build()
        try {
            val old = fixture.openHelper.writableDatabase
            old.execSQL("DROP TABLE viewing_completion_aliases")
            old.execSQL("DROP TABLE viewings")
            val schema = JSONObject(File("schemas/work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase/15.json").readText()).getJSONObject("database")
            restoreEmptyFixtureTable(old, schema, "titles")
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                if (entity.getString("tableName") != "viewings") continue
                fun String.tableSql() = replace("\${TABLE_NAME}", "viewings")
                old.execSQL(entity.getString("createSql").tableSql())
                val indices = entity.getJSONArray("indices")
                for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").tableSql())
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) old.execSQL(setup.getString(index))
            old.execSQL("INSERT INTO titles (id,tmdbId,type,title,genres,status,addedAt,updatedAt,tags,studios) VALUES ('title',42,'MOVIE','A movie','','WATCHED','2026-01-01','2026-01-01','','')")
            old.execSQL("INSERT INTO viewings (id,titleId,date,rating,notes,venue,companions,outingId) VALUES ('viewing','title',NULL,4.5,'Keep this history','Cinema','','outing')")
            old.execSQL("INSERT INTO mutation_outbox (id,entityType,entityId,operation,payloadJson,createdAt,attemptCount) VALUES ('pending','viewing','viewing','update','{\"notes\":\"Pending note\"}',1,2)")
            old.execSQL("INSERT INTO legacy_restore_receipt (`key`,archiveId,kind,restoredAt) VALUES ('entry:old','archive','entry','2026-01-01')")
            old.version = 15
        } finally { fixture.close() }
        val upgraded = builder(name).addMigrations(LibraryDatabase.MIGRATION_15_16, LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18).build()
        try {
            val viewing = upgraded.viewingDao().getById("viewing")!!
            assertEquals("Keep this history", viewing.notes)
            assertEquals(4.5, viewing.rating)
            assertNull(viewing.date)
            assertNull(viewing.updatedAt)
            val intent = upgraded.outboxDao().getPending().single()
            assertEquals("pending", intent.id)
            assertEquals(2, intent.attemptCount)
            assertEquals("Pending note", JSONObject(intent.payloadJson).getString("notes"))
            assertEquals(listOf("entry:old"), upgraded.legacyRestoreReceiptDao().keysFor("archive"))
            assertNull(upgraded.viewingCompletionAliasDao().byProvisionalId("viewing"))
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }

    @Test fun aliasSurvivesDeletionAndReopenPerAccount() = runBlocking {
        val firstName = "owner-a.db"
        val secondName = "owner-b.db"
        val first = builder(firstName).build()
        val alias = ViewingCompletionAliasEntity("provisional", "canonical", "title", "outing", "completion", "2026-10-08T12:00:00Z")
        try {
            first.openHelper.writableDatabase.execSQL("INSERT INTO titles (id,tmdbId,type,title,genres,status,addedAt,updatedAt,tags,studios) VALUES ('title',42,'MOVIE','A movie','','WATCHED','2026-01-01','2026-01-01','','')")
            first.viewingDao().upsert(ViewingEntity("canonical", "title", null, null, "Original event", null))
            val dao = first.viewingCompletionAliasDao()
            dao.insert(alias)
            try {
                dao.insert(alias.copy(canonicalViewingId = "different"))
                fail("An existing provisional identity must not be rebound")
            } catch (_: android.database.sqlite.SQLiteConstraintException) { }
            try {
                dao.insert(alias.copy(provisionalViewingId = "different"))
                fail("An accepted operation cannot be rebound to another provisional event")
            } catch (_: android.database.sqlite.SQLiteConstraintException) { }
            first.viewingDao().deleteById("canonical")
            assertNull(first.viewingDao().getById("canonical"))
            first.openHelper.writableDatabase.execSQL("DELETE FROM cinema_outings")
            first.openHelper.writableDatabase.execSQL("DELETE FROM titles")
            assertEquals(alias, dao.byProvisionalId("provisional"))
        } finally { first.close() }
        val reopened = builder(firstName).build()
        val other = builder(secondName).build()
        try {
            assertEquals(alias, reopened.viewingCompletionAliasDao().byCompletionOperationId("completion"))
            assertNull(other.viewingCompletionAliasDao().byProvisionalId("provisional"))
            assertNull(other.viewingCompletionAliasDao().byCompletionOperationId("completion"))
            val historical = alias.copy(provisionalViewingId = "old", completionOperationId = "old-operation", canonicalViewingVersion = null)
            other.viewingCompletionAliasDao().insert(historical)
            assertNull(other.viewingCompletionAliasDao().byProvisionalId("old")!!.canonicalViewingVersion)
            assertEquals(alias, reopened.viewingCompletionAliasDao().byProvisionalId("provisional"))
        } finally {
            reopened.close(); other.close()
            context.deleteDatabase(firstName); context.deleteDatabase(secondName)
        }
    }
}
