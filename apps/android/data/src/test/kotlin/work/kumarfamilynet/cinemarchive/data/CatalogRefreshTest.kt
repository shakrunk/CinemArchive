package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.*

@RunWith(RobolectricTestRunner::class)
class CatalogRefreshTest {
    private lateinit var db: LibraryDatabase
    private lateinit var box: MutationOutbox
    private var active = true
    private val title = TitleMetadataFixture.entity().copy(tmdbId = 42, type = "TV", title = "Stored", synopsis = "Keep synopsis", tags = listOf("Keep tag"))
    private val season = SeasonEntity(BackupImportFixture.id(21), title.id, 1, 2, 1, 2020)
    private val episode = EpisodeEntity(BackupImportFixture.id(22), title.id, season.id, 1, "Old episode", "2020-01-01", 40, "Keep episode synopsis", "https://image/old")
    private fun fresh() = providerDetails(MediaType.TV).copy(title = "Refreshed", synopsis = null,
        seasons = listOf(MediaSeason(1, 2, 2020, listOf(MediaEpisode(1, "New episode", null, 42)),
            listOf(MediaCredit(90, "Guest", null, 0, "https://image/guest", 2))),
            MediaSeason(0, 1, 2019, listOf(MediaEpisode(4, "Special", null, 20)))))
    private fun repo(fetch: CreditMetadataFetcher = CreditMetadataFetcher { fresh() }) = CreditRefreshRepository(db, box, fetch, TitleMetadataFixture.owner, { active })
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        box = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter { override suspend fun push(entry: OutboxEntity) = PushResult.Retry("offline") },
            TitleConflictHandler(db.titleDao()), RoomTransactor(db), pendingProjectionKeys = CreditReceiptApplier(db, TitleMetadataFixture.owner)::protectionKeys)
        db.titleDao().upsertAll(listOf(title)); db.seasonDao().upsertAll(listOf(season))
        db.episodeDao().upsertAll(listOf(episode, episode.copy(id = BackupImportFixture.id(23), episodeNumber = 2, episodeName = "Omitted episode")))
        db.episodeWatchEventDao().upsertAll(listOf(EpisodeWatchEventEntity("watch", episode.id, "2026-01-01", "Keep history")))
    }
    @After fun close() { db.close() }

    @Test fun fullRefreshDurablyUpdatesCatalogPreservingTrackingAndOmittedEpisodes() = runBlocking {
        assertTrue(repo().refreshMetadata(title.id))
        val updated = db.titleDao().getById(title.id)!!
        assertEquals("Refreshed", updated.title); assertEquals("Keep synopsis", updated.synopsis)
        assertEquals(title.status, updated.status); assertEquals(title.rating, updated.rating); assertEquals(title.notes, updated.notes)
        assertEquals(title.tags, updated.tags); assertEquals("3/3", updated.bechdelScore)
        val queue = db.outboxDao().getPending()
        val metadata = queue.single { it.operation == TITLE_METADATA_COMMAND }
        val operation = titleMetadataOperation(metadata, TitleMetadataFixture.owner)
        assertEquals(title.updatedAt, operation.getString("expectedUpdatedAt"))
        assertFalse(operation.getJSONObject("values").has("status"))
        assertEquals("New episode", db.episodeDao().getById(episode.id)!!.episodeName)
        assertEquals(episode.synopsis, db.episodeDao().getById(episode.id)!!.synopsis)
        assertEquals("Omitted episode", db.episodeDao().getById(BackupImportFixture.id(23))!!.episodeName)
        assertEquals("Keep history", db.episodeWatchEventDao().observeAllWatchEvents().first().single().notes)
        assertEquals(1, db.seasonDao().observeSeasons(title.id).first().single().episodesWatched)
        assertEquals(2, queue.count { it.entityType == "title_catalog" }) // Specials fill and existing metadata.
        assertTrue("episode:${episode.id}" in box.pendingEntityKeys())
        val cast = db.titleCastDao().observeAllCast().first().single()
        assertEquals("https://image.test/actor.jpg", cast.profileUrl); assertEquals(4, cast.episodeCount)
        val creditValues = JSONObject(queue.single { it.entityType == "title_credits" }.payloadJson).getJSONArray("operations").importObjects()
            .single { it.getString("table") == "title_cast" }.getJSONObject("values")
        assertEquals(cast.profileUrl, creditValues.getString("profile_url")); assertEquals(4, creditValues.getInt("episode_count"))
    }

    @Test fun failedDurabilityAndChangedAccountRetainOriginalEntireGraph() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER no_catalog BEFORE INSERT ON mutation_outbox WHEN NEW.entityType = 'title_catalog' BEGIN SELECT RAISE(ABORT, 'full storage'); END")
        assertTrue(runCatching { repo().refreshMetadata(title.id) }.isFailure)
        assertEquals(title, db.titleDao().getById(title.id)); assertEquals(episode, db.episodeDao().getById(episode.id))
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertTrue(runCatching { repo(CreditMetadataFetcher { active = false; fresh() }).refreshMetadata(title.id) }.isFailure)
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun concurrentLocalTitleEditIsNotOverwrittenBySlowFetch() = runBlocking {
        val source = repo(CreditMetadataFetcher {
            db.titleDao().upsertAll(listOf(title.copy(tags = listOf("New draft"))))
            fresh()
        })
        assertTrue(runCatching { source.refreshMetadata(title.id) }.isFailure)
        assertEquals(listOf("New draft"), db.titleDao().getById(title.id)!!.tags)
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun freshCurrentEpisodeAckPreservesLaterRefreshAndNeverResurrectsDeletedEpisode() = runBlocking {
        repo().refreshMetadata(title.id)
        val entry = db.outboxDao().getPending().single { it.entityType == "title_catalog" && JSONObject(it.payloadJson).getJSONArray("catalog").length() == 0 }
        val operations = episodeCatalogOperations(entry, TitleMetadataFixture.owner)
        val row = JSONObject().put("id", episode.id).put("user_id", TitleMetadataFixture.owner).put("title_id", title.id)
            .put("season_id", season.id).put("episode_number", 1).put("episode_name", "Newer remote")
            .put("air_date", JSONObject.NULL).put("runtime", 55).put("synopsis", JSONObject.NULL).put("still_url", JSONObject.NULL)
        val receiptRows = operations.importObjects().map { op -> JSONObject().put("table", op.getString("table")).put("key", op.getJSONObject("key"))
            .put("row", if (op.getString("table") == "titles") TitleMetadataFixture.row() else row) }
        val envelope = JSONObject().put("receipt", JSONObject().put("operationId", entry.id).put("rows", JSONArray(receiptRows)))
            .put("currentRows", JSONArray().put(JSONObject().put("table", "episodes").put("key", JSONObject().put("id", episode.id)).put("row", row)))
        RoomTransactor(db).run { EpisodeCatalogFillApplier(db, TitleMetadataFixture.owner).apply(entry, envelope) }
        assertEquals("Newer remote", db.episodeDao().getById(episode.id)!!.episodeName)
        db.episodeDao().upsertAll(listOf(db.episodeDao().getById(episode.id)!!.copy(episodeName = "Later local refresh")))
        val nextPayload = JSONObject(entry.payloadJson).apply {
            getJSONArray("operations").getJSONObject(1).getJSONObject("values").put("episode_name", "Later local refresh")
        }
        val later = entry.copy(id = BackupImportFixture.id(55), payloadJson = nextPayload.toString())
        db.outboxDao().enqueue(later)
        RoomTransactor(db).run { EpisodeCatalogFillApplier(db, TitleMetadataFixture.owner).apply(entry, envelope) }
        assertEquals("Later local refresh", db.episodeDao().getById(episode.id)!!.episodeName)
        assertEquals(later.payloadJson, db.outboxDao().getPending().last().payloadJson)
        db.episodeDao().deleteById(episode.id)
        RoomTransactor(db).run { EpisodeCatalogFillApplier(db, TitleMetadataFixture.owner).apply(entry, envelope) }
        assertNull(db.episodeDao().getById(episode.id))
    }

    @Test fun bulkRefreshReportsPartialFailureAndKeepsSuccessfulDurableTitles() = runBlocking {
        val second = title.copy(id = BackupImportFixture.id(30), tmdbId = 99, title = "Unavailable")
        db.titleDao().upsertAll(listOf(second))
        val progress = mutableListOf<CatalogRefreshProgress>()
        val report = repo(CreditMetadataFetcher { if (it.tmdbId == 99) error("Provider unavailable") else fresh() }).refreshAll { progress += it }
        assertEquals(1, report.refreshed); assertEquals(1, report.failures.size)
        assertTrue(report.failures.single().contains("Unavailable")); assertEquals(2, progress.last().completed)
        assertEquals(second, db.titleDao().getById(second.id)); assertEquals("Refreshed", db.titleDao().getById(title.id)!!.title)
        assertTrue(db.outboxDao().getPending().isNotEmpty())
    }
}
