package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope
import work.kumarfamilynet.cinemarchive.core.model.ViewingDraft

@RunWith(RobolectricTestRunner::class)
class LibraryBackupExportTest {
    private lateinit var db: LibraryDatabase
    private val owner = TicketOwnerScope("https://backup.invalid", TitleMetadataFixture.owner)
    private val clock = Clock.fixed(Instant.parse("2026-10-09T00:01:00Z"), ZoneOffset.UTC)
    private var active = true
    private val friendOne = "30000000-0000-4000-8000-000000000001"
    private val friendTwo = "30000000-0000-4000-8000-000000000002"
    private val companions get() = """[{"name":"Sam","friendUserId":"$friendOne"},{"name":"Sam","friendUserId":"$friendTwo"}]"""
    private val title get() = TitleMetadataFixture.entity()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() { db.close() }
    private fun repository(sync: suspend () -> Unit = {}) = LibraryBackupRepository(db, owner, { active }, sync, clock)

    @Test fun graphPreservesSpecialsIndependentLogsCreditsAndPhysicalMetadata() {
        val rich = title.copy(type = "TV", contentRating = "TV-MA", rtScore = 0, awardsCount = 0,
            inHomeCollection = false, physicalMediaJson = """[{"id":"copy","format":"DVD","unknown":9007199254740993}]""",
            studios = listOf("Studio"), bechdelScore = "1/3", customWatchUrl = "https://example.org/watch", notes = "Pending notes")
        val graph = LibraryExportGraph(listOf(rich),
            seasons = listOf(SeasonEntity("special", title.id, 0, 1, 1, 2025)),
            episodes = listOf(EpisodeEntity("episode", title.id, "special", 1, "Special", "2025-01-01", 30, "Episode synopsis", "https://example.org/still")),
            watches = listOf(EpisodeWatchEventEntity("watch", "episode", null, "Before joining", "bw")),
            ratings = listOf(EpisodeRatingEntity("rating", "episode", 4.5, "2026-10-01T12:00:00Z")),
            reviews = listOf(EpisodeReviewEntity("review", "episode", "Independent", "2026-10-02T12:00:00Z", "color")),
            viewings = listOf(ViewingEntity("view", title.id, null, 0.0, "Rewatch", "Cinema", listOf("Sam", "Sam"), "outing", companionsJson = companions)),
            cast = listOf(TitleCastEntity("cast", title.id, 7, "Actor", "Role", 0, "https://example.org/profile", 8)),
            crew = listOf(TitleCrewEntity("crew", title.id, 8, "Director", "Director", "Directing", "https://example.org/crew")),
            seasonCast = listOf(SeasonCastEntity("seasoncast", title.id, "special", 9, "Guest", "Role", 2, "https://example.org/guest", 1)),
            episodeCrew = listOf(EpisodeCrewEntity("epcrew", title.id, "episode", 10, "Writer", "Writer")),
        )
        val bytes = LibraryBackupCodec.encodeJsonValue(graph.exportDocument("2026-10-09")).toByteArray()
        val parsed = LibraryBackupCodec.parseBytes(bytes) as LibraryBackupCodec.ParseResult.Success
        assertEquals(1, parsed.sourceVersion)
        val exported = parsed.document.titles.single()
        assertEquals("Pending notes", exported.getString("notes")); assertFalse(exported.getBoolean("inHomeCollection"))
        assertEquals("9007199254740993", exported.getJSONArray("physicalMedia").getJSONObject(0).get("unknown").toString())
        assertEquals(8, exported.getJSONArray("cast").getJSONObject(0).getInt("episodeCount"))
        assertEquals("https://example.org/crew", exported.getJSONArray("crew").getJSONObject(0).getString("profileUrl"))
        val viewing = exported.getJSONArray("viewings").getJSONObject(0)
        assertFalse(viewing.has("date")); assertEquals(friendTwo, viewing.getJSONArray("companions").getJSONObject(1).getString("friendUserId"))
        val season = exported.getJSONArray("seasons").getJSONObject(0)
        assertEquals(0, season.getInt("seasonNumber")); assertEquals(1, season.getJSONArray("cast").getJSONObject(0).getInt("episodeCount"))
        val episode = season.getJSONArray("episodes").getJSONObject(0)
        assertFalse(episode.getJSONArray("watchEvents").getJSONObject(0).has("watchedAt"))
        assertEquals("bw", episode.getJSONArray("watchEvents").getJSONObject(0).getString("colorMode"))
        assertEquals("Independent", episode.getJSONArray("reviews").getJSONObject(0).getString("reviewText"))
        assertEquals(4.5, episode.getJSONArray("ratings").getJSONObject(0).getDouble("rating"), 0.0)
        assertEquals("Writer", episode.getJSONArray("writers").getString(0))
        assertFalse(exported.has("updatedAt")); assertFalse(exported.has("user_id"))
    }

