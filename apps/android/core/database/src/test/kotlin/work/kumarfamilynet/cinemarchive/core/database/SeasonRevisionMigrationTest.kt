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
class SeasonRevisionMigrationTest {
    @Test fun upgradePreservesHistoryQueueAndAddsObservedRevisionOnly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "s.db"
        fun builder() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        val seed = builder().build()
        try {
            val sql = seed.openHelper.writableDatabase
            val schema = JSONObject(File("schemas/work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase/20.json").readText()).getJSONObject("database")
            restoreEmptyFixtureSchema(sql, schema)
            sql.execSQL("INSERT INTO titles (id,tmdbId,type,title,genres,status,addedAt,updatedAt,tags,studios) VALUES ('title',42,'TV','Series','','WATCHING','2026-01-01','2026-01-01','','')")
            sql.execSQL("INSERT INTO seasons VALUES ('season','title',1,10,4,2020)")
            sql.execSQL("INSERT INTO episodes (id,titleId,seasonId,episodeNumber) VALUES ('episode','title','season',1)")
            sql.execSQL("INSERT INTO episode_watch_events (id,episodeId,watchedAt,notes,colorMode) VALUES ('watch','episode',NULL,'Memory','bw')")
            sql.execSQL("INSERT INTO mutation_outbox (id,entityType,entityId,operation,payloadJson,createdAt,attemptCount) VALUES ('pending','episode_watch_event','watch','upsert','{}',1,3)")
            sql.execSQL("INSERT INTO legacy_restore_receipt VALUES ('receipt','archive','episode_watch_event','2026-01-01')")
            sql.version = 20
        } finally { seed.close() }
        val upgraded = builder().addMigrations(LibraryDatabase.MIGRATION_20_21).build()
        try {
            val season = upgraded.seasonDao().observeSeasons("title").first().single()
            assertNull(season.updatedAt); assertEquals(4, season.episodesWatched)
            assertEquals("bw", upgraded.episodeWatchEventDao().observeAllWatchEvents().first().single().colorMode)
            assertEquals(3, upgraded.outboxDao().getPending().single().attemptCount)
            upgraded.episodeBulkAdmissionDao().insert(EpisodeBulkAdmissionEntity("operation", "immutable"))
            upgraded.seasonDao().upsertAll(listOf(season.copy(updatedAt = "2026-10-08T12:00:00Z")))
        } finally { upgraded.close() }
        val reopened = builder().build()
        try {
            assertEquals("immutable", reopened.episodeBulkAdmissionDao().payload("operation"))
            assertEquals("2026-10-08T12:00:00Z", reopened.seasonDao().observeSeasons("title").first().single().updatedAt)
            assertTrue(reopened.legacyRestoreReceiptDao().keysFor("archive").contains("receipt"))
        } finally { reopened.close(); context.deleteDatabase(name) }
    }
}
