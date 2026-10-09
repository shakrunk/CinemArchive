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

@RunWith(RobolectricTestRunner::class)
class ProviderMergeTest {
    private lateinit var db: LibraryDatabase
    private lateinit var box: MutationOutbox
    private var active = true
    private var fail = false
    private var replayed = false
    private val owner = BackupImportFixture.owner
    private var current = JSONObject()
    private val remote = object : ProviderMergeRemote {
        override suspend fun push(entry: OutboxEntity) = PushResult.Retry("offline")
        override suspend fun current(titleId: String) = exactMetadataObject(metadataJson(current))
    }
    private val title get() = TitleMetadataFixture.entity().copy(id = BackupImportFixture.id(1), type = "MOVIE", status = "WATCHLIST", rating = null, tmdbId = 42)
    private fun configure() {
        val dao = object : OutboxDao by db.outboxDao() {
            override suspend fun enqueue(entry: OutboxEntity) { check(!fail) { "Disk full" }; db.outboxDao().enqueue(entry) }
        }
        box = MutationOutbox(dao, object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity) = remote.push(entry)
        }, TitleConflictHandler(db.titleDao()), RoomTransactor(db), pendingProjectionKeys = { providerMergeProtectionKeys(it, owner) })
    }
    private fun source() = ProviderMergeRepository(db, box, owner, { active }, remote, {}, { replayed = true; it() },
        Clock.fixed(Instant.parse(BackupImportFixture.at), ZoneOffset.UTC))
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        configure(); db.titleDao().upsertAll(listOf(title))
        current = JSONObject().put("currentTitle", TitleMetadataFixture.row().put("id", title.id).put("type", "movie")
            .put("status", "watchlist").put("rating", JSONObject.NULL)).put("currentViewings", JSONArray())
    }
    @After fun close() { db.close() }
    @Test fun optimisticGraphAndLinkCommitTogetherWithFrozenGuards() = runBlocking {
        assertTrue(source().merge(title.id, providerItem()))
        val entry = db.outboxDao().getPending().single(); val command = checkedProviderMerge(entry, owner)
        assertEquals("WATCHED", db.titleDao().getById(title.id)!!.status)
        assertEquals(4.0, db.titleDao().getById(title.id)!!.rating!!, 0.0)
        assertEquals(2, db.viewingDao().observeAllViewings().first().size)
        assertEquals(4, command.operations.length())
        assertEquals(title.updatedAt, command.operations.getJSONObject(0).getString("expectedUpdatedAt"))
        assertEquals("external_title_links", command.operations.getJSONObject(3).getString("table"))
        assertEquals(entry.id, captureViewingTitleGuard(db.titleDao().getById(title.id)!!, listOf(entry), owner.ownerId).operationId)
        assertEquals(entry.id, captureViewingGuard(command.viewings.first(), null, listOf(entry), owner.ownerId).operationId)
    }
    @Test fun enqueueFailureAndAccountChangeCannotLeakOptimism() = runBlocking {
        fail = true; assertTrue(runCatching { source().merge(title.id, providerItem()) }.isFailure)
        assertEquals(title, db.titleDao().getById(title.id)); assertTrue(db.viewingDao().observeAllViewings().first().isEmpty())
        fail = false; active = false
        assertTrue(runCatching { source().merge(title.id, providerItem()) }.isFailure)
        assertTrue(db.outboxDao().getPending().isEmpty())
    }
    @Test fun repeatedImportKeepsExistingRatingsDatesAndLinkOnlyIsNotTitleProof() = runBlocking {
        source().merge(title.id, providerItem())
        assertFalse(source().merge(title.id, providerItem().copy(rating = 5.0)))
        val entries = db.outboxDao().getPending(); val second = checkedProviderMerge(entries.last(), owner)
        assertEquals(1, second.operations.length()); assertTrue(second.viewings.isEmpty())
        assertEquals(4.0, db.titleDao().getById(title.id)!!.rating!!, 0.0)
        assertEquals(entries.first().id, captureViewingTitleGuard(db.titleDao().getById(title.id)!!, entries, owner.ownerId).operationId)
        assertNull(providerMergePredecessor(entries.last(), "titles", title.id, owner.ownerId))
    }
    @Test fun unknownCannotDiscardAndRejectedRecoveryRetainsDependentDraft() = runBlocking {
        val source = source(); source.merge(title.id, providerItem())
        val first = db.outboxDao().getPending().single()
        assertTrue(runCatching { source.compare(first.id) }.isFailure)
        box.atomically { box.enqueueTitleMetadata(db.titleDao().getById(title.id)!!, JSONObject().put("tags", JSONArray().put("Later")), owner.ownerId)
            db.titleDao().upsertAll(listOf(db.titleDao().getById(title.id)!!.copy(tags = listOf("Later")))) }
        val later = db.outboxDao().getPending().last()
        db.outboxDao().markForReview(first.id, "Definite rejection")
        val comparison = source.compare(first.id); assertEquals(1, comparison.dependentChanges)
        source.discard(comparison)
        val retained = db.outboxDao().getPending().single()
        assertEquals(later.id, retained.id); assertEquals(later.payloadJson, retained.payloadJson); assertEquals("review", retained.operation)
        assertTrue(replayed); assertEquals(listOf("Later"), db.titleDao().getById(title.id)!!.tags)
        assertEquals("WATCHLIST", db.titleDao().getById(title.id)!!.status)
        assertTrue(db.viewingDao().observeAllViewings().first().isEmpty())
    }
    @Test fun retryAndRoomReopenPreserveExactIdentityAndIntent() = runBlocking {
        db.close(); val context = ApplicationProvider.getApplicationContext<Context>(); context.deleteDatabase("merge.db")
        db = LibraryDatabase.create(context, "merge.db"); configure(); db.titleDao().upsertAll(listOf(title))
        source().merge(title.id, providerItem()); val before = db.outboxDao().getPending().single()
        db.close(); db = LibraryDatabase.create(context, "merge.db"); configure()
        assertEquals(before, db.outboxDao().getPending().single())
        db.outboxDao().markForReview(before.id, "rejected"); source().retry(before.id)
        val after = db.outboxDao().getPending().single()
        assertEquals(before.id, after.id); assertEquals(before.payloadJson, after.payloadJson); assertEquals(PROVIDER_MERGE_COMMAND, after.operation)
    }
    @Test fun changedQueueInvalidatesComparisonAndAttemptedDependentBlocksRemoval() = runBlocking {
        val source = source(); source.merge(title.id, providerItem()); val entry = db.outboxDao().getPending().single()
        box.atomically { box.enqueueTitleMetadata(db.titleDao().getById(title.id)!!, JSONObject().put("tags", JSONArray().put("New")), owner.ownerId) }
        db.outboxDao().markForReview(entry.id, "rejected"); val old = source.compare(entry.id)
        assertEquals(1, old.dependentChanges)
        val dependent = db.outboxDao().getPending().last(); db.outboxDao().recordFailure(dependent.id, "unknown")
        assertTrue(runCatching { source.discard(old) }.isFailure)
        assertTrue(runCatching { source.discard(source.compare(entry.id)) }.isFailure)
        assertEquals(2, db.outboxDao().getPending().size)
    }

    @Test fun pendingNewTitleLinkCannotBeRetargetedByMerge() = runBlocking {
        val admission = ProviderImportAdmission(db, box, owner, { active })
        assertTrue(admission.addNew(providerDetails(tmdb = 43), providerItem()))
        val original = db.outboxDao().getPending().single()
        db.outboxDao().markForReview(original.id, "rejected")
        val saved = db.outboxDao().getPending()
        assertTrue(runCatching { source().merge(title.id, providerItem()) }.isFailure)
        assertEquals(saved, db.outboxDao().getPending())
        assertEquals(title, db.titleDao().getById(title.id))
        // Same owned title is still a valid target, including an unresolved import.
        assertFalse(source().merge(original.entityId, providerItem()))
        assertEquals(original.entityId, db.outboxDao().getPending().last().entityId)
    }

    @Test fun pendingMergeLinkCannotBeRetargetedByNewTitleImport() = runBlocking {
        source().merge(title.id, providerItem())
        val original = db.outboxDao().getPending().single()
        db.outboxDao().recordFailure(original.id, "unknown")
        val saved = db.outboxDao().getPending()
        val admission = ProviderImportAdmission(db, box, owner, { active })
        assertTrue(runCatching { admission.addNew(providerDetails(tmdb = 43), providerItem()) }.isFailure)
        assertEquals(saved, db.outboxDao().getPending()); assertEquals(1, db.titleDao().count())
        assertFalse(admission.addNew(providerDetails(), providerItem()))
        assertEquals(saved, db.outboxDao().getPending())
    }
}
