package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.TicketBarcode
import work.kumarfamilynet.cinemarchive.core.model.TicketBarcodeFormat

/** Ticket sending (#220): request shapes, parsing and owner fencing against a scripted HTTP layer. */
class TicketSharingRepositoryTest {
    private val owner = "10000000-0000-4000-8000-000000000001"
    private val friend = "20000000-0000-4000-8000-000000000001"
    private val grant = "30000000-0000-4000-8000-000000000001"
    private val outing = "40000000-0000-4000-8000-000000000001"
    private val requests = mutableListOf<Request>()
    private val bodies = mutableListOf<String>()
    private var current = true
    private var signedIn: SupabaseSession? = SupabaseSession("token-1", owner)

    private fun respond(vararg replies: Pair<Int, ByteArray>): TicketSharingRepository {
        val queue = replies.toMutableList()
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += request
            bodies += request.body?.let { Buffer().also { buffer -> it.writeTo(buffer) }.readUtf8() }.orEmpty()
            val (code, bytes) = queue.removeAt(0)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("scripted")
                .body(bytes.toResponseBody("application/json".toMediaType())).build()
        }).build()
        return TicketSharingRepository(SupabaseRestClient("https://project.example.test", "anon", http), { signedIn }, owner, { current }, { emptyList() })
    }

    private fun text(vararg replies: Pair<Int, String>) = respond(*replies.map { it.first to it.second.toByteArray() }.toTypedArray())

    private val grantJson = """[{"grant_id":"$grant","user_id":"$friend","name":"Sam Friend","sent_at":"2026-11-01T10:00:00+00:00","opened_at":null}]"""

    private val receivedJson = """[{"grant_id":"$grant","sender_id":"$friend","sender_name":"Fran","title":"Film","year":2026,"poster_url":null,
        "showtime":"2099-11-02T19:00:00+00:00","ends_at":"2099-11-02T21:00:00+00:00","venue":"AMC","sent_at":"2026-11-01T10:00:00+00:00","opened_at":null}]"""

    private val openedJson = """{"id":"x","objectKey":"$friend/aaa/original","mimeType":"image/png","byteLength":4,"sha256":"a",
        "barcode":{"payload":"CODE-123","format":"QR_CODE"},"title":"Film","showtime":"2099-11-02T19:00:00+00:00","venue":"AMC"}"""

    @Test fun sendsToDistinctFriendsAndReturnsWhoHasIt() = runBlocking {
        val repo = text(200 to grantJson)
        val grants = repo.send(outing, listOf(friend, friend))
        assertEquals("/rest/v1/rpc/send_ticket", requests.single().url.encodedPath)
        val sent = JSONObject(bodies.single())
        assertEquals(outing, sent.getString("p_outing_id"))
        assertEquals(1, sent.getJSONArray("p_recipient_ids").length())
        assertEquals(listOf("Sam Friend"), grants.map { it.name })
        assertNull(grants.single().openedAt)
    }

    @Test fun mapsAServerRefusalToAReadableMessage() = runBlocking {
        val repo = text(400 to """{"code":"22023","message":"This outing has no ticket to send"}""")
        try { repo.send(outing, listOf(friend)); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.isNotBlank()) }
    }

    @Test fun listsRevokesAndReadsReceivedTickets() = runBlocking {
        val repo = text(200 to grantJson, 204 to "", 200 to receivedJson)
        assertEquals(1, repo.grants(outing).size)
        assertEquals("/rest/v1/rpc/get_ticket_grants", requests[0].url.encodedPath)
        repo.revoke(grant)
        assertEquals(grant, JSONObject(bodies[1]).getString("p_grant_id"))
        val tickets = repo.received()
        assertEquals("/rest/v1/rpc/get_received_tickets", requests[2].url.encodedPath)
        assertEquals(listOf("Film"), tickets.map { it.title })
        assertEquals("Fran", tickets.single().senderName)
        assertNull(tickets.single().openedAt)
    }

    @Test fun opensATicketIntoItsFileAndDecodedBarcodeThenDownloadsAndChecksTheSize() = runBlocking {
        val repo = text(200 to openedJson, 200 to "1234", 200 to "123")
        val ticket = repo.open(grant)
        assertEquals("/rest/v1/rpc/open_received_ticket", requests[0].url.encodedPath)
        assertEquals(TicketBarcode("CODE-123", TicketBarcodeFormat.QR_CODE), ticket.barcode)
        assertEquals("AMC", ticket.venue)
        assertArrayEquals("1234".toByteArray(), repo.download(ticket))
        assertEquals("/storage/v1/object/authenticated/ticket-attachments/${friend}/aaa/original", requests[1].url.encodedPath)
        try { repo.download(ticket); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun parsingToleratesAMissingBarcodeAndAnUnknownFormatButNotABadFile() {
        assertNull(parseOpenedTicket(openedJson.replace("\"barcode\":{\"payload\":\"CODE-123\",\"format\":\"QR_CODE\"}", "\"barcode\":null"))!!.barcode)
        assertEquals(TicketBarcodeFormat.OTHER, parseOpenedTicket(openedJson.replace("QR_CODE", "MAXICODE"))!!.barcode!!.format)
        assertNull(parseOpenedTicket(openedJson.replace("image/png", "text/html")))
        assertNull(parseOpenedTicket(openedJson.replace("$friend/aaa/original", "../other-bucket/x")))
        assertNull(parseOpenedTicket(openedJson.replace("\"byteLength\":4", "\"byteLength\":0")))
    }

    @Test fun skipsMalformedRows() {
        assertTrue(parseTicketGrants("""[{"grant_id":"x"}]""").isEmpty())
        assertTrue(parseReceivedTickets("""[{"grant_id":"$grant"}]""").isEmpty())
    }

    @Test fun rejectsEmptySendsAndMalformedIdsAndAnotherAccountBeforeAnyRequest() = runBlocking {
        val repo = text()
        try { repo.send(outing, emptyList()); fail() } catch (_: IllegalArgumentException) {}
        try { repo.send("1&x", listOf(friend)); fail() } catch (_: IllegalArgumentException) {}
        try { repo.send(outing, listOf("nope")); fail() } catch (_: IllegalArgumentException) {}
        try { repo.revoke("nope"); fail() } catch (_: IllegalArgumentException) {}
        try { repo.open("nope"); fail() } catch (_: IllegalArgumentException) {}
        signedIn = SupabaseSession("token-2", "someone-else")
        try { repo.received(); fail() } catch (_: IllegalStateException) {}
        signedIn = null
        try { repo.received(); fail() } catch (_: IllegalStateException) {}
        signedIn = SupabaseSession("token-1", owner); current = false
        try { repo.received(); fail() } catch (_: IllegalStateException) {}
        assertTrue(requests.isEmpty())
    }
}