    @Test fun managedClearNeverExportsLegacyCodeOrLocalPhotoPath() {
        val outing = outing().copy(ticketImagePath = "/private/photo.jpg", ticketBarcodePayload = "legacy", ticketBarcodeFormat = "QR_CODE")
        val base = LibraryExportGraph(listOf(title), outings = listOf(outing))
        val legacy = base.exportDocument("2026-10-09").getJSONArray("outings").getJSONObject(0)
        assertEquals("legacy", legacy.getString("ticketBarcodePayload")); assertFalse(legacy.has("ticketImagePath"))
        val clear = base.copy(ticketBarcodes = mapOf(outing.id to null)).exportDocument("2026-10-09").getJSONArray("outings").getJSONObject(0)
        assertFalse(clear.has("ticketBarcodePayload"))
        val managed = base.copy(ticketBarcodes = mapOf(outing.id to JSONObject().put("payload", "actual").put("format", "CODE_128")))
            .exportDocument("2026-10-09").getJSONArray("outings").getJSONObject(0)
        assertEquals("actual", managed.getString("ticketBarcodePayload")); assertFalse(managed.has("ticketAttachment"))
        assertEquals("completed", managed.getString("status")); assertEquals("view", managed.getString("completedViewingId"))
        assertEquals("watched", managed.getString("previousStatus"))
    }

    @Test fun unknownCompanionProvenanceBlocksUntilExplicitSyncRecoversIt() = runBlocking {
        db.titleDao().upsertAll(listOf(title))
        val viewing = ViewingEntity("view", title.id, null, null, null, null, listOf("Sam", "Sam"))
        db.viewingDao().upsert(viewing)
        val repository = repository { db.viewingDao().upsert(viewing.copy(companionsJson = companions)) }
        try { repository.prepareExport(); fail("Unknown links must not silently disappear") } catch (_: CompanionRecoveryRequired) { }
        repository.syncBeforeExport()
        assertEquals(1, repository.prepareExport().titleCount)
    }

    @Test fun pendingLocalRowsAreExportedWithoutSendingOrChangingQueue() = runBlocking {
        db.titleDao().upsertAll(listOf(title.copy(notes = "Saved offline")))
        val queued = TitleMetadataFixture.entry()
        db.outboxDao().enqueue(queued)
        val prepared = repository().prepareExport()
        val root = JSONObject(prepared.bytes.toString(Charsets.UTF_8))
        assertEquals("Saved offline", root.getJSONArray("titles").getJSONObject(0).getString("notes"))
        assertEquals("cinemarchive-2026-10-09.json", prepared.fileName)
        assertEquals(listOf(queued), db.outboxDao().getPending())
        assertFalse(root.has("outbox")); assertFalse(root.has("lists"))
    }

    @Test fun accountChangeAndForeignPreparedSnapshotCannotOpenDestination() = runBlocking {
        db.titleDao().upsertAll(listOf(title))
        val repository = repository()
        val prepared = repository.prepareExport()
        var opened = false
        try { repository().writeExport(prepared) { opened = true; ByteArrayOutputStream() }; fail() } catch (_: IllegalStateException) { }
        assertFalse(opened)
        active = false
        try { repository.writeExport(prepared) { opened = true; ByteArrayOutputStream() }; fail() } catch (_: IllegalStateException) { }
        assertFalse(opened)
    }

    @Test fun writeFailureIsReportedAndSamePreparedBytesCanBeRetried() = runBlocking {
        db.titleDao().upsertAll(listOf(title))
        val repository = repository(); val prepared = repository.prepareExport()
        try { repository.writeExport(prepared) { object : OutputStream() { override fun write(value: Int) { throw IOException("full") } } }; fail() }
        catch (e: IOException) { assertEquals("full", e.message) }
        val output = ByteArrayOutputStream()
        repository.writeExport(prepared) { output }
        assertArrayEquals(prepared.bytes, output.toByteArray())
    }

    @Test fun occurrenceMatchingRetainsDistinctFriendsWithSameName() {
        assertEquals(companions, retainCompanionsJson(companions, listOf("Sam", "Sam"), listOf("Sam", "Sam")))
        val edited = JSONArray(retainCompanionsJson(companions, listOf("Sam", "Sam"), listOf("Sam", "New", "Sam")))
        assertEquals(friendOne, edited.getJSONObject(0).getString("friendUserId"))
        assertFalse(edited.getJSONObject(1).has("friendUserId"))
        assertEquals(friendTwo, edited.getJSONObject(2).getString("friendUserId"))
        assertNull(retainCompanionsJson(null, listOf("Sam"), listOf("Sam")))
    }

