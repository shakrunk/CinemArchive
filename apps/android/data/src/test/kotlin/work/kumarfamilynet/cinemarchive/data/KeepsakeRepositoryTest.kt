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
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import work.kumarfamilynet.cinemarchive.core.model.KeepsakeSaveException
import work.kumarfamilynet.cinemarchive.core.model.OutingKeepsake
import org.junit.Test

/** Keepsake photos (#208): request shapes and owner fencing, against a scripted HTTP layer. */
class KeepsakeRepositoryTest {
    private val owner = "10000000-0000-4000-8000-000000000001"
    private val outing = "30000000-0000-4000-8000-000000000001"
    private val photoId = "40000000-0000-4000-8000-000000000001"
    private val requests = mutableListOf<Request>()
    private val bodies = mutableListOf<ByteArray>()
    private var current = true
    private var signedIn: SupabaseSession? = SupabaseSession("token-1", owner)
    private var journal: KeepsakeJournal = InMemoryKeepsakeJournal()

    private fun respond(vararg replies: Pair<Int, ByteArray>): KeepsakeRepository {
        val queue = replies.toMutableList()
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += request
            bodies += request.body?.let { body -> Buffer().also { body.writeTo(it) }.readByteArray() } ?: ByteArray(0)
            val (code, bytes) = queue.removeAt(0)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("scripted")
                .body(bytes.toResponseBody("application/json".toMediaType())).build()
        }).build()
        return KeepsakeRepository(SupabaseRestClient("https://project.example.test", "anon", http), { signedIn }, owner, { current }, journal)
    }

    private fun row(id: String = photoId, bytes: Int = 4) =
        """{"id":"$id","user_id":"$owner","outing_id":"$outing","object_key":"$owner/$id","mime_type":"image/jpeg","byte_length":$bytes,"created_at":"2026-10-30T10:00:00Z"}"""

    @Test fun listsAnOutingsPhotosOldestFirst() = runBlocking {
        val repo = respond(200 to "[${row()}]".toByteArray())
        val photos = repo.list(outing)
        assertEquals(listOf(photoId), photos.map { it.id })
        assertEquals("/rest/v1/outing_keepsakes", requests.single().url.encodedPath)
        assertEquals("outing_id=eq.$outing&select=*&order=created_at.asc", requests.single().url.query)
        assertEquals("Bearer token-1", requests.single().header("Authorization"))
    }

    @Test fun uploadsTheBytesUnderTheOwnersFolderThenRecordsTheRow() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val repo = respond(200 to "{}".toByteArray(), 201 to "[${row()}]".toByteArray())
        val saved = repo.add(outing, bytes, "image/jpeg")
        val upload = requests[0]
        assertEquals("POST", upload.method)
        assertTrue(upload.url.encodedPath.startsWith("/storage/v1/object/outing-keepsakes/$owner/"))
        assertEquals("false", upload.header("x-upsert"))
        assertEquals("image/jpeg", upload.body!!.contentType().toString())
        assertArrayEquals(bytes, bodies[0])
        val key = upload.url.encodedPath.substringAfter("outing-keepsakes/")
        val inserted = JSONArray(String(bodies[1])).getJSONObject(0)
        assertEquals(key, inserted.getString("object_key"))
        assertEquals("$owner/${inserted.getString("id")}", key)
        assertEquals(owner, inserted.getString("user_id"))
        assertEquals(outing, inserted.getString("outing_id"))
        assertEquals(4, inserted.getInt("byte_length"))
        assertEquals(photoId, saved.id)
    }

    @Test fun aFailedUploadNeverInsertsARow() = runBlocking {
        val repo = respond(500 to "{}".toByteArray())
        try { repo.add(outing, byteArrayOf(1), "image/jpeg"); fail() } catch (_: SupabaseHttpException) {}
        assertEquals(1, requests.size)
    }

    @Test fun refusesUnsupportedOrOversizedPhotosBeforeAnyRequest() = runBlocking {
        val repo = respond()
        for ((mime, size) in listOf("text/html" to 4, "image/jpeg" to 0, "image/jpeg" to 10 * 1024 * 1024 + 1)) {
            try { repo.add(outing, ByteArray(size), mime); fail() } catch (_: IllegalArgumentException) {}
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun removesOnlyTheOwnersRow() = runBlocking {
        val repo = respond(204 to ByteArray(0))
        repo.remove(photoId)
        assertEquals("DELETE", requests.single().method)
        assertEquals("id=eq.$photoId&user_id=eq.$owner", requests.single().url.query)
    }

    @Test fun downloadsFromThePrivateBucketAndChecksTheSize() = runBlocking {
        val repo = respond(200 to byteArrayOf(9, 9, 9, 9), 200 to byteArrayOf(9, 9))
        val keepsake = parseKeepsakes("[${row()}]").single()
        assertArrayEquals(byteArrayOf(9, 9, 9, 9), repo.download(keepsake))
        assertEquals("/storage/v1/object/authenticated/outing-keepsakes/$owner/$photoId", requests.single().url.encodedPath)
        try { repo.download(keepsake); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun refusesAnotherAccountsSessionOrAnEndedSignInBeforeAnyRequest() = runBlocking {
        val repo = respond()
        signedIn = SupabaseSession("token-2", "someone-else")
        try { repo.list(outing); fail() } catch (_: IllegalStateException) {}
        signedIn = null
        try { repo.list(outing); fail() } catch (_: IllegalStateException) {}
        signedIn = SupabaseSession("token-1", owner); current = false
        try { repo.list(outing); fail() } catch (_: IllegalStateException) {}
        assertTrue(requests.isEmpty())
    }

    @Test fun rejectsMalformedIdsBeforeTheyReachAFilter() = runBlocking {
        val repo = respond()
        try { repo.list("1&user_id=neq.x"); fail() } catch (_: IllegalArgumentException) {}
        try { repo.remove("not-a-uuid"); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(requests.isEmpty())
    }

    @Test fun parsingSkipsRowsWithAnUnexpectedShape() {
        val body = "[${row()},{\"id\":\"x\"},{\"id\":\"$photoId\",\"outing_id\":\"$outing\",\"mime_type\":\"text/html\",\"byte_length\":4}]"
        assertEquals(listOf(photoId), parseKeepsakes(body).map { it.id })
    }

    // ─── Interrupted uploads and replace (#394) ──────────────────────────────

    private val old = OutingKeepsake(photoId, outing, "image/jpeg", 4, "2026-10-30T10:00:00Z")
    private val newId = "40000000-0000-4000-8000-0000000000aa"
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private fun methods() = requests.map { it.method }
    private fun postgresError(code: String) = """{"code":"$code","message":"refused"}""".toByteArray()

    @Test fun aFailedInsertKeepsTheUploadedPhotoPendingAndFinishRetriesOnlyTheInsert() = runBlocking {
        val first = respond(200 to "{}".toByteArray(), 503 to "{}".toByteArray())
        val failure = try { first.add(outing, bytes, "image/jpeg"); null } catch (e: KeepsakeSaveException) { e }
        val pending = failure!!.pending!!
        assertEquals(listOf(pending), first.pending(outing))
        assertEquals(emptyList<Any>(), first.pending("30000000-0000-4000-8000-0000000000bb"))

        requests.clear(); bodies.clear()
        val second = respond(201 to "[${row(pending.id)}]".toByteArray())
        assertEquals(pending.id, second.finish(pending).id)
        assertEquals(listOf("POST"), methods()) // the retry never uploads again
        assertEquals("/rest/v1/outing_keepsakes", requests.single().url.encodedPath)
        assertEquals(pending.id, JSONArray(String(bodies.single())).getJSONObject(0).getString("id"))
        assertEquals(emptyList<Any>(), second.pending(outing))
    }

    @Test fun aRowThatAlreadyExistsCountsAsSavedWhenALostReplyIsRetried() = runBlocking {
        val repo = respond(200 to "{}".toByteArray(), 503 to "{}".toByteArray())
        val pending = try { repo.add(outing, bytes, "image/jpeg"); null } catch (e: KeepsakeSaveException) { e.pending!! }!!
        requests.clear()
        val retry = respond(409 to postgresError("23505"), 200 to "[${row(pending.id)}]".toByteArray())
        assertEquals(pending.id, retry.finish(pending).id)
        assertEquals(listOf("POST", "GET"), methods())
        assertEquals("id=eq.${pending.id}&select=*", requests[1].url.query)
        assertEquals(emptyList<Any>(), retry.pending(outing))
    }

    @Test fun givesUpOnAPhotoTheDatabaseCanNeverAccept() = runBlocking {
        for (code in listOf("22023", "54000", "42501")) {
            requests.clear()
            val repo = respond(200 to "{}".toByteArray(), 400 to postgresError(code))
            val failure = try { repo.add(outing, bytes, "image/jpeg"); null } catch (e: KeepsakeSaveException) { e }
            assertNotNull(code, failure)
            assertNull(code, failure!!.pending)
            assertEquals(code, emptyList<Any>(), repo.pending(outing))
        }
    }

    @Test fun theJournalSurvivesAsText() {
        var stored: String? = null
        val journal = TextKeepsakeJournal({ stored }, { stored = it })
        val pending = work.kumarfamilynet.cinemarchive.core.model.PendingKeepsake(newId, outing, "image/jpeg", 4)
        journal.store(listOf(pending))
        assertEquals(listOf(pending), TextKeepsakeJournal({ stored }, { stored = it }).load())
        journal.store(emptyList())
        assertNull(stored)
        stored = "{broken"
        assertEquals(emptyList<Any>(), journal.load())
        stored = """[{"id":"x"},null,{"id":"$newId","outingId":"$outing","mimeType":"text/html","byteLength":4}]"""
        assertEquals(emptyList<Any>(), journal.load())
    }

    @Test fun replaceWithRoomAddsTheNewPhotoThenRemovesTheOldOne() = runBlocking {
        val repo = respond(200 to "{}".toByteArray(), 201 to "[${row(newId)}]".toByteArray(), 204 to ByteArray(0))
        val result = repo.replace(old, bytes, "image/jpeg", 3)
        assertEquals(newId, result.saved.id)
        assertTrue(result.oldRemoved)
        assertEquals(listOf("POST", "POST", "DELETE"), methods())
        assertEquals("id=eq.$photoId&user_id=eq.$owner", requests[2].url.query)
    }

    @Test fun replaceNeverTouchesTheOldPhotoWhenTheUploadFails() = runBlocking {
        val repo = respond(500 to "{}".toByteArray())
        try { repo.replace(old, bytes, "image/jpeg", 3); fail() } catch (_: SupabaseHttpException) {}
        assertEquals(listOf("POST"), methods())
    }

    @Test fun replaceKeepsTheOldPhotoWhenTheNewRowCannotBeSavedAndLetsTheNewOneBeFinished() = runBlocking {
        val repo = respond(200 to "{}".toByteArray(), 503 to "{}".toByteArray())
        try { repo.replace(old, bytes, "image/jpeg", 3); fail() } catch (_: KeepsakeSaveException) {}
        assertEquals(listOf("POST", "POST"), methods())
        assertEquals(1, repo.pending(outing).size)
    }

    @Test fun replaceReportsBothPhotosWhenTheOldOneCannotBeRemoved() = runBlocking {
        val repo = respond(200 to "{}".toByteArray(), 201 to "[${row(newId)}]".toByteArray(), 500 to "{}".toByteArray())
        val result = repo.replace(old, bytes, "image/jpeg", 3)
        assertEquals(newId, result.saved.id)
        assertEquals(false, result.oldRemoved)
    }

    @Test fun replaceInAFullAlbumRemovesTheOldRowFirstThenAddsTheNewOne() = runBlocking {
        val repo = respond(200 to "{}".toByteArray(), 204 to ByteArray(0), 201 to "[${row(newId)}]".toByteArray())
        val result = repo.replace(old, bytes, "image/jpeg", 8)
        assertTrue(result.oldRemoved)
        assertEquals(listOf("POST", "DELETE", "POST"), methods())
    }

    @Test fun replaceInAFullAlbumPutsTheOldPhotoBackWhenTheNewRowFails() = runBlocking {
        val repo = respond(200 to "{}".toByteArray(), 204 to ByteArray(0), 503 to "{}".toByteArray(), 201 to "[${row()}]".toByteArray())
        val failure = try { repo.replace(old, bytes, "image/jpeg", 8); null } catch (e: KeepsakeSaveException) { e }
        assertNotNull(failure)
        assertEquals(false, failure!!.originalRemoved)
        assertEquals(listOf("POST", "DELETE", "POST", "POST"), methods())
        val restored = JSONArray(String(bodies[3])).getJSONObject(0)
        assertEquals(photoId, restored.getString("id"))
        assertEquals("$owner/$photoId", restored.getString("object_key"))
        assertEquals(emptyList<Any>(), repo.pending(outing)) // whole again: nothing left to finish
    }

    @Test fun replaceInAFullAlbumSaysSoWhenTheOldPhotoCannotBePutBack() = runBlocking {
        val repo = respond(200 to "{}".toByteArray(), 204 to ByteArray(0), 503 to "{}".toByteArray(), 503 to "{}".toByteArray())
        val failure = try { repo.replace(old, bytes, "image/jpeg", 8); null } catch (e: KeepsakeSaveException) { e }
        assertTrue(failure!!.originalRemoved)
        assertEquals(1, repo.pending(outing).size) // the new photo can still be finished
    }

    @Test fun replaceInAFullAlbumLeavesEverythingAloneWhenTheOldRowCannotBeRemoved() = runBlocking {
        val repo = respond(200 to "{}".toByteArray(), 500 to "{}".toByteArray())
        try { repo.replace(old, bytes, "image/jpeg", 8); fail() } catch (_: SupabaseHttpException) {}
        assertEquals(listOf("POST", "DELETE"), methods())
        assertEquals(emptyList<Any>(), repo.pending(outing))
    }
}
