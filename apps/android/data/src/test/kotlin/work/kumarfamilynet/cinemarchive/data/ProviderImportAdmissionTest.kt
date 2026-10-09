package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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

internal fun providerDetails(type: MediaType = MediaType.MOVIE, tmdb: Int = 42) = MediaDetails(tmdb, type, "Provider film", 2026,
    "2026-01-01", "Director", listOf("Drama"), null, null, "Synopsis", 90, null, "en", "PG", null,
    cast = listOf(MediaCredit(7, "Actor", "Lead", 0, "https://image.test/actor.jpg", 4)),
    crew = listOf(MediaCrewCredit(8, "Director", "Director", "Directing", "https://image.test/director.jpg")),
    rtUrl = "https://www.rottentomatoes.com/m/provider_film", awardsCount = 3, bechdelOutcome = "pass", bechdelScore = "3/3",
    seasons = if (type == MediaType.TV) listOf(MediaSeason(0, 1, 2026,
        listOf(MediaEpisode(1, "Special", null, 30, crew = listOf(MediaCrewCredit(9, "Writer", "Writer", null)))),
        listOf(MediaCredit(10, "Guest", "Guest", 0)))) else emptyList())
internal fun providerItem(type: MediaType = MediaType.MOVIE, id: String = "stable") = SyncItem(SyncProvider.LETTERBOXD,
    id, type, "Provider film", 2026, ExternalIds(tmdb = 42), LibraryStatus.WATCHED, 4.0,
    listOf("2026-01-02", "2026-01-01", "2026-01-02"))

