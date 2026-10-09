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
class PersonCreditsMigrationTest {
    @Test fun version14UpgradeRetainsHistoryQueueMetadataAndRecoveryReceiptsAndAddsCascadingCredits() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "credits.db"
        fun builder() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        val fixture = builder().build()
        try {
            val old = fixture.openHelper.writableDatabase
            val schema = JSONObject(File("schemas/work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase/14.json").readText()).getJSONObject("database")
            restoreEmptyFixtureSchema(old, schema)
            old.execSQL("INSERT INTO titles (id,tmdbId,type,title,genres,status,addedAt,updatedAt,tags,studios,collectionId,collectionName) VALUES ('title',42,'TV','A show','','WATCHING','2026-01-01','2026-01-01','favorite','Studio',7,'Saga')")
            old.execSQL("INSERT INTO seasons (id,titleId,seasonNumber,episodeCount,episodesWatched) VALUES ('season','title',0,1,1)")
            old.execSQL("INSERT INTO episodes (id,titleId,seasonId,episodeNumber) VALUES ('ep','title','season',1)")
            old.execSQL("INSERT INTO episode_watch_events (id,episodeId,watchedAt,notes) VALUES ('watch','ep',NULL,'A memory')")
            old.execSQL("INSERT INTO mutation_outbox (id,entityType,entityId,operation,payloadJson,createdAt,attemptCount) VALUES ('pending','title','title','update','{}',1,0)")
            old.execSQL("INSERT INTO legacy_restore_receipt (`key`,archiveId,kind,restoredAt) VALUES ('entry:old','archive','entry','2026-01-01')")
            old.version = 14
        } finally { fixture.close() }
        val upgraded = builder().addMigrations(LibraryDatabase.MIGRATION_14_15, LibraryDatabase.MIGRATION_15_16, LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19, LibraryDatabase.MIGRATION_19_20, LibraryDatabase.MIGRATION_20_21).build()
        try {
            assertEquals(listOf("favorite"), upgraded.titleDao().getById("title")!!.tags)
            assertEquals("A memory", upgraded.episodeWatchEventDao().observeAllWatchEvents().first().single().notes)
            assertEquals("pending", upgraded.outboxDao().getPending().single().id)
            assertEquals(listOf("entry:old"), upgraded.legacyRestoreReceiptDao().keysFor("archive"))
            val dao = upgraded.personCreditsDao()
            dao.upsertSeasonCast(listOf(SeasonCastEntity("cast", "title", "season", 42, "Same name", null, 0)))
            dao.upsertEpisodeCrew(listOf(EpisodeCrewEntity("crew", "title", "ep", 84, "Same name", "Writer")))
            assertEquals(setOf(42, 84), dao.observeLibraryPeople().first().map { it.tmdbPersonId }.toSet())
            upgraded.seasonDao().deleteById("season")
            assertTrue(dao.observeSeasonCast().first().isEmpty())
            assertTrue(dao.observeEpisodeCrew().first().isEmpty())
            assertEquals("pending", upgraded.outboxDao().getPending().single().id)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