    @Test fun capturedUnrelatedViewingEditDoesNotSendNameOnlyCompanions() {
        val draft = ViewingDraft(ViewingCommandFixture.viewing, "2026-10-01", 4.0, "Old", null, listOf("Sam", "Sam"))
        val saved = draft.copy(notes = "New", openingContext = viewingOpening(owner.ownerId, title.id, draft.id, draft,
            ViewingGuard(revision = ViewingCommandFixture.baseline), ViewingGuard(revision = ViewingCommandFixture.baseline), emptyList(), companions))
        val captured = checkedViewingOpening(saved, owner.ownerId, title.id)
        val fields = captured.fields(saved)
        assertEquals(setOf("notes"), fields.keys().asSequence().toSet())
        val changed = captured.fields(saved.copy(companions = listOf("Sam", "New", "Sam")))
        assertEquals(friendTwo, changed.getJSONArray("companions").getJSONObject(2).getString("friendUserId"))
    }

    @Test fun unknownPartialNameEditsRequireRecoveryButNotesAndExplicitReplacementWork() {
        assertNull(retainCompanionsJson(null, listOf("Sam"), listOf("Sam")))
        try { retainCompanionsJson(null, listOf("Sam"), listOf("Sam", "New")); fail() }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("Sync before changing")) }
        assertEquals("[]", retainCompanionsJson(null, listOf("Sam"), emptyList()))
        assertEquals("New", JSONArray(retainCompanionsJson(null, listOf("Sam"), listOf("New"))).getJSONObject(0).getString("name"))
        val draft = ViewingDraft(ViewingCommandFixture.viewing, null, null, "Old", null, listOf("Sam"))
        val opening = viewingOpening(owner.ownerId, title.id, draft.id, draft,
            ViewingGuard(revision = ViewingCommandFixture.baseline), ViewingGuard(revision = ViewingCommandFixture.baseline), emptyList())
        val edited = draft.copy(notes = "New", openingContext = opening)
        assertEquals(setOf("notes"), checkedViewingOpening(edited, owner.ownerId, title.id).fields(edited).keys().asSequence().toSet())
        val oldOpening = JSONObject(opening).apply { remove("companionsKnown") }.toString()
        val retained = edited.copy(companions = listOf("Sam", "New"), openingContext = oldOpening)
        try { checkedViewingOpening(retained, owner.ownerId, title.id).fields(retained); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(oldOpening, retained.openingContext)
    }

    @Test fun emptyLibraryAndEmptyNamesNeedNoBackfillAndRawLiteralNamesRemainExact() = runBlocking {
        assertEquals(0, repository().prepareExport().titleCount)
        db.titleDao().upsertAll(listOf(title))
        db.viewingDao().upsert(ViewingEntity("empty", title.id, null, null, null, null))
        assertEquals(1, repository().prepareExport().titleCount)
        val raw = """[{"name":"Sam | Alex","friendUserId":"$friendOne"}]"""
        db.viewingDao().upsert(ViewingEntity("literal", title.id, null, null, null, null, listOf("Sam ", " Alex"), companionsJson = raw))
        val exported = JSONObject(repository().prepareExport().bytes.toString(Charsets.UTF_8))
        val rows = exported.getJSONArray("titles").getJSONObject(0).getJSONArray("viewings")
        val literal = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == "literal" }
        assertEquals("Sam | Alex", literal.getJSONArray("companions").getJSONObject(0).getString("name"))
        assertEquals(listOf("Sam | Alex"), savedCompanionNames(raw, listOf("Sam ", " Alex")))
    }

    @Test fun reopenedRoomExportsSameSavedCompanionLinksAndPendingIntent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "backup-export.db"
        context.deleteDatabase(name)
        var disk = LibraryDatabase.create(context, name)
        try {
            disk.titleDao().upsertAll(listOf(title.copy(notes = "Not yet synced")))
            disk.viewingDao().upsert(ViewingEntity("view", title.id, null, 4.0, "History", null,
                listOf("Sam", "Sam"), companionsJson = companions))
            disk.outboxDao().enqueue(TitleMetadataFixture.entry())
            val before = LibraryBackupRepository(disk, owner, { true }, {}, clock).prepareExport()
            disk.close(); disk = LibraryDatabase.create(context, name)
            val after = LibraryBackupRepository(disk, owner, { true }, {}, clock).prepareExport()
            assertArrayEquals(before.bytes, after.bytes)
            assertEquals(listOf(TitleMetadataFixture.entry()), disk.outboxDao().getPending())
            assertEquals(companions, disk.viewingDao().getById("view")!!.companionsJson)
        } finally { disk.close(); context.deleteDatabase(name) }
    }

    private fun outing() = CinemaOutingEntity("outing", title.id, "2026-10-01T19:00:00Z", 20, 90, "2026-10-01T20:50:00Z",
        "Cinema", emptyList(), "IMAX", 12.5, status = "COMPLETED", previousStatus = "WATCHED", completedViewingId = "view",
        createdAt = "2026-10-01T12:00:00Z", updatedAt = "2026-10-01T12:00:00Z")
}
