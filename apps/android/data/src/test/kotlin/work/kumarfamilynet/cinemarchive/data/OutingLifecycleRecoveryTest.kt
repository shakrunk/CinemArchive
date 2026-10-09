package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
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

@RunWith(RobolectricTestRunner::class)
class OutingLifecycleRecoveryTest {
    private lateinit var db: LibraryDatabase
    private lateinit var outbox: MutationOutbox
    private lateinit var original: OutboxEntity
    private val owner = OutingCommandFixture.owner
    private val title = OutingCommandFixture.title
    private val outing = OutingCommandFixture.outing
    private var signedIn: SupabaseSession? = SupabaseSession("token", owner)
    private var remoteOuting = OutingCommandFixture.row()
    private var remoteViewing: JSONObject? = null
    private val archive = MemoryArchive()
    private val attempts = mutableListOf<String>()
    private var synchronizations = 0
    private val client = SupabaseRestClient("https://project.example", "anon", OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val path = request.url.encodedPath
        val rpc = "/rpc/" in path
        val body = when {
            rpc -> {
                attempts += Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                "{\"message\":\"Confirmation unavailable\"}"
            }
            path.endsWith("/cinema_outings") -> JSONArray().put(remoteOuting).toString()
            path.endsWith("/viewings") -> JSONArray().apply { remoteViewing?.let { put(it) } }.toString()
            path.endsWith("/titles") -> JSONArray().put(JSONObject().put("id", title).put("user_id", owner)
                .put("title", "Film").put("updated_at", OutingCommandFixture.baseline).put("tags", JSONArray())
                .put("status", "watchlist").put("rating", JSONObject.NULL)).toString()
            else -> error("Unexpected request: $path")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (rpc) 503 else 200)
            .message("Fixture").body(body.toResponseBody()).build()
    }.build())

    private fun repository() = OutingLifecycleRecovery(db, outbox, owner, client, { signedIn }, archive,
        replayBoundary = { action -> action() }, synchronize = { synchronizations++ })

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity): PushResult = error("Only explicit recovery may send in this fixture")
        }, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db),
            outingOwnerScope = TicketOwnerScope("https://project.example", owner))
        db.titleDao().upsertAll(listOf(TitleEntity(title, 42, "MOVIE", "Film", 2026, null, emptyList(), null, null,
            null, 90, null, "WATCHLIST", null, null, OutingCommandFixture.baseline, OutingCommandFixture.baseline)))
        db.cinemaOutingDao().upsert(OutingCommandFixture.entity())
        OutingLifecycleRepository(db, outbox, owner) { signedIn?.userId == owner }
            .completeDue(Instant.parse("2100-01-01T00:00:00Z"), ZoneId.of("UTC"))
        original = db.outboxDao().getPending().single()
    }
    @After fun tearDown() { db.close() }

    private suspend fun reject() { db.outboxDao().markForReview(original.id, "Rejected by server; compare current plan") }
    private suspend fun dependent(): OutboxEntity {
        val command = completionCommand(original)
        return OutboxEntity(UUID.randomUUID().toString(), "viewing", command.provisionalViewingId, AWAITING_COMPLETION,
            awaitingCompletionPayload(original, "viewing", "update", JSONObject().put("notes", "Preserved note")).toString(),
            original.createdAt + 1).also { db.outboxDao().enqueue(it) }
    }

    @Test fun rejectedErrorDoesNotHideReviewAndComparisonIdentifiesTheMovie() = runBlocking {
        reject()
        remoteViewing = JSONObject().put("id", completionCommand(original).provisionalViewingId).put("title_id", title)
            .put("user_id", owner).put("viewed_at", "2026-10-08").put("rating", JSONObject.NULL)
            .put("notes", "Shared memory").put("venue", "Remote cinema").put("companions", JSONArray())
            .put("outing_id", outing).put("updated_at", OutingCommandFixture.baseline)
        val repo = repository()
        val card = repo.items().single()
        assertNull("A valid rejected action must retain its Review button", card.error)
        assertTrue(card.title.contains("Film"))
        val review = repo.review(original.id)
        assertTrue(review.title.contains("Film"))
        assertFalse(review.pendingAttempt)
        assertTrue(review.message!!.contains("Rejected"))
        assertEquals(listOf("lifecycleAction"), review.fields.filter { it.selectable }.map { it.key })
        assertFalse(review.fields.any { it.current.contains("\"user_id\"") })
        assertTrue(review.fields.any { it.current.contains("Shared memory") })
        assertEquals(original.payloadJson, JSONObject(repo.exportOriginal(original.id)).getJSONObject("original").getString("payloadJson"))
        assertTrue(attempts.isEmpty())
    }

    @Test fun staleComparisonDoesNotReplaceTheRejectedCommand() = runBlocking {
        reject()
        val repo = repository()
        val reviewed = repo.review(original.id)
        val before = db.outboxDao().getPending()
        remoteOuting.put("updated_at", "2026-10-09T12:00:00Z").put("venue", "Changed cinema")
        assertEquals(OutingRecoveryOutcome.CHANGED, repo.apply(original.id, reviewed.remoteVersion, setOf("lifecycleAction")))
        assertEquals(before, db.outboxDao().getPending())
        assertEquals(0, synchronizations)
        assertTrue(attempts.isEmpty())
    }

    @Test fun unknownOriginalRetainsIdenticalRpcAndCannotBeDiscardedOrSuperseded() = runBlocking {
        val repo = repository()
        assertTrue(repo.pendingAttempt(original.id))
        repeat(2) {
            assertTrue(runCatching { repo.apply(original.id, "ignored different snapshot", setOf("other")) }.isFailure)
            assertEquals(original, db.outboxDao().getPending().single())
            assertTrue(runCatching { repo.discard(original.id) }.isFailure)
        }
        assertEquals(2, attempts.size)
        assertEquals(attempts[0], attempts[1])
        assertEquals(original.id, JSONObject(attempts[0]).getString("p_operation_id"))
        assertEquals("pending", JSONObject(archive.records.value.getValue(original.id)).getString("state"))
    }

    @Test fun reviewedReplacementKeepsFifoAndPreservesDependentBytesForSeparateReview() = runBlocking {
        val child = dependent()
        val unrelated = child.copy(id = UUID.randomUUID().toString(), entityType = "list", operation = "upsert",
            payloadJson = "{\"name\":\"Later list\"}", createdAt = child.createdAt + 1)
        db.outboxDao().enqueue(unrelated)
        reject()
        val repo = repository()
        val reviewed = repo.review(original.id)
        assertEquals(OutingRecoveryOutcome.APPLIED, repo.apply(original.id, reviewed.remoteVersion, setOf("lifecycleAction")))
        val queue = db.outboxDao().getPending()
        assertNotEquals(original.id, queue[0].id)
        assertEquals(OUTING_COMPLETION, queue[0].operation)
        assertEquals(original.createdAt, queue[0].createdAt)
        assertEquals(listOf(child.id, unrelated.id), queue.drop(1).map { it.id })
        assertEquals("review", queue[1].operation)
        assertEquals(child.payloadJson, queue[1].payloadJson)
        assertEquals(unrelated, queue[2])
        assertEquals("reapplied", JSONObject(archive.records.value.getValue(original.id)).getString("state"))
        assertEquals(1, synchronizations)
    }

    @Test fun failedReplacementRollsBackDependentChangesAndNeverFinalizesArchive() = runBlocking {
        dependent(); reject()
        val repo = repository()
        val reviewed = repo.review(original.id)
        val before = db.outboxDao().getPending()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_replacement BEFORE UPDATE OF id ON mutation_outbox BEGIN SELECT RAISE(ABORT, 'Storage failure'); END")
        assertTrue(runCatching { repo.apply(original.id, reviewed.remoteVersion, setOf("lifecycleAction")) }.isFailure)
        assertEquals(before, db.outboxDao().getPending())
        assertEquals("pending", JSONObject(archive.records.value.getValue(original.id)).getString("state"))
        assertEquals(0, synchronizations)
    }

    @Test fun accountLossDuringArchivePreservationCannotAdmitReplacement() = runBlocking {
        reject()
        val repo = repository()
        val reviewed = repo.review(original.id)
        val before = db.outboxDao().getPending()
        archive.afterPut = { signedIn = null }
        assertTrue(runCatching { repo.apply(original.id, reviewed.remoteVersion, setOf("lifecycleAction")) }.isFailure)
        assertEquals(before, db.outboxDao().getPending())
        assertEquals("pending", JSONObject(archive.records.value.getValue(original.id)).getString("state"))
        assertFalse(repo.isActive())
        assertEquals(0, synchronizations)
    }

    @Test fun failedFinalArchiveWriteKeepsOriginalExportableWithoutReapplyingIt() = runBlocking {
        reject()
        val repo = repository()
        val reviewed = repo.review(original.id)
        archive.beforePut = { record ->
            check(JSONObject(record).getString("state") == "pending") { "Recovery status storage failed" }
        }
        assertTrue(runCatching { repo.apply(original.id, reviewed.remoteVersion, setOf("lifecycleAction")) }.isFailure)
        val replacement = db.outboxDao().getPending().single()
        assertNotEquals(original.id, replacement.id)
        assertEquals(OUTING_COMPLETION, replacement.operation)
        assertEquals("pending", JSONObject(archive.records.value.getValue(original.id)).getString("state"))
        val retained = repo.items().single { it.id == original.id }
        assertNotNull("Interrupted originals must remain exportable, without new mutation controls", retained.error)
        assertEquals(original.payloadJson, JSONObject(repo.exportOriginal(original.id)).getJSONObject("original").getString("payloadJson"))
        assertTrue(runCatching { repo.discard(original.id) }.isFailure)
        assertTrue(runCatching { repo.apply(original.id, reviewed.remoteVersion, setOf("lifecycleAction")) }.isFailure)
        assertEquals(replacement, db.outboxDao().getPending().single())
    }

    private class MemoryArchive : OutingRecoveryArchive {
        override val records = MutableStateFlow<Map<String, String>>(emptyMap())
        var beforePut: (String) -> Unit = {}
        var afterPut: () -> Unit = {}
        override suspend fun put(id: String, record: String) {
            beforePut(record); records.value = records.value + (id to record); afterPut()
        }
    }
}
