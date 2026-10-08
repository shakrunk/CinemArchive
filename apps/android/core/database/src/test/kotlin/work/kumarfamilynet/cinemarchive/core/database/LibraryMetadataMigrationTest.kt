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
class LibraryMetadataMigrationTest {
    @Test fun version13UpgradePreservesTitleHistoryQueueAndRecoveryReceipts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric already isolates each test directory. Keep SQLite's path below Windows MAX_PATH.
        val name = "metadata.db"
        val schema = JSONObject(File("schemas/work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase/13.json").readText()).getJSONObject("database")
        fun builder() = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        val fixture = builder().build()
        try {
            val old = fixture.openHelper.writableDatabase
            old.execSQL("DROP TABLE viewing_completion_aliases")
            old.execSQL("DROP TABLE viewings")
            old.execSQL("DROP TABLE season_cast")
            old.execSQL("DROP TABLE episode_crew")
            old.execSQL("DROP TABLE titles")
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                fun String.tableSql() = replace("\${TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(entity.getString("createSql").tableSql())
                val indices = entity.optJSONArray("indices") ?: continue
                for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").tableSql())
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) old.execSQL(setup.getString(index))
            old.execSQL("INSERT INTO titles (id,tmdbId,type,title,genres,status,addedAt,updatedAt) VALUES ('title',42,'TV','A show','','WATCHING','2026-01-01','2026-01-01')")
            old.execSQL("INSERT INTO seasons (id,titleId,seasonNumber,episodeCount,episodesWatched) VALUES ('season','title',1,1,1)")
            old.execSQL("INSERT INTO episodes (id,titleId,seasonId,episodeNumber) VALUES ('ep','title','season',1)")
            old.execSQL("INSERT INTO episode_watch_events (id,episodeId,watchedAt,notes) VALUES ('watch','ep',NULL,'A memory')")
            old.execSQL("INSERT INTO mutation_outbox (id,entityType,entityId,operation,payloadJson,createdAt,attemptCount) VALUES ('pending','title','title','update','{}',1,0)")
            old.execSQL("INSERT INTO legacy_restore_receipt (`key`,archiveId,kind,restoredAt) VALUES ('entry:old','archive','entry','2026-01-01')")
            old.version = 13
        } finally { fixture.close() }
        val upgraded = builder().addMigrations(LibraryDatabase.MIGRATION_13_14, LibraryDatabase.MIGRATION_14_15, LibraryDatabase.MIGRATION_15_16).build()
        try {
            val row = upgraded.titleDao().getById("title")!!
            assertEquals("WATCHING", row.status)
            assertTrue(row.tags.isEmpty()); assertTrue(row.studios.isEmpty())
            assertNull(row.collectionId); assertNull(row.collectionName)
            assertEquals("A memory", upgraded.episodeWatchEventDao().observeAllWatchEvents().first().single().notes)
            assertEquals("pending", upgraded.outboxDao().getPending().single().id)
            assertEquals(listOf("entry:old"), upgraded.legacyRestoreReceiptDao().keysFor("archive"))
            upgraded.titleDao().upsertAll(listOf(row.copy(tags = listOf("Favorite"), studios = listOf("Studio"), collectionId = 42, collectionName = "Collection")))
            val projected = upgraded.titleDao().observeLibrary().first().single()
            assertEquals(listOf("Favorite"), projected.tags); assertEquals(listOf("Studio"), projected.studios)
            assertEquals(42, projected.collectionId); assertEquals("Collection", projected.collectionName)
            assertEquals("A memory", upgraded.episodeWatchEventDao().observeAllWatchEvents().first().single().notes)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
