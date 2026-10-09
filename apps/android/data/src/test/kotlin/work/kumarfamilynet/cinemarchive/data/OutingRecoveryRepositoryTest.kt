package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*

@RunWith(RobolectricTestRunner::class)
class OutingRecoveryRepositoryTest {
    private lateinit var db: LibraryDatabase
    private lateinit var outbox: MutationOutbox
    private var session: SupabaseSession? = SupabaseSession("token-a", "owner")
    private val archive = MemoryArchive()
    private val remote = FakeRemote()
    private val original = CinemaOutingEntity("outing", "title", "2099-10-08T19:00:00Z", 20, 90,
        "2099-10-08T20:50:00Z", "Saved cinema", listOf("Saved friend"), "IMAX", 12.5,
        notes = "Saved note", createdAt = "2026-10-08T12:00:00Z", updatedAt = "2026-10-08T12:00:00Z")
    private lateinit var entry: OutboxEntity
    private fun repository() = OutingRecoveryRepository("owner", { session }, db.outboxDao(), db.cinemaOutingDao(),
        db.titleDao(), outbox, archive, remote)
    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity) = PushResult.Retry("review")
        }, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db))
        db.titleDao().upsertAll(listOf(TitleEntity("title", 42, "MOVIE", "Film", 2026, null, emptyList(), null, null, null, 90, null,
            "WATCHLIST", null, null, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")))
        db.cinemaOutingDao().upsert(original)
        entry = OutboxEntity("legacy", "cinema_outing", "outing", "upsert", original.mutationPayload().toString(), 1)
        db.outboxDao().enqueue(entry)
        remote.row = outingWireBody(original.mutationPayload(), "owner", true)
            .put("venue", "Current cinema").put("notes", "Current note").put("updated_at", "2026-10-08T13:00:00Z")
    }
    @After fun tearDown() { db.close() }

    @Test fun reviewRetainsExactOriginalAndShowsCurrentVersusSavedWithoutMutatingEither() = runBlocking {
        val repo = repository()
        val review = repo.review("legacy")
        assertEquals("Saved cinema", review.fields.single { it.key == "venue" }.saved)
        assertEquals("Current cinema", review.fields.single { it.key == "venue" }.current)
        assertFalse(review.pendingAttempt)
        assertEquals(entry.payloadJson, JSONObject(repo.exportOriginal("legacy")).getJSONObject("original").getString("payloadJson"))
        assertEquals("upsert", db.outboxDao().getPending().single().operation)
        assertEquals("Saved cinema", db.cinemaOutingDao().getById("outing")!!.venue)
        assertTrue(remote.attempts.isEmpty())
    }

    @Test fun chosenFieldCasPreservesUnselectedServerValuesAndArchivesOriginalAfterResolution() = runBlocking {
        val repo = repository()
        val review = repo.review("legacy")
        assertEquals(OutingRecoveryOutcome.APPLIED, repo.apply("legacy", review.remoteVersion, setOf("venue")))
        assertEquals(setOf("venue"), remote.attempts.single().getJSONObject("patch").keys().asSequence().toSet())
        assertEquals("Saved cinema", remote.row!!.getString("venue"))
        assertEquals("Current note", remote.row!!.getString("notes"))
        assertEquals("Current note", db.cinemaOutingDao().getById("outing")!!.notes)
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertTrue(repo.items().single().resolved)
        assertEquals(entry.payloadJson, JSONObject(repo.exportOriginal("legacy")).getJSONObject("original").getString("payloadJson"))
    }

    @Test fun unknownOutcomeSurvivesRepositoryRestartAndReusesExactOperationWhileBlockingDiscard() = runBlocking {
        val repo = repository()
        val review = repo.review("legacy")
        remote.failAfterApply = true
        try { repo.apply("legacy", review.remoteVersion, setOf("venue")); fail("transport lost") } catch (_: IOException) {}
        assertEquals("review", db.outboxDao().getPending().single().operation)
        val restarted = repository()
        assertTrue(restarted.pendingAttempt("legacy"))
        try { restarted.discard("legacy"); fail("unconfirmed attempt") } catch (_: IllegalStateException) {}
        remote.failAfterApply = false
        assertEquals(OutingRecoveryOutcome.APPLIED, restarted.apply("legacy", "different", setOf("notes")))
        assertEquals(2, remote.attempts.size)
        assertEquals(remote.attempts[0].toString(), remote.attempts[1].toString())
        assertEquals(1, remote.mutations)
    }

    @Test fun staleVersionRefreshesComparisonWithoutChangingCurrentPlan() = runBlocking {
        val repo = repository()
        val review = repo.review("legacy")
        remote.row!!.put("updated_at", "2026-10-08T14:00:00Z").put("venue", "Another device")
        assertEquals(OutingRecoveryOutcome.CHANGED, repo.apply("legacy", review.remoteVersion, setOf("venue")))
        assertFalse(repo.pendingAttempt("legacy"))
        assertEquals("Another device", repo.review("legacy").fields.single { it.key == "venue" }.current)
        assertEquals(0, remote.mutations)
        assertEquals(1, db.outboxDao().getPending().size)
    }

    @Test fun missingRemoteOffersExportAndDiscardWithoutCreatingOrDeletingViewingHistory() = runBlocking {
        db.viewingDao().upsert(ViewingEntity("viewing", "title", "2026-10-08", 4.5, "Keep history", "Cinema", outingId = "outing"))
        remote.row = null
        val repo = repository()
        val review = repo.review("legacy")
        assertFalse(review.remoteExists)
        assertTrue(repo.exportOriginal("legacy").contains("Saved cinema"))
        repo.discard("legacy")
        assertNull(db.cinemaOutingDao().getById("outing"))
        assertEquals("Keep history", db.viewingDao().getById("viewing")!!.notes)
        assertTrue(remote.attempts.isEmpty())
    }

    @Test fun laterQueuedEditsAndDependentViewingCommandsSurviveApplyInOriginalFifoOrder() = runBlocking {
        val later = OutboxEntity("later", "cinema_outing", "outing", "update",
            JSONObject().put("id", "outing").put("notes", "New local note").put("updatedAt", "2026-10-08T15:00:00Z").toString(), 2)
        val dependent = OutboxEntity("view", "viewing", "viewing", "update", """{"id":"viewing","rating":4.5}""", 3)
        db.outboxDao().enqueue(later); db.outboxDao().enqueue(dependent)
        val repo = repository()
        val review = repo.review("legacy")
        remote.beforeReply = {
            assertEquals(listOf("legacy", "later", "view"), db.outboxDao().getPending().map { it.id })
            assertEquals("review", db.outboxDao().getPending().first().operation)
        }
        repo.apply("legacy", review.remoteVersion, setOf("venue"))
        assertEquals(listOf("later", "view"), db.outboxDao().getPending().map { it.id })
        assertEquals("New local note", db.cinemaOutingDao().getById("outing")!!.notes)
        assertEquals(later.payloadJson, db.outboxDao().getPending().first().payloadJson)
    }

    @Test fun replayedReceiptCannotResurrectPlanDeletedAfterFirstAttempt() = runBlocking {
        val repo = repository()
        val review = repo.review("legacy")
        remote.failAfterApply = true
        try { repo.apply("legacy", review.remoteVersion, setOf("venue")) } catch (_: IOException) {}
        remote.row = null
        remote.failAfterApply = false
        repo.apply("legacy", review.remoteVersion, emptySet())
        assertNull(db.cinemaOutingDao().getById("outing"))
        assertEquals(1, remote.mutations)
    }

    @Test fun malformedLaterIntentKeepsItsProjectionAndCannotHideOtherRecoveryCards() = runBlocking {
        db.outboxDao().enqueue(entry.copy(id = "later-broken", payloadJson = "{broken", createdAt = 2))
        val repo = repository()
        val review = repo.review("legacy")
        repo.apply("legacy", review.remoteVersion, setOf("venue"))
        assertEquals(listOf("later-broken"), db.outboxDao().getPending().map { it.id })
        assertEquals("Saved note", db.cinemaOutingDao().getById("outing")!!.notes)
        assertTrue(repo.items().any { it.id == "later-broken" && !it.resolved })
        assertEquals("{broken", JSONObject(repo.exportOriginal("later-broken")).getJSONObject("original").getString("payloadJson"))
    }

    @Test fun accountSwitchDuringFetchHidesResultsAndCannotIssueAnApply() = runBlocking {
        remote.beforeFetchReturn = { session = SupabaseSession("token-b", "other") }
        val repo = repository()
        try { repo.review("legacy"); fail("old account") } catch (_: IllegalStateException) {}
        assertFalse(repo.isActive())
        try { repo.apply("legacy", "2026-10-08T13:00:00Z", setOf("venue")); fail("old account write") } catch (_: IllegalStateException) {}
        assertTrue(remote.attempts.isEmpty())
        assertEquals("upsert", db.outboxDao().getPending().single().operation)
    }

    @Test fun privateLifecycleFieldsCannotBeSelectedAndMalformedIntentStillExports() = runBlocking {
        val repo = repository()
        val review = repo.review("legacy")
        assertTrue(review.fields.filter { it.key in setOf("status", "ticket_image_path", "completed_viewing_id") }.all { !it.selectable })
        try { repo.apply("legacy", review.remoteVersion, setOf("status")); fail("forbidden") } catch (_: IllegalArgumentException) {}
        db.outboxDao().enqueue(entry.copy(id = "malformed", payloadJson = "{broken"))
        assertTrue(repo.items().any { it.id == "malformed" })
        assertEquals("{broken", JSONObject(repo.exportOriginal("malformed")).getJSONObject("original").getString("payloadJson"))
        assertTrue(repo.review("malformed").fields.isEmpty())
    }

    @Test fun mismatchedReceiptCannotAcknowledgeOrClearDurableAttempt() = runBlocking {
        val repo = repository()
        val review = repo.review("legacy")
        val corruptions: List<(JSONObject) -> Unit> = listOf(
            { it.put("operationId", "wrong") },
            { it.getJSONObject("request").put("kind", "outing.other") },
            { it.getJSONObject("request").put("outingId", "other") },
            { it.getJSONObject("request").put("expectedUpdatedAt", "2026-10-08T14:00:00Z") },
            { it.getJSONObject("request").getJSONObject("patch").put("venue", "Other intent") },
            { it.getJSONObject("request").getJSONObject("patch").put("notes", "Unexpected field") },
            { it.remove("request") },
            { it.put("status", "conflict").put("operationId", "wrong") },
        )
        corruptions.forEach { corrupt ->
            remote.transformResponse = { response -> response.also(corrupt) }
            try { repo.apply("legacy", review.remoteVersion, setOf("venue")); fail("receipt mismatch") }
            catch (_: IllegalArgumentException) {} catch (_: org.json.JSONException) {}
            assertTrue(repo.pendingAttempt("legacy"))
            assertEquals(listOf("legacy"), db.outboxDao().getPending().map { it.id })
            assertEquals("Saved note", db.cinemaOutingDao().getById("outing")!!.notes)
            assertEquals(entry.payloadJson, JSONObject(repo.exportOriginal("legacy")).getJSONObject("original").getString("payloadJson"))
        }
        remote.transformResponse = { it }
        assertEquals(OutingRecoveryOutcome.APPLIED, repository().apply("legacy", null, emptySet()))
        assertEquals(1, remote.mutations)
        assertEquals(1, remote.attempts.map { it.getString("operationId") }.distinct().size)
    }

    @Test fun equivalentTimestampAndJsonNumbersAcknowledgeButNestedArrayChangesDoNot() = runBlocking {
        val repo = repository()
        val review = repo.review("legacy")
        remote.transformResponse = { response -> response.apply {
            getJSONObject("request").getJSONObject("patch").getJSONArray("companions").getJSONObject(0).put("name", "Other friend")
        } }
        try { repo.apply("legacy", review.remoteVersion, setOf("companions", "previews_minutes")); fail("changed companion") }
        catch (_: IllegalArgumentException) {}
        assertTrue(repo.pendingAttempt("legacy"))
        remote.transformResponse = { response -> response.apply {
            getJSONObject("request").put("expectedUpdatedAt", "2026-10-08T07:00:00-06:00")
                .getJSONObject("patch").put("previews_minutes", 20.0)
        } }
        assertEquals(OutingRecoveryOutcome.APPLIED, repo.apply("legacy", null, emptySet()))
    }

    @Test fun unreadableArchiveEntriesRemainRawExportableWithoutHidingOrResolvingOtherEntries() = runBlocking {
        val damaged = mapOf("bad-json" to "{broken\n", "empty" to "", "bad-shape" to "{\"version\":1,\"state\":\"pending\"}")
        damaged.forEach { (id, raw) -> archive.put(id, raw) }
        val repo = repository()
        val cards = repo.items()
        assertEquals(4, cards.size)
        assertNull(cards.single { it.id == "legacy" }.error)
        damaged.forEach { (id, raw) ->
            assertNotNull(cards.single { it.id == id }.error)
            assertEquals(raw, repo.exportOriginal(id))
            try { repo.discard(id); fail("unknown original must remain") } catch (_: Exception) {}
            try { repo.apply(id, "2026-10-08T13:00:00Z", setOf("venue")); fail("unknown intent must remain") } catch (_: Exception) {}
            assertEquals(raw, archive.records.value[id])
        }
        assertTrue(remote.attempts.isEmpty())
        val review = repo.review("legacy")
        assertEquals(OutingRecoveryOutcome.APPLIED, repo.apply("legacy", review.remoteVersion, setOf("venue")))
        assertEquals(3, repo.items().count { it.error != null })
    }

    private class MemoryArchive : OutingRecoveryArchive {
        override val records = MutableStateFlow<Map<String, String>>(emptyMap())
        override suspend fun put(id: String, record: String) { records.value = records.value + (id to record) }
    }
    private class FakeRemote : OutingRecoveryRemote {
        var row: JSONObject? = null
        var failAfterApply = false
        var mutations = 0
        var beforeReply: suspend () -> Unit = {}
        var beforeFetchReturn: suspend () -> Unit = {}
        var transformResponse: (JSONObject) -> JSONObject = { it }
        val attempts = mutableListOf<JSONObject>()
        private val receipts = mutableMapOf<String, String>()
        override suspend fun fetch(session: SupabaseSession, outingId: String): JSONObject? {
            val result = row?.let { JSONObject(it.toString()) }
            beforeFetchReturn()
            return result
        }
        override suspend fun apply(session: SupabaseSession, outingId: String, attempt: JSONObject): JSONObject {
            attempts += JSONObject(attempt.toString())
            beforeReply()
            val operation = attempt.getString("operationId")
            val result = receipts[operation]?.let(::JSONObject) ?: run {
                val current = row
                when {
                    current == null -> JSONObject().put("status", "missing").put("outing", JSONObject.NULL)
                    current.getString("updated_at") != attempt.getString("expectedVersion") ->
                        JSONObject().put("status", "conflict").put("outing", current)
                    else -> {
                        val patch = attempt.getJSONObject("patch")
                        patch.keys().forEach { current.put(it, patch.get(it)) }
                        current.put("updated_at", "2026-10-08T16:00:00Z")
                        mutations++
                        JSONObject().put("status", "applied").put("outing", current)
                    }
                }
            }
            if (!result.has("request")) result.put("operationId", operation).put("request", JSONObject()
                .put("kind", "outing.resolve").put("outingId", outingId)
                .put("expectedUpdatedAt", attempt.getString("expectedVersion"))
                .put("patch", JSONObject(attempt.getJSONObject("patch").toString())))
            if (result.getString("status") == "applied") receipts[operation] = result.toString()
            if (failAfterApply) throw IOException("Lost response")
            return transformResponse(JSONObject(result.toString()))
        }
    }
}

