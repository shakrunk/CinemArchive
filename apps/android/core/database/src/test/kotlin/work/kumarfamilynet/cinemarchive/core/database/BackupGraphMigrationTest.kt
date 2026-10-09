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
class BackupGraphMigrationTest {
    @Test fun version18PreservesGraphAndPendingBytesThenRetainsNewFieldsAfterReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "backup-graph.db"
        fun builder() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        val fixture = builder().build()
        try {
            val old = fixture.openHelper.writableDatabase
            val schema = JSONObject(File("schemas/work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase/18.json").readText()).getJSONObject("database")
            restoreEmptyFixtureSchema(old, schema)
            old.execSQL("INSERT INTO titles (id,tmdbId,type,title,genres,status,addedAt,updatedAt,tags,studios,physicalMediaJson) VALUES ('title',42,'TV','Show','','WATCHING','2026-01-01','2026-01-01','','','[]')")
            old.execSQL("INSERT INTO seasons (id,titleId,seasonNumber,episodeCount,episodesWatched) VALUES ('season','title',0,1,1)")
            old.execSQL("INSERT INTO episodes (id,titleId,seasonId,episodeNumber) VALUES ('episode','title','season',1)")
            old.execSQL("INSERT INTO title_cast VALUES ('cast','title',42,'Actor','Role',0)")
            old.execSQL("INSERT INTO title_crew VALUES ('crew','title',43,'Director','Director','Directing')")
            old.execSQL("INSERT INTO season_cast VALUES ('season-cast','title','season',44,'Guest','Role',1)")
            old.execSQL("INSERT INTO episode_watch_events VALUES ('watch','episode',NULL,'Memory')")
            old.execSQL("INSERT INTO episode_reviews VALUES ('review','episode','Review','2026-01-01')")
            old.execSQL("INSERT INTO mutation_outbox (id,entityType,entityId,operation,payloadJson,createdAt,attemptCount) VALUES ('pending','episode_review','review','review','{\"immutable\":true}',1,4)")
            old.version = 18
        } finally { fixture.close() }
        val upgraded = builder().addMigrations(LibraryDatabase.MIGRATION_18_19, LibraryDatabase.MIGRATION_19_20, LibraryDatabase.MIGRATION_20_21).build()
        try {
            val cast = upgraded.titleCastDao().observeAllCast().first().single()
            val crew = upgraded.titleCrewDao().observeAllCrew().first().single()
            val season = upgraded.personCreditsDao().observeSeasonCast().first().single()
            val watch = upgraded.episodeWatchEventDao().observeAllWatchEvents().first().single()
            val review = upgraded.episodeReviewDao().observeReviews("title").first().single()
            assertNull(cast.profileUrl); assertNull(cast.episodeCount); assertNull(crew.profileUrl)
            assertNull(season.profileUrl); assertNull(season.episodeCount)
            assertNull(watch.colorMode); assertNull(review.colorMode)
            assertNull(watch.watchedAt); assertEquals("Memory", watch.notes); assertEquals("Review", review.reviewText)
            assertEquals("[]", upgraded.titleDao().getById("title")!!.physicalMediaJson)
            upgraded.titleCastDao().upsertAll(listOf(cast.copy(profileUrl = "https://image/cast", episodeCount = 0)))
            upgraded.titleCrewDao().upsertAll(listOf(crew.copy(profileUrl = "https://image/crew")))
            upgraded.personCreditsDao().upsertSeasonCast(listOf(season.copy(profileUrl = "https://image/guest", episodeCount = 1)))
            upgraded.episodeWatchEventDao().upsertAll(listOf(watch.copy(colorMode = "bw")))
            upgraded.episodeReviewDao().upsertAll(listOf(review.copy(colorMode = "color")))
        } finally { upgraded.close() }
        val reopened = builder().build()
        try {
            assertEquals(0, reopened.titleCastDao().observeAllCast().first().single().episodeCount)
            assertEquals("https://image/cast", reopened.titleCastDao().observeAllCast().first().single().profileUrl)
            assertEquals("https://image/crew", reopened.titleCrewDao().observeAllCrew().first().single().profileUrl)
            assertEquals(1, reopened.personCreditsDao().observeSeasonCast().first().single().episodeCount)
            assertEquals("https://image/guest", reopened.personCreditsDao().observeSeasonCast().first().single().profileUrl)
            assertEquals("bw", reopened.episodeWatchEventDao().observeAllWatchEvents().first().single().colorMode)
            assertEquals("color", reopened.episodeReviewDao().observeReviews("title").first().single().colorMode)
            val intent = reopened.outboxDao().getPending().single()
            assertEquals("{\"immutable\":true}", intent.payloadJson); assertEquals(4, intent.attemptCount)
        } finally { reopened.close(); context.deleteDatabase(name) }
    }
}
