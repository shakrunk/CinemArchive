package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.*

@RunWith(RobolectricTestRunner::class)
class TitlePinsRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val owner = id(1); private val title = id(2)
    private val file get() = File(context.cacheDir, "pins.preferences_pb")
    private lateinit var job: Job
    private lateinit var db: LibraryDatabase
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: TitlePinsRepository
    private lateinit var outbox: MutationOutbox
    private var active = true; private var failStore = false; private var pushes = 0
    private var remotePins = mapOf<String, String>()
    private var fetch: (suspend () -> Map<String, String>)? = null
    private fun open() {
        job = SupervisorJob()
        val underlying = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
        store = object : DataStore<Preferences> {
            override val data = underlying.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (failStore) error("Disk full")
                return underlying.updateData(transform)
            }
        }
        db = Room.databaseBuilder(context, LibraryDatabase::class.java, "pins.db").setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        val remote = object : TitlePinRemote {
            override suspend fun fetch() = this@TitlePinsRepositoryTest.fetch?.invoke() ?: remotePins
            override suspend fun push(entry: OutboxEntity): PushResult {
                pushes++
                val mode = JSONObject(entry.payloadJson).let { if (it.isNull("variant")) null else it.getString("variant") }
                remotePins = if (mode == null) remotePins - title else remotePins + (title to mode)
                return PushResult.Applied(envelope(entry, mode))
            }
        }
        outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity) = repo.push(entry)
        }, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db), appliedHandler = AppliedMutationHandler { entry, receipt -> repo.apply(entry, receipt) })
        repo = TitlePinsRepository(db, store, outbox, owner, { active }, remote, {})
    }
    @Before fun setup() = runBlocking {
        file.delete(); context.deleteDatabase("pins.db"); open()
        db.titleDao().upsertAll(listOf(TitleEntity(title, SPIDER_NOIR_TMDB_ID, "TV", "Noir", 2026, null, emptyList(), null, null, null, 30, null,
            "WATCHING", null, null, "2026-10-09T00:00:00Z", "2026-10-09T00:00:00Z", null, null, null)))
        db.seasonDao().upsertAll(listOf(SeasonEntity(id(3), title, 1, 10, 10, 2026)))
        db.episodeDao().upsertAll(listOf(EpisodeEntity(id(4), title, id(3), 1, "One", null, 30)))
        db.episodeWatchEventDao().upsertAll(listOf(EpisodeWatchEventEntity(id(5), id(4), null, colorMode = "bw"), EpisodeWatchEventEntity(id(6), id(4), null, colorMode = "color")))
    }
    @After fun cleanup() = runBlocking { job.cancelAndJoin(); db.close(); file.delete(); context.deleteDatabase("pins.db"); Unit }
    private fun row(mode: String) = JSONObject().put("user_id", owner).put("title_id", title).put("easter_egg_key", NOIR_PIN_KEY).put("pinned_variant", mode)
    private fun envelope(entry: OutboxEntity, current: String?): JSONObject {
        val operation = titlePinOperations(entry, owner).getJSONObject(0)
        val result = JSONObject().put("table", "user_title_pins").put("key", operation.getJSONObject("key"))
        if (operation.getString("action") == "delete") result.put("deleted", true)
        else result.put("row", row(operation.getJSONObject("values").getString("pinned_variant")))
        return JSONObject().put("ownerId", owner).put("titleId", title).put("receipt", JSONObject().put("operationId", entry.id).put("rows", org.json.JSONArray().put(result)))
            .put("current", current?.let(::row) ?: JSONObject.NULL)
    }
    @Test fun offlinePinAndUnpinSurviveRestartAsSameImmutableCommands() = runBlocking {
        repo.set(title, "bw"); repo.set(title, null)
        val originals = db.outboxDao().getPending()
        assertEquals(2, originals.size); assertNull(repo.state.first().pins[title])
        job.cancelAndJoin(); db.close(); open()
        assertEquals(originals, db.outboxDao().getPending())
        outbox.flush()
        assertTrue(db.outboxDao().getPending().isEmpty()); assertNull(repo.state.first().pins[title])
    }
    @Test fun freshCurrentProjectionReplacesOldReceiptButLaterPendingPinWins() = runBlocking {
        repo.set(title, "bw"); repo.set(title, null)
        val first = db.outboxDao().getPending().first()
        outbox.atomically { repo.apply(first, envelope(first, "color")); db.outboxDao().remove(first.id) }
        assertNull(repo.state.first().pins[title])
        db.outboxDao().remove(db.outboxDao().getPending().single().id)
        assertEquals("color", repo.state.first().pins[title])
    }
    @Test fun failedLocalAckRetainsOriginalCommandAndRetryCompletes() = runBlocking {
        repo.set(title, "bw"); val original = db.outboxDao().getPending().single()
        failStore = true; outbox.flush()
        assertEquals(original.id, db.outboxDao().getPending().single().id)
        assertEquals(original.payloadJson, db.outboxDao().getPending().single().payloadJson)
        failStore = false; outbox.flush()
        assertEquals(2, pushes); assertTrue(db.outboxDao().getPending().isEmpty()); assertEquals("bw", repo.state.first().pins[title])
    }
    @Test fun fullFetchCannotLandAfterANewerAckAndFullEmptyClearsRemoteUnpin() = runBlocking {
        repo.set(title, "bw")
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fetch = { started.complete(Unit); release.await(); mapOf(title to "color") }
        val refreshing = launch { repo.refresh() }; started.await()
        val flushing = launch { outbox.flush() }; yield(); assertEquals(0, pushes)
        release.complete(Unit); refreshing.join(); flushing.join()
        assertEquals("bw", repo.state.first().pins[title])
        fetch = { emptyMap() }; repo.refresh(); assertNull(repo.state.first().pins[title])
    }
    @Test fun knownMissingParentOutcomeClearsCacheWithoutTouchingHistoryOrLaterOverlay() = runBlocking {
        remotePins = mapOf(title to "bw"); repo.refresh()
        repo.set(title, "bw"); repo.set(title, "color")
        val first = db.outboxDao().getPending().first()
        outbox.atomically {
            repo.apply(first, JSONObject().put("ownerId", owner).put("titleId", title).put("missingParent", true).put("rejectionCode", "P0002"))
            db.outboxDao().remove(first.id)
        }
        assertEquals("color", repo.state.first().pins[title]); assertEquals(2, db.episodeWatchEventDao().observeAllWatchEvents().first().size)
        assertNotNull(db.titleDao().getById(title))
    }
    @Test fun staleOwnerFetchAndWritesCannotPublishPins() = runBlocking {
        fetch = { active = false; mapOf(title to "bw") }
        try { repo.refresh(); fail() } catch (_: IllegalStateException) { }
        assertFalse(repo.state.first().loaded)
        try { repo.set(title, "bw"); fail() } catch (_: IllegalStateException) { }
        active = true; assertTrue(repo.state.first().pins.isEmpty()); assertTrue(db.outboxDao().getPending().isEmpty())
    }
    @Test fun incompleteMainEpisodeRejectsPinWhileUnpinNeedsNoEarnedHistory() = runBlocking {
        db.episodeDao().upsertAll(listOf(EpisodeEntity(id(7), title, id(3), 2, "Two", null, 30)))
        try { repo.set(title, "bw"); fail() } catch (_: IllegalStateException) { }
        assertTrue(db.outboxDao().getPending().isEmpty())
        repo.set(title, null); assertEquals(1, db.outboxDao().getPending().size)
    }
    @Test fun ackRejectsWrongHeadForeignCurrentAndNontransactionalCalls() = runBlocking {
        repo.set(title, "bw"); val entry = db.outboxDao().getPending().single()
        try { repo.apply(entry, envelope(entry, "bw")); fail() } catch (_: IllegalStateException) { }
        val wrong = envelope(entry, "bw"); wrong.getJSONObject("current").put("user_id", id(9))
        try { outbox.atomically { repo.apply(entry, wrong) }; fail() } catch (_: IllegalArgumentException) { }
        try { outbox.atomically { repo.apply(entry.copy(id = id(9)), envelope(entry, "bw")) }; fail() } catch (_: IllegalArgumentException) { }
        assertEquals(entry, db.outboxDao().getPending().single())
    }
    companion object { private fun id(n: Int) = "10000000-0000-4000-8000-${n.toString().padStart(12, '0')}" }
}
