package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.database.RoomTransactor

@RunWith(RobolectricTestRunner::class)
class ViewingReviewDispatchTest {
    private var requests = 0
    private var sessions = 0
    private val client = SupabaseRestClient("https://example.supabase.co", "anon", OkHttpClient.Builder().addInterceptor { chain ->
        requests++
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body("[]".toResponseBody()).build()
    }.build())
    private val writer = SupabaseRemoteMutationWriter(client) {
        sessions++
        SupabaseSession("owner-token", ViewingCommandFixture.owner)
    }
    private fun retained(operation: String, command: Boolean = false): OutboxEntity {
        val payload = if (command) JSONObject(ViewingCommandFixture.entry().payloadJson)
            else JSONObject().put("id", ViewingCommandFixture.viewing).put("titleId", ViewingCommandFixture.title)
                .put("date", "2026-10-01").put("notes", "Preserved, not authorized for upload")
        return OutboxEntity(ViewingCommandFixture.operation, "viewing", ViewingCommandFixture.viewing,
            operation, payload.toString(), 1)
    }

    @Test fun reviewOfLegacyOrGuardedViewingNeverCallsNetworkOrSession() = runBlocking {
        for (command in listOf(false, true)) {
            val entry = retained("review", command)
            val result = writer.push(entry)
            assertTrue(result is PushResult.Retry)
            assertTrue((result as PushResult.Retry).reason.contains("Saved viewing changes"))
        }
        assertEquals(0, requests)
        assertEquals(0, sessions)
    }

    @Test fun awaitingCanonicalIdentityNeverFallsThroughToLegacyUpsert() = runBlocking {
        val entry = retained(AWAITING_COMPLETION)
        assertTrue(writer.push(entry) is PushResult.Retry)
        assertEquals(0, requests)
        assertEquals(0, sessions)
    }

    @Test fun repeatedFlushRetainsExactReviewedBytesAndStopsLaterDelivery() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val original = retained("review", command = true)
            val later = retained("upsert").copy(id = ViewingCommandFixture.nextOperation)
            db.outboxDao().enqueue(original); db.outboxDao().enqueue(later)
            val outbox = MutationOutbox(db.outboxDao(), writer, TitleConflictHandler(db.titleDao()), RoomTransactor(db))
            repeat(2) { outbox.flush() }
            val queue = db.outboxDao().getPending()
            assertEquals(listOf(original.id, later.id), queue.map { it.id })
            assertEquals(original.payloadJson, queue.first().payloadJson)
            assertEquals("review", queue.first().operation)
            assertEquals(2, queue.first().attemptCount)
            assertEquals(later, queue.last())
            assertEquals(0, requests)
            assertEquals(0, sessions)
        } finally { db.close() }
    }
}
