package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

internal object BackupImportFixture {
    val owner = TicketOwnerScope("https://backup.invalid", TitleMetadataFixture.owner)
    const val at = "2026-10-09T00:01:00Z"
    fun id(n: Int) = "80000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    fun document(tmdb: Int = 42): JSONObject {
        val title = TitleMetadataFixture.entity().copy(id = id(1), tmdbId = tmdb, type = "TV", physicalMediaJson = "[{\"id\":\"copy\",\"format\":\"DVD\",\"opaque\":9007199254740993}]")
        val graph = LibraryExportGraph(listOf(title),
            seasons = listOf(SeasonEntity(id(2), title.id, 0, 1, 1, 2025)),
            episodes = listOf(EpisodeEntity(id(3), title.id, id(2), 1, "Special", "2025-01-01", 30, "Synopsis", null)),
            watches = listOf(EpisodeWatchEventEntity(id(4), id(3), null, "Undated", "bw")),
            ratings = listOf(EpisodeRatingEntity(id(5), id(3), 4.5, at)),
            reviews = listOf(EpisodeReviewEntity(id(6), id(3), "Review", at, "color")),
            viewings = listOf(ViewingEntity(id(7), title.id, "2026-10-01", 4.0, "History", "Cinema", listOf("Sam", "Sam"), id(8),
                companionsJson = "[{\"name\":\"Sam\",\"friendUserId\":\"${id(20)}\"},{\"name\":\"Sam\",\"friendUserId\":\"${id(21)}\"}]")),
            cast = listOf(TitleCastEntity(id(10), title.id, 1, "Actor", "Role", 0, null, 1)),
            crew = listOf(TitleCrewEntity(id(11), title.id, 2, "Director", "Director", "Directing")),
            seasonCast = listOf(SeasonCastEntity(id(12), title.id, id(2), 3, "Guest", "Role", 1)),
            episodeCrew = listOf(EpisodeCrewEntity(id(13), title.id, id(3), 4, "Writer", "Writer")),
            outings = listOf(CinemaOutingEntity(id(8), title.id, at, 20, 90, "2026-10-09T01:51:00Z", venue = "Cinema", format = null, ticketPrice = null,
                status = "COMPLETED", previousStatus = "WATCHLIST", completedViewingId = id(7), createdAt = at, updatedAt = at)))
        return graph.exportDocument("2026-10-09")
    }
    fun entry(): OutboxEntity {
        val doc = document()
        return OutboxEntity(id(30), "title", id(1), BACKUP_IMPORT_COMMAND,
            importPayload(owner, doc.getJSONArray("titles").getJSONObject(0), doc.getJSONArray("outings").importObjects(), at), 1)
    }
    fun receipt(entry: OutboxEntity): JSONObject {
        val rows = checkedImportCommand(entry, owner).mapping.operations.importObjects().mapIndexed { index, operation ->
            val key = operation.getJSONObject("key")
            val row = exactMetadataObject(metadataJson(operation.getJSONObject("values")))
            key.keys().forEach { row.put(it, key.get(it)) }
            row.put("id", key.opt("id") ?: id(100 + index)).put("user_id", owner.ownerId).put("updated_at", at)
            JSONObject().put("table", operation.getString("table")).put("key", key).put("row", row)
        }
        return JSONObject().put("operationId", entry.id).put("rows", JSONArray(rows))
    }
    fun current(entry: OutboxEntity): JSONObject = TitleMetadataFixture.row().put("id", entry.entityId)
        .put("tmdb_id", 42).put("type", "tv").put("title", "Current server title")
    fun envelope(entry: OutboxEntity, deleted: Boolean = false) = JSONObject().put("receipt", receipt(entry))
        .put("currentTitle", if (deleted) JSONObject.NULL else current(entry))
        .put("currentViewings", JSONArray()).put("currentOutings", JSONArray())
}

