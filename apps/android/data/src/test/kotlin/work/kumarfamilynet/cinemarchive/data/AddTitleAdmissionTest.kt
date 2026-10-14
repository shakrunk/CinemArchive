package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.*

@RunWith(RobolectricTestRunner::class)
class AddTitleAdmissionTest {
    private lateinit var db: LibraryDatabase
    private lateinit var source: AddTitleAdmission
    private var current = true
    private var fail = false
    private var changeOwner = false
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            LibraryDatabase::class.java).allowMainThreadQueries().build()
        val dao = object : OutboxDao by db.outboxDao() {
            override suspend fun enqueue(entry: OutboxEntity) {
                check(!fail) { "Disk full" }
                db.outboxDao().enqueue(entry)
                if (changeOwner) current = false
            }
        }
        val box = MutationOutbox(dao, object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity) = PushResult.Retry("Offline")
        }, TitleConflictHandler(db.titleDao()), RoomTransactor(db))
        source = AddTitleAdmission(db, box, BackupImportFixture.owner, { current })
    }
    @After fun close() = db.close()
    private fun request(type: MediaType = MediaType.MOVIE) = AddTitleRequest(providerDetails(type),
        LibraryStatus.WATCHED, 4.0, "Remember this", null, listOf(" Favorite ", "Favorite", ""))

    @Test fun ordinaryWatchedTvRetainsUndatedViewingAndEpisodeIntent() = runBlocking {
        val id = source.add(request(MediaType.TV).copy(seasonProgress = mapOf(0 to 1)))
        val title = db.titleDao().getById(id)!!
        assertEquals("", title.updatedAt)
        assertEquals(listOf("Favorite"), title.tags)
        assertEquals("Remember this", title.notes)
        val viewing = db.viewingDao().observeAllViewings().first().single()
        assertNull(viewing.date); assertEquals("Remember this", viewing.notes)
        assertNull(db.episodeWatchEventDao().observeAllWatchEvents().first().single().watchedAt)
        val entry = db.outboxDao().getPending().single()
        val command = checkedImportCommand(entry, BackupImportFixture.owner)
        assertTrue(command.providerLinks.isEmpty())
        assertEquals(1, command.mapping.graph.watches.size)
        assertEquals(1, command.mapping.graph.viewings.size)
    }
    @Test fun duplicateAdmissionKeepsOriginalIdentityAndRequest() = runBlocking {
        val id = source.add(request())
        val entry = db.outboxDao().getPending().single()
        assertEquals(id, source.add(request().copy(notes = "Changed")))
        assertEquals(entry, db.outboxDao().getPending().single())
        assertEquals("Remember this", db.titleDao().getById(id)!!.notes)
    }
    @Test fun storageAndOwnerFailuresRollBackWholeGraph() = runBlocking {
        fail = true
        assertTrue(runCatching { source.add(request()) }.isFailure)
        assertEquals(0, db.titleDao().count())
        fail = false; changeOwner = true
        assertTrue(runCatching { source.add(request()) }.isFailure)
        assertEquals(0, db.titleDao().count())
        assertTrue(db.outboxDao().getPending().isEmpty())
    }
    @Test fun invalidProgressNeverWritesAndWatchlistHasNoViewing() = runBlocking {
        assertTrue(runCatching { source.add(request(MediaType.TV).copy(seasonProgress = mapOf(0 to 2))) }.isFailure)
        assertEquals(0, db.titleDao().count())
        source.add(request().copy(status = LibraryStatus.WATCHLIST))
        assertTrue(db.viewingDao().observeAllViewings().first().isEmpty())
    }

    @Test fun removedPendingGraphCannotBeRemintedUnderAnotherIdentity() = runBlocking {
        val id = source.add(request())
        val original = db.outboxDao().getPending().single()
        db.titleDao().deleteById(id)
        assertTrue(runCatching { source.add(request()) }.isFailure)
        assertEquals(original, db.outboxDao().getPending().single())
        assertEquals(0, db.titleDao().count())
    }
}
