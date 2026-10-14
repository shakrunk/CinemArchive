package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*

@RunWith(RobolectricTestRunner::class)
class MoviegoingPreferencesTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val title = "22222222-2222-4222-8222-222222222222"
    private val noteId = "33333333-3333-4333-8333-333333333333"
    private val stamp = "2026-10-08T01:00:00.123456Z"
    private val venue = "O'Brien + Cinema"
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
    private var active = true
    private val replies = ArrayDeque<Pair<Int, String>>()
    private val requests = mutableListOf<okhttp3.Request>()
    private var afterRequest: () -> Unit = {}
    private var afterArchive: () -> Unit = {}
    private val originals = MutableStateFlow<Map<String, String>>(emptyMap())
    private val archive = object : OutingRecoveryArchive {
        override val records = originals
        override suspend fun put(id: String, record: String) { originals.value += id to record; afterArchive() }
    }
    private val client = SupabaseRestClient("https://x.supabase.co", "anon", OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request(); val response = replies.removeFirst(); afterRequest()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(response.first).message("fixture")
            .body(response.second.toResponseBody("application/json".toMediaType())).build()
    }.build())
    private val session = { if (active) SupabaseSession("token", owner) else null }
    private lateinit var repo: MoviegoingPreferencesRepository
    private val box = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
        override suspend fun push(entry: OutboxEntity) = repo.push(entry)
    }, TitleConflictHandler(db.titleDao()), RoomTransactor(db), AppliedMutationHandler { e, r -> repo.apply(e, r) },
        pendingProjectionKeys = { repo.protectionKeys(it) })
    @Before fun setup() = runBlocking {
        repo = MoviegoingPreferencesRepository(db, box, owner, { active }, client, session, archive, box::flush, { it() })
        db.titleDao().upsertAll(listOf(TitleMetadataFixture.entity().copy(id = title)))
    }
    @After fun close() { db.close() }
    private fun row(notes: String = "Remote", revision: String = stamp) = JSONObject().put("id", noteId).put("user_id", owner)
        .put("venue", venue).put("notes", notes).put("created_at", stamp).put("updated_at", revision)
    private suspend fun managed(notes: String = "Original") { db.venueNoteDao().upsert(row(notes).toVenueNote()) }
    private fun receipt(entry: OutboxEntity, canonical: JSONObject = row(JSONObject(entry.payloadJson).optString("notes"))): JSONObject {
        val operation = preferenceOperation(entry, owner)
        val result = JSONObject().put("table", operation.getString("table")).put("key", operation.getJSONObject("key"))
        if (operation.getString("action") == "delete") result.put("deleted", true) else result.put("row", canonical)
        return JSONObject().put("operationId", entry.id).put("rows", JSONArray().put(result))
    }
    private fun accepted(entry: OutboxEntity, current: JSONObject? = row(), canonical: JSONObject = row(JSONObject(entry.payloadJson).optString("notes"))) {
        replies += 200 to receipt(entry, canonical).toString()
        replies += 200 to JSONArray().apply { current?.let(::put) }.toString()
    }
    private fun requestBody(index: Int) = Buffer().also { requests[index].body!!.writeTo(it) }.readUtf8()

    @Test fun openingRetryKeepsExactBytesAndIdAfterQueueDrainAndDoesNotAdoptNewRevision() = runBlocking {
        managed(); val opening = repo.captureVenue(" $venue ")
        repo.saveVenue(opening, "Saved"); val original = db.outboxDao().getPending().single()
        db.venueNoteDao().upsert(row("Newer", "2026-10-09T00:00:00Z").toVenueNote())
        repo.saveVenue(opening, "Saved"); assertEquals(original, db.outboxDao().getPending().single())
        db.outboxDao().remove(original.id) // Lost local result after confirmed delivery.
        repo.saveVenue(opening, "Saved")
        val restored = db.outboxDao().getPending().single()
        assertEquals(original.id, restored.id); assertEquals(original.payloadJson, restored.payloadJson)
        assertEquals(stamp, preferenceOperation(restored, owner).getString("expectedUpdatedAt"))
    }

    @Test fun emptyNoteAndDeleteAreDistinctAndLaterEditsUseImmutablePriorOperation() = runBlocking {
        repo.saveVenue(repo.captureVenue(venue), "")
        val insert = db.outboxDao().getPending().single()
        repo.saveVenue(repo.captureVenue(venue), "Parking")
        val update = db.outboxDao().getPending().last()
        repo.saveVenue(repo.captureVenue(venue), null)
        val delete = db.outboxDao().getPending().last()
        assertEquals("insert", preferenceOperation(insert, owner).getString("action"))
        assertEquals("", JSONObject(insert.payloadJson).getString("notes"))
        assertEquals(insert.id, preferenceOperation(update, owner).getString("expectedOperationId"))
        assertEquals(update.id, preferenceOperation(delete, owner).getString("expectedOperationId"))
        assertNull(db.venueNoteDao().get(venue)); assertTrue("venue_note_name:$venue" in box.pendingEntityKeys())
    }

    @Test fun legacyRowsAreProtectedUntilExplicitAdmissionAndWhitespaceIsAsciiOnly() = runBlocking {
        db.venueNoteDao().upsert(VenueNoteEntity(venue, "Device memory", "2099-01-01"))
        db.theaterInterestDao().upsert(TheaterInterestEntity(title, stamp))
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertTrue(box.pendingEntityKeys().containsAll(setOf("venue_note_name:$venue", "theater_interest:$title")))
        val opening = repo.captureVenue(venue)
        assertTrue(runCatching { repo.saveVenue(opening, null) }.isFailure)
        repo.saveVenue(opening, "Device memory")
        assertFalse(preferenceOperation(db.outboxDao().getPending().single(), owner).has("expectedUpdatedAt"))
        assertEquals("\tCinema\t", normalizedVenue(" \tCinema\t "))
        assertTrue(runCatching { repo.saveVenue(opening, "bad\u0000") }.isFailure)
    }

    @Test fun failedLocalEnqueueRollsBackTheOptimisticChangeAndOwnerChangeCannotWrite() = runBlocking {
        managed()
        val broken = object : OutboxDao by db.outboxDao() { override suspend fun enqueue(entry: OutboxEntity) { error("Disk full") } }
        val failing = MutationOutbox(broken, object : RemoteMutationWriter { override suspend fun push(entry: OutboxEntity) = PushResult.Success }, TitleConflictHandler(db.titleDao()), RoomTransactor(db))
        val other = MoviegoingPreferencesRepository(db, failing, owner, { active }, client, session, archive, {}, { it() })
        assertTrue(runCatching { other.saveVenue(other.captureVenue(venue), "Lost") }.isFailure)
        assertEquals("Original", db.venueNoteDao().get(venue)!!.notes)
        active = false; assertTrue(runCatching { repo.setInterest(title, true) }.isFailure)
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun unknownOutcomeRetriesOriginalAndAcceptedReadConflictIsStillUnknown() = runBlocking {
        managed(); repo.saveVenue(repo.captureVenue(venue), "Saved")
        val entry = db.outboxDao().getPending().single()
        replies += 503 to "{}"; box.flush()
        replies += 200 to receipt(entry).toString(); replies += 409 to """{"code":"40001","message":"Read failed"}"""; box.flush()
        assertEquals(MOVIEGOING_COMMAND, db.outboxDao().getPending().single().operation)
        accepted(entry, row("Newer", "2026-10-09T00:00:00Z")); box.flush()
        assertEquals(requestBody(0), requestBody(1)); assertEquals(requestBody(0), requestBody(3))
        assertTrue(db.outboxDao().getPending().isEmpty()); assertEquals("Newer", db.venueNoteDao().get(venue)!!.notes)
        assertEquals("eq.$venue", requests.last().url.queryParameter("venue"))
        assertEquals("eq.$owner", requests.last().url.queryParameter("user_id"))
    }

    @Test fun acceptedOldReceiptCannotResurrectDeletedNoteOrOverwriteLaterLocalNote() = runBlocking {
        managed(); repo.saveVenue(repo.captureVenue(venue), "First")
        val first = db.outboxDao().getPending().single()
        repo.saveVenue(repo.captureVenue(venue), "Later")
        val later = db.outboxDao().getPending().last()
        box.atomically { repo.apply(first, JSONObject().put("receipt", receipt(first)).put("current", row("Other", "2026-10-10T00:00:00Z"))) }
        assertEquals("Later", db.venueNoteDao().get(venue)!!.notes)
        assertEquals("2026-10-10T00:00:00Z", db.venueNoteDao().get(venue)!!.serverUpdatedAt)
        assertEquals(later, db.outboxDao().getPending().last())
        db.outboxDao().remove(later.id)
        box.atomically { repo.apply(first, JSONObject().put("receipt", receipt(first)).put("current", JSONObject.NULL)) }
        assertNull(db.venueNoteDao().get(venue))
        assertTrue(runCatching { box.atomically { repo.apply(first.copy(payloadJson = "{}"), JSONObject()) } }.isFailure)
    }

    @Test fun definitiveConflictCanCompareAndReapplyFreshGuardWhileUnknownCannotDiscard() = runBlocking {
        managed(); repo.saveVenue(repo.captureVenue(venue), "Saved")
        val original = db.outboxDao().getPending().single()
        replies += 503 to "{}"; box.flush(); replies += 200 to JSONArray().put(row()).toString()
        val unknown = repo.compare(original.id)
        assertFalse(unknown.canResolve); assertTrue(runCatching { repo.resolve(unknown, false) }.isFailure)
        replies += 409 to """{"code":"40001","message":"Newer note"}"""; box.flush()
        val fresh = row("Current", "2026-10-10T00:00:00Z")
        replies += 200 to JSONArray().put(fresh).toString(); val comparison = repo.compare(original.id)
        assertTrue(comparison.canResolve)
        replies += 200 to JSONArray().put(fresh).toString(); repo.resolve(comparison, true)
        val next = db.outboxDao().getPending().single()
        assertNotEquals(original.id, next.id)
        assertEquals("2026-10-10T00:00:00Z", preferenceOperation(next, owner).getString("expectedUpdatedAt"))
        assertEquals("Saved", db.venueNoteDao().get(venue)!!.notes)
        assertEquals(original.payloadJson, JSONObject(originals.value.getValue(original.id)).getJSONObject("original").getString("payloadJson"))
        assertEquals("reapplied", JSONObject(originals.value.getValue(original.id)).getString("state"))
    }

    @Test fun failedRecoveryKeepsOriginalQueueAndPreparedArchiveRatherThanFalseDiscard() = runBlocking {
        managed(); repo.saveVenue(repo.captureVenue(venue), "Saved")
        val original = db.outboxDao().getPending().single()
        replies += 409 to """{"code":"40001","message":"Conflict"}"""; box.flush()
        replies += 200 to JSONArray().put(row()).toString(); val comparison = repo.compare(original.id)
        replies += 200 to JSONArray().put(row()).toString(); afterArchive = { active = false }
        assertTrue(runCatching { repo.resolve(comparison, false) }.isFailure)
        assertEquals(original.id, db.outboxDao().getPending().single().id)
        assertEquals("Saved", db.venueNoteDao().get(venue)!!.notes)
        assertEquals("prepared", JSONObject(originals.value.getValue(original.id)).getString("state"))
    }

    @Test fun interestDesiredPresenceNeverSchedulesOrChangesTitleAndCanBeRemoved() = runBlocking {
        val before = db.titleDao().getById(title)
        repo.setInterest(title, true); repo.setInterest(title, false)
        val queue = db.outboxDao().getPending()
        assertEquals(listOf("insert", "delete"), queue.map { preferenceOperation(it, owner).getString("action") })
        assertEquals(before, db.titleDao().getById(title)); assertTrue(db.cinemaOutingDao().observeAllOutings().first().isEmpty())
        assertTrue(db.theaterInterestDao().observeAll().first().isEmpty())
        assertTrue("theater_interest:$title" in box.pendingEntityKeys())
    }

    @Test fun wrongOwnerReceiptAndAccountChangeAfterReadNeverAcknowledge() = runBlocking {
        repo.saveVenue(repo.captureVenue(venue), "Saved"); val entry = db.outboxDao().getPending().single()
        replies += 200 to receipt(entry, row("Saved").put("user_id", title)).toString(); box.flush()
        assertEquals(entry.id, db.outboxDao().getPending().single().id)
        accepted(entry); afterRequest = { if (requests.last().method == "GET") active = false }; box.flush()
        assertEquals(entry.id, db.outboxDao().getPending().single().id)
        assertEquals("Saved", db.venueNoteDao().get(venue)!!.notes)
    }
}