@RunWith(RobolectricTestRunner::class)
class BackupImportTest {
    private lateinit var db: LibraryDatabase
    private lateinit var outbox: MutationOutbox
    private var active = true
    private var failEnqueue = false
    private var failAfter: Int? = null
    private var failReplay = false
    private var failAck = false
    private var pushes = 0
    private var deliver: (OutboxEntity) -> PushResult = { PushResult.Retry("offline") }
    private val owner = BackupImportFixture.owner
    private val clock = Clock.fixed(Instant.parse(BackupImportFixture.at), ZoneOffset.UTC)
    private fun configure() {
        val dao = object : OutboxDao by db.outboxDao() {
            override suspend fun enqueue(entry: OutboxEntity) { if (failEnqueue || failAfter?.let { db.outboxDao().getPending().size >= it } == true) error("Disk full"); db.outboxDao().enqueue(entry) }
        }
        outbox = MutationOutbox(dao, object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity): PushResult { pushes++; return deliver(entry) }
        }, TitleConflictHandler(db.titleDao()), RoomTransactor(db), AppliedMutationHandler { entry, receipt ->
            BackupImportApplier(db, owner).apply(entry, receipt); check(!failAck) { "ACK disk full" }
        }, pendingProjectionKeys = { backupImportProtectionKeys(it, owner) }, outingOwnerScope = owner)
    }
    private fun source() = BackupImportRepository(db, outbox, owner, { active }, outbox::flush,
        { action -> check(!failReplay); action() }, clock)
    private suspend fun prepare(document: JSONObject = BackupImportFixture.document()) = source().prepareImport {
        ByteArrayInputStream(metadataJson(document).toByteArray())
    }
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        configure()
    }
    @After fun close() { db.close() }

    @Test fun completeGraphIsOneImmutableTransactionAndReceiptPredecessor() = runBlocking {
        val source = source(); val preview = source.prepareImport { metadataJson(BackupImportFixture.document()).byteInputStream() }
        assertEquals(1, preview.titleCount); assertEquals(1, preview.outingCount); assertTrue(preview.issues.isEmpty())
        assertEquals(1, source.admit(preview).admitted)
        val entry = db.outboxDao().getPending().single()
        val command = checkedImportCommand(entry, owner); val graph = command.mapping.graph
        assertEquals(13, command.mapping.operations.length())
        assertNotEquals(BackupImportFixture.id(1), entry.entityId)
        assertEquals(1, db.episodeWatchEventDao().observeAllWatchEvents().first().size)
        assertEquals(1, db.personCreditsDao().observeSeasonCast().first().size)
        assertEquals("9007199254740993", exactMetadataArray(db.titleDao().getById(entry.entityId)!!.physicalMediaJson!!).getJSONObject(0).get("opaque").toString())
        assertEquals(entry.id, importPredecessorFor(entry, "titles", entry.entityId, owner.ownerId))
        assertEquals(entry.id, importPredecessorFor(entry, "viewings", graph.viewings.single().id, owner.ownerId))
        assertEquals(entry.id, importPredecessorFor(entry, "cinema_outings", graph.outings.single().id, owner.ownerId))
        assertNull(importPredecessorFor(entry, "viewings", BackupImportFixture.id(55), owner.ownerId))
        assertEquals(entry.id, captureViewingGuard(graph.viewings.single(), null, listOf(entry), owner.ownerId).operationId)
        assertEquals(entry.id, captureViewingTitleGuard(graph.titles.single(), listOf(entry), owner.ownerId).operationId)
        assertEquals(OutingPrecondition.Operation(entry.id), resolveOutingPrecondition(graph.outings.single(), listOf(entry), owner))
        assertTrue(runCatching { importPredecessorFor(entry.copy(operation = "review"), "titles", entry.entityId, owner.ownerId) }.isFailure)
        assertTrue(outbox.pendingEntityKeys().contains("library_import:" + entry.entityId))
    }

    @Test fun enqueueFailureRollsBackEveryChildAndAdmitsNoCommand() = runBlocking {
        val source = source(); val preview = source.prepareImport { metadataJson(BackupImportFixture.document()).byteInputStream() }
        failEnqueue = true
        val result = source.admit(preview)
        assertEquals(0, result.admitted); assertEquals(1, result.remaining)
        assertEquals(0, db.titleDao().count()); assertTrue(db.outboxDao().getPending().isEmpty())
        assertTrue(db.episodeDao().observeAllEpisodes().first().isEmpty())
    }

    @Test fun duplicatesSkipWholeGraphAndASecondPreviewRechecksAtAdmission() = runBlocking {
        val source = source()
        val one = source.prepareImport { metadataJson(BackupImportFixture.document()).byteInputStream() }
        val two = source.prepareImport { metadataJson(BackupImportFixture.document()).byteInputStream() }
        assertEquals(1, source.admit(one).admitted)
        assertEquals(1, source.admit(two).skipped)
        val after = source.prepareImport { metadataJson(BackupImportFixture.document()).byteInputStream() }
        assertEquals(0, after.titleCount); assertEquals(1, after.skippedTitles); assertEquals(1, after.skippedOutings)
        assertEquals(1, db.cinemaOutingDao().observeAllOutings().first().size)
    }

    @Test fun literalCompanionNamesAndDistinctSameNameFriendLinksSurvivePlanning() = runBlocking {
        val document = BackupImportFixture.document()
        val names = document.getJSONArray("titles").getJSONObject(0).getJSONArray("viewings").getJSONObject(0).getJSONArray("companions")
        names.getJSONObject(0).put("name", "  Sam | Alex  ")
        val source = source(); val preview = source.prepareImport { metadataJson(document).byteInputStream() }
        assertEquals(1, preview.titleCount); source.admit(preview)
        val row = db.viewingDao().observeAllViewings().first().single()
        val raw = exactMetadataArray(row.companionsJson!!)
        assertEquals("  Sam | Alex  ", raw.getJSONObject(0).getString("name"))
        assertEquals(BackupImportFixture.id(20), raw.getJSONObject(0).getString("friendUserId"))
        assertEquals(BackupImportFixture.id(21), raw.getJSONObject(1).getString("friendUserId"))
    }

    @Test fun invalidEarlierDuplicateCannotSupplyAnotherTitlesCompanions() = runBlocking {
        val document = BackupImportFixture.document()
        val good = document.getJSONArray("titles").getJSONObject(0)
        val invalid = exactMetadataObject(metadataJson(good)).put("id", "invalid-first").put("status", "unknown")
            .put("viewings", JSONArray()).put("seasons", JSONArray())
        document.put("titles", JSONArray().put(invalid).put(good))
        val source = source(); val preview = source.prepareImport { metadataJson(document).byteInputStream() }
        assertEquals(1, preview.titleCount)
        source.admit(preview)
        val retained = exactMetadataArray(db.viewingDao().observeAllViewings().first().single().companionsJson!!)
        assertEquals(BackupImportFixture.id(20), retained.getJSONObject(0).getString("friendUserId"))
    }

    @Test fun malformedGraphNeverAdmitsPartialHistoryOrRoundedValues() = runBlocking {
        val bad = BackupImportFixture.document()
        bad.getJSONArray("titles").getJSONObject(0).getJSONArray("viewings").getJSONObject(0).put("rating", 4.55)
        val preview = prepare(bad)
        assertEquals(0, preview.titleCount); assertTrue(preview.issues.any { it.contains("rounding") })
        assertEquals(0, db.titleDao().count())
    }

    @Test fun jsonbNumericBoundsAndExpandedSizeAreCheckedBeforeAdmission() = runBlocking {
        val bad = BackupImportFixture.document()
        bad.getJSONArray("titles").getJSONObject(0).getJSONArray("physicalMedia").getJSONObject(0)
            .put("opaque", java.math.BigDecimal("1e-20000"))
        assertEquals(0, prepare(bad).titleCount)
        assertEquals(995L, importNumericExpansion(java.math.BigDecimal("1e-1000")))
        assertEquals(0L, importNumericExpansion(java.math.BigDecimal("0e-1000000")))
        assertTrue(runCatching { importNumericExpansion(java.math.BigDecimal("1e131072")) }.isFailure)
        val expanded = JSONArray()
        repeat(1100) { expanded.put(java.math.BigDecimal("1e-16000")) }
        bad.getJSONArray("titles").getJSONObject(0).getJSONArray("physicalMedia").getJSONObject(0).put("opaque", expanded)
        val preview = prepare(bad)
        assertEquals(0, preview.titleCount); assertTrue(preview.issues.any { it.contains("16 MiB") })
    }

    @Test fun malformedLaterIntentCannotBeDeletedAsAnImportDependency() = runBlocking {
        val source = source(); source.admit(source.prepareImport { metadataJson(BackupImportFixture.document()).byteInputStream() })
        deliver = { PushResult.Review("Rejected") }; outbox.flush()
        db.outboxDao().enqueue(OutboxEntity(BackupImportFixture.id(95), "viewing", BackupImportFixture.id(94), "review", "{invalid", 2))
        val saved = source.savedImports().first().single()
        assertTrue(runCatching { source.discardRejected(saved) }.isFailure)
        assertEquals(1, db.titleDao().count()); assertEquals(2, db.outboxDao().getPending().size)
    }

    @Test fun completedWithoutSurvivingViewingAndLongRuntimeRemainRepresentable() = runBlocking {
        val document = BackupImportFixture.document()
        document.getJSONArray("titles").getJSONObject(0).put("viewings", JSONArray())
        document.getJSONArray("outings").getJSONObject(0).remove("completedViewingId")
        document.getJSONArray("outings").getJSONObject(0).put("runtimeMinutes", 1800)
        assertEquals(1, prepare(document).titleCount)
        document.getJSONArray("outings").getJSONObject(0).put("previewsMinutes", 121)
        assertEquals(0, prepare(document).titleCount)
        document.getJSONArray("outings").getJSONObject(0).put("previewsMinutes", 20).put("runtimeMinutes", 0)
        assertEquals(0, prepare(document).titleCount)
    }

    @Test fun laterStorageFailureReportsPartialAdmissionWithoutRollingBackEarlierTitle() = runBlocking {
        val document = BackupImportFixture.document()
        val second = BackupImportFixture.document(43).getJSONArray("titles").getJSONObject(0)
        // A second independent graph, not ambiguous source identities.
        second.put("id", "second").put("seasons", JSONArray()).put("viewings", JSONArray())
        document.getJSONArray("titles").put(second)
        val source = source(); val prepared = source.prepareImport { metadataJson(document).byteInputStream() }
        assertEquals(2, prepared.titleCount)
        failAfter = 1
        val result = source.admit(prepared)
        assertEquals(1, result.admitted); assertEquals(1, result.remaining); assertEquals(1, db.titleDao().count())
        assertEquals(1, db.outboxDao().getPending().size)
    }

    @Test fun freshAckKeepsLaterTitleIntentAndItsFrozenImportDependency() = runBlocking {
        val source = source(); source.admit(source.prepareImport { metadataJson(BackupImportFixture.document()).byteInputStream() })
        val entry = db.outboxDao().getPending().single()
        val local = db.titleDao().getById(entry.entityId)!!
        outbox.atomically { outbox.enqueueTitleMetadata(local, TitleMetadataFixture.patch("Later"), owner.ownerId)
            db.titleDao().upsertAll(listOf(local.copy(tags = listOf("Later")))) }
        val next = db.outboxDao().getPending().last()
        assertEquals(entry.id, titleMetadataOperation(next, owner.ownerId).getString("expectedOperationId"))
        deliver = { if (it.id == entry.id) PushResult.Applied(BackupImportFixture.envelope(it)) else PushResult.Retry("offline") }
        outbox.flush()
        assertEquals("Current server title", db.titleDao().getById(entry.entityId)!!.title)
        assertEquals(listOf("Later"), db.titleDao().getById(entry.entityId)!!.tags)
        assertEquals(next.payloadJson, db.outboxDao().getPending().single().payloadJson)
    }

    @Test fun accountAndPreparedInstanceFencesPreventAdmission() = runBlocking {
        val source = source(); val preview = source.prepareImport { metadataJson(BackupImportFixture.document()).byteInputStream() }
        assertTrue(runCatching { source().admit(preview) }.isFailure)
        active = false
        assertTrue(runCatching { source.admit(preview) }.isFailure)
        assertEquals(0, db.titleDao().count())
    }

    @Test fun acceptedReceiptAfterRemoteDeletionDoesNotResurrectAndAckFailureRollsBack() = runBlocking {
        val source = source(); val preview = source.prepareImport { metadataJson(BackupImportFixture.document()).byteInputStream() }
        source.admit(preview)
        val entry = db.outboxDao().getPending().single()
        deliver = { PushResult.Applied(BackupImportFixture.envelope(it, deleted = true)) }
        failAck = true; outbox.flush()
        assertNotNull(db.titleDao().getById(entry.entityId)); assertEquals(entry.payloadJson, db.outboxDao().getPending().single().payloadJson)
        failAck = false; outbox.flush()
        assertNull(db.titleDao().getById(entry.entityId)); assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun unknownOutcomeCannotDiscardButRejectedChainRequiresExactComparisonAndReplay() = runBlocking {
        val source = source(); source.admit(source.prepareImport { metadataJson(BackupImportFixture.document()).byteInputStream() })
        val entry = db.outboxDao().getPending().single()
        outbox.flush()
        var saved = source.savedImports().first().single()
        assertFalse(saved.review); assertTrue(runCatching { source.discardRejected(saved) }.isFailure)
        deliver = { PushResult.Review("Definite atomic rejection") }; outbox.flush()
        saved = source.savedImports().first().single()
        val later = TitleMetadataFixture.entry().copy(entityId = entry.entityId, payloadJson = titleMetadataPayload(owner.ownerId,
            entry.entityId, TitleMetadataFixture.patch("Later"), null, entry.id).toString())
        db.outboxDao().enqueue(later)
        assertTrue(runCatching { source.discardRejected(saved) }.isFailure)
        saved = source.savedImports().first().single(); assertEquals(1, saved.dependentChanges)
        failReplay = true; assertTrue(runCatching { source.discardRejected(saved) }.isFailure)
        assertEquals(2, db.outboxDao().getPending().size)
        failReplay = false; source.discardRejected(saved)
        assertEquals(0, db.titleDao().count()); assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun rejectionRetryClearsOnlyEvidenceAndPreservesExactOperationAndExport() = runBlocking {
        val source = source(); source.admit(source.prepareImport { metadataJson(BackupImportFixture.document()).byteInputStream() })
        val before = db.outboxDao().getPending().single()
        deliver = { PushResult.Review("Rejected") }; outbox.flush()
        deliver = { PushResult.Retry("unknown") }; source.retry(before.id)
        val after = db.outboxDao().getPending().single()
        assertEquals(before.id, after.id); assertEquals(before.payloadJson, after.payloadJson); assertEquals(BACKUP_IMPORT_COMMAND, after.operation)
        val output = ByteArrayOutputStream(); source.exportSaved(before.id) { output }
        assertTrue(LibraryBackupCodec.parseBytes(output.toByteArray()) is LibraryBackupCodec.ParseResult.Success)
    }

    @Test fun graphAndExactQueueSurviveRoomReopen() = runBlocking {
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "import.db"; context.deleteDatabase(name)
        db = LibraryDatabase.create(context, name); configure()
        val source = source(); source.admit(source.prepareImport { metadataJson(BackupImportFixture.document()).byteInputStream() })
        val before = db.outboxDao().getPending().single(); val id = checkedImportCommand(before, owner).mapping.graph.viewings.single().id
        val companions = db.viewingDao().getById(id)!!.companionsJson
        db.close(); db = LibraryDatabase.create(context, name); configure()
        assertEquals(before, db.outboxDao().getPending().single()); assertEquals(companions, db.viewingDao().getById(id)!!.companionsJson)
    }
}