@RunWith(RobolectricTestRunner::class)
class ProviderImportAdmissionTest {
    private lateinit var db: LibraryDatabase
    private lateinit var box: MutationOutbox
    private var active = true
    private var fail = false
    private var switchOnEnqueue = false
    private val owner = BackupImportFixture.owner
    private val clock = Clock.fixed(Instant.parse(BackupImportFixture.at), ZoneOffset.UTC)
    private fun configure() {
        val dao = object : OutboxDao by db.outboxDao() {
            override suspend fun enqueue(entry: OutboxEntity) {
                check(!fail) { "Disk full" }; db.outboxDao().enqueue(entry)
                if (switchOnEnqueue) active = false
            }
        }
        box = MutationOutbox(dao, object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity) = PushResult.Retry("Offline")
        }, TitleConflictHandler(db.titleDao()), RoomTransactor(db), pendingProjectionKeys = { backupImportProtectionKeys(it, owner) })
    }
    private fun source() = ProviderImportAdmission(db, box, owner, { active }, clock)
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
        LibraryDatabase::class.java).allowMainThreadQueries().build(); configure() }
    @After fun close() { db.close() }

    @Test fun graphAndStableProviderLinkAreOneDurableCommand() = runBlocking {
        assertTrue(source().addNew(providerDetails(), providerItem()))
        val entry = db.outboxDao().getPending().single()
        val command = checkedImportCommand(entry, owner)
        assertEquals(listOf(ProviderTitleLink(SyncProvider.LETTERBOXD, "stable")), command.providerLinks)
        val ops = command.mapping.operations.importObjects()
        assertEquals("external_title_links", ops.last().getString("table"))
        assertEquals(entry.entityId, ops.last().getJSONObject("values").getString("title_id"))
        val viewings = db.viewingDao().observeAllViewings().first().sortedBy { it.date }
        assertEquals(listOf("2026-01-01", "2026-01-02"), viewings.map { it.date })
        assertNull(viewings.first().rating); assertEquals(4.0, viewings.last().rating!!, 0.0)
        assertEquals("", db.titleDao().getById(entry.entityId)!!.updatedAt)
        assertEquals(1, db.titleCastDao().observeAllCast().first().size)
        val stored = db.titleDao().getById(entry.entityId)!!
        assertEquals(providerDetails().rtUrl, stored.rtUrl)
        assertEquals(3, stored.awardsCount); assertEquals("pass", stored.bechdelOutcome); assertEquals("3/3", stored.bechdelScore)
        assertEquals("https://image.test/actor.jpg", db.titleCastDao().observeAllCast().first().single().profileUrl)
        assertEquals(4, db.titleCastDao().observeAllCast().first().single().episodeCount)
        assertEquals("https://image.test/director.jpg", command.mapping.graph.crew.single().profileUrl)
        assertEquals(entry.id, captureViewingGuard(viewings.first(), null, listOf(entry), owner.ownerId).operationId)
    }

    @Test fun watchedTvAndUnknownMovieDatesInventNoViewing() = runBlocking {
        assertTrue(source().addNew(providerDetails(MediaType.TV), providerItem(MediaType.TV)))
        assertTrue(db.viewingDao().observeAllViewings().first().isEmpty())
        assertEquals(1, db.episodeDao().observeAllEpisodes().first().size)
        assertTrue(db.episodeWatchEventDao().observeAllWatchEvents().first().isEmpty())
        assertTrue(source().addNew(providerDetails(tmdb = 43), providerItem(id = "other").copy(watchedDates = emptyList())))
        assertTrue(db.viewingDao().observeAllViewings().first().isEmpty())
    }

    @Test fun storageOrAccountFailureRollsBackGraphAndProvenanceTogether() = runBlocking {
        fail = true
        assertTrue(runCatching { source().addNew(providerDetails(), providerItem()) }.isFailure)
        assertEquals(0, db.titleDao().count()); assertTrue(db.outboxDao().getPending().isEmpty())
        fail = false; switchOnEnqueue = true
        assertTrue(runCatching { source().addNew(providerDetails(), providerItem()) }.isFailure)
        assertEquals(0, db.titleDao().count()); assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun repeatedNaturalTitleDoesNotMintASecondGraphOrRetargetPendingLink() = runBlocking {
        assertTrue(source().addNew(providerDetails(), providerItem()))
        val original = db.outboxDao().getPending().single()
        assertFalse(source().addNew(providerDetails(), providerItem()))
        assertTrue(runCatching { source().addNew(providerDetails(tmdb = 43), providerItem()) }.isFailure)
        assertEquals(original, db.outboxDao().getPending().single())
        assertEquals(1, db.titleDao().count())
    }

    @Test fun invalidProvenanceAndTamperedLinkCannotEnterOrChangeQueue() = runBlocking {
        assertTrue(runCatching { source().addNew(providerDetails(), providerItem(id = " ")) }.isFailure)
        assertTrue(source().addNew(providerDetails(), providerItem()))
        val entry = db.outboxDao().getPending().single()
        val payload = exactMetadataObject(entry.payloadJson)
        payload.getJSONObject(BACKUP_IMPORT_DATA).getJSONArray("providerLinks").getJSONObject(0).put("externalId", "different")
        assertTrue(runCatching { checkedImportCommand(entry.copy(payloadJson = metadataJson(payload)), owner) }.isFailure)
        assertEquals(entry, db.outboxDao().getPending().single())
    }

    @Test fun olderArchiveCommandsRemainValidWithoutProviderMetadata() {
        val entry = BackupImportFixture.entry()
        assertFalse(exactMetadataObject(entry.payloadJson).getJSONObject(BACKUP_IMPORT_DATA).has("providerLinks"))
        assertTrue(checkedImportCommand(entry, owner).providerLinks.isEmpty())
    }

    @Test fun providerQueueAndHistorySurviveRoomReopen() = runBlocking {
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("provider.db")
        db = LibraryDatabase.create(context, "provider.db"); configure()
        assertTrue(source().addNew(providerDetails(), providerItem()))
        val entry = db.outboxDao().getPending().single()
        db.close(); db = LibraryDatabase.create(context, "provider.db"); configure()
        assertEquals(entry, db.outboxDao().getPending().single())
        assertEquals(2, db.viewingDao().observeAllViewings().first().size)
        assertEquals("stable", checkedImportCommand(entry, owner).providerLinks.single().externalId)
    }
}
