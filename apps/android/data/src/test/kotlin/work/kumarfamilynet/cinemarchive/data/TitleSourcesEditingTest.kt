package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.*
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
class TitleSourcesEditingTest {
    private lateinit var db: LibraryDatabase
    private lateinit var box: MutationOutbox
    private var active = true
    private val original get() = TitleMetadataFixture.entity().copy(customWatchUrl = "https://example.org/watch",
        inHomeCollection = true, physicalMediaJson = """[{"id":"copy","format":"DVD","notes":"Region 2","opaque":9007199254740993,"decimal":0.1234567890123456789}]""")
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        box = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity) = PushResult.Retry("Offline")
        }, TitleConflictHandler(db.titleDao()), RoomTransactor(db))
        db.titleDao().upsertAll(listOf(original))
    }
    @After fun close() { db.close() }
    private fun editor() = TitleSourcesEditing(db.titleDao(), box, TitleMetadataFixture.owner, { active })

    @Test fun fullDraftRetainsOpaqueCopyFieldsAndStableRequestOnRetry() = runBlocking {
        val editor = editor()
        val opening = editor.capture(original.id)
        val values = titleSourcesValues(opening)
        val desired = values.copy(watchUrl = "https://example.org/new", copiesJson = addPhysicalCopy(values.copiesJson, "4K UHD", " Steelbook "))
        editor.save(original.id, opening, desired)
        val first = db.outboxDao().getPending().single()
        box.flush()
        editor.save(original.id, opening, desired)
        val retry = db.outboxDao().getPending().single()
        assertEquals(first.id, retry.id); assertEquals(first.payloadJson, retry.payloadJson)
        assertEquals(1, retry.attemptCount)
        val patch = titleMetadataPatch(retry, TitleMetadataFixture.owner)
        assertFalse(patch.has("in_home_collection"))
        val oldCopy = patch.getJSONArray("physical_media").getJSONObject(0)
        assertEquals("9007199254740993", oldCopy.get("opaque").toString())
        assertEquals("0.1234567890123456789", oldCopy.get("decimal").toString())
        assertEquals("Region 2", oldCopy.getString("notes"))
        assertEquals("Steelbook", patch.getJSONArray("physical_media").getJSONObject(1).getString("edition"))
        val stored = db.titleDao().getById(original.id)!!
        assertEquals(original.updatedAt, stored.updatedAt)
        assertEquals(original.notes, stored.notes)
        assertEquals("9007199254740993", physicalMediaItems(stored.physicalMediaJson).first().let {
            exactMetadataObject(it.sourceJson).get("opaque").toString()
        })
    }

    @Test fun laterRefreshNeverRebasesOpeningAndUnchangedFieldsDoNotOverwriteIt() = runBlocking {
        val editor = editor()
        val opening = editor.capture(original.id)
        db.titleDao().upsertAll(listOf(original.copy(customWatchUrl = "https://example.org/newer", updatedAt = TitleMetadataFixture.applied,
            tags = listOf("Newer"), notes = "Newer notes")))
        editor.save(original.id, opening, titleSourcesValues(opening).copy(homeCollection = false))
        val entry = db.outboxDao().getPending().single()
        val operation = titleMetadataOperation(entry, TitleMetadataFixture.owner)
        assertEquals(TitleMetadataFixture.baseline, operation.getString("expectedUpdatedAt"))
        assertEquals(setOf("in_home_collection"), operation.getJSONObject("values").keys().asSequence().toSet())
        val local = db.titleDao().getById(original.id)!!
        assertEquals("https://example.org/newer", local.customWatchUrl)
        assertEquals(listOf("Newer"), local.tags); assertEquals("Newer notes", local.notes)
    }

    @Test fun clearLastCopyUsesEmptyArrayAndWatchLinkUsesNull() = runBlocking {
        val editor = editor(); val opening = editor.capture(original.id)
        editor.save(original.id, opening, TitleSourcesValues("", false, removePhysicalCopy(titleSourcesValues(opening).copiesJson, "copy")))
        val patch = titleMetadataPatch(db.outboxDao().getPending().single(), TitleMetadataFixture.owner)
        assertTrue(patch.has("custom_watch_url") && patch.isNull("custom_watch_url"))
        assertEquals(0, patch.getJSONArray("physical_media").length())
        assertFalse(patch.getBoolean("in_home_collection"))
        assertEquals("[]", db.titleDao().getById(original.id)!!.physicalMediaJson)
    }

    @Test fun openingRetainsPredecessorAndLegacyIntentRequiresReview() = runBlocking {
        val first = TitleMetadataFixture.entry()
        db.outboxDao().enqueue(first)
        val editor = editor(); val opening = editor.capture(original.id)
        editor.save(original.id, opening, titleSourcesValues(opening).copy(homeCollection = false))
        assertEquals(first.id, titleMetadataOperation(db.outboxDao().getPending().last(), TitleMetadataFixture.owner).getString("expectedOperationId"))
        db.outboxDao().getPending().forEach { db.outboxDao().remove(it.id) }
        db.outboxDao().enqueue(first.copy(operation = "update", payloadJson = """{"status":"WATCHED"}"""))
        val legacyOpening = editor.capture(original.id)
        editor.save(original.id, legacyOpening, titleSourcesValues(legacyOpening).copy(watchUrl = "https://example.org/new"))
        assertEquals("review", db.outboxDao().getPending().last().operation)
    }

    @Test fun collectionColumnsRejectNullToMatchSharedNotNullConstraints() {
        for (field in listOf("physical_media", "in_home_collection")) {
            try { checkedTitlePatch(JSONObject().put(field, JSONObject.NULL)); fail("Null $field must not enter the queue") }
            catch (_: Exception) { }
        }
        checkedTitlePatch(JSONObject().put("physical_media", JSONArray()).put("in_home_collection", false))
    }

    @Test fun removingKnownCopyPreservesMalformedLegacySiblingForVisibleValidation() {
        val after = removePhysicalCopy("""[{"id":"keep","format":"DVD"},7,{"id":"remove","format":"VHS"}]""", "remove")
        val rows = exactMetadataArray(after)
        assertEquals(2, rows.length()); assertEquals(7, rows.getInt(1))
        try { checkedTitlePatch(JSONObject().put("physical_media", rows)); fail() } catch (_: Exception) { }
    }

    @Test fun noOpAndExpiredOrCrossOwnerOpeningNeverMutate() = runBlocking {
        val editor = editor(); val opening = editor.capture(original.id)
        editor.save(original.id, opening, titleSourcesValues(opening))
        assertTrue(db.outboxDao().getPending().isEmpty())
        val desired = titleSourcesValues(opening).copy(homeCollection = false)
        active = false
        try { editor.save(original.id, opening, desired); fail() } catch (_: IllegalStateException) { }
        active = true
        val foreign = exactMetadataObject(opening).put("ownerId", "other").let(::metadataJson)
        try { editor.save(original.id, foreign, desired); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(original, db.titleDao().getById(original.id))
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun unsupportedTextFailsBeforeLocalSave() = runBlocking {
        val editor = editor(); val opening = editor.capture(original.id)
        val desired = titleSourcesValues(opening).copy(copiesJson = addPhysicalCopy(titleSourcesValues(opening).copiesJson, "DVD", "bad\u0000text"))
        try { editor.save(original.id, opening, desired); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(original, db.titleDao().getById(original.id)); assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun actualTransportPreservesOpaqueNumbersInRequestReceiptAndCurrentRead() = runBlocking {
        val editor = editor(); val opening = editor.capture(original.id)
        editor.save(original.id, opening, titleSourcesValues(opening).copy(copiesJson = addPhysicalCopy(titleSourcesValues(opening).copiesJson, "VHS", "")))
        val entry = db.outboxDao().getPending().single()
        val effect = TitleMetadataFixture.row().put("updated_at", TitleMetadataFixture.applied)
        val patch = titleMetadataPatch(entry, TitleMetadataFixture.owner)
        patch.keys().forEach { effect.put(it, patch.get(it)) }
        val receipt = JSONObject().put("operationId", entry.id).put("rows", JSONArray().put(
            JSONObject().put("table", "titles").put("key", JSONObject().put("id", original.id)).put("row", effect)))
        var sent = ""
        val client = SupabaseRestClient("https://fixture.supabase.co", "anon", OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val response = if (request.method == "POST") {
                sent = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                metadataJson(receipt)
            } else metadataJson(JSONArray().put(effect))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build())
        val transport = TitleMetadataTransport(client, SessionSource { SupabaseSession("token", TitleMetadataFixture.owner) })
        val result = transport.push(entry) as PushResult.Applied
        assertTrue(sent.contains("9007199254740993")); assertTrue(sent.contains("0.1234567890123456789"))
        assertEquals("9007199254740993", result.receipt.getJSONObject("current").getJSONArray("physical_media").getJSONObject(0).get("opaque").toString())
        checkedTitleMetadataReceipt(entry, result.receipt.getJSONObject("receipt"), TitleMetadataFixture.owner)
        Unit
    }

    @Test fun receiptAndLaterPatchRetainExactCollectionAndCurrentUnrelatedFields() = runBlocking {
        val editor = editor(); val opening = editor.capture(original.id)
        editor.save(original.id, opening, titleSourcesValues(opening).copy(copiesJson = addPhysicalCopy(titleSourcesValues(opening).copiesJson, "Blu-ray", "")))
        val first = db.outboxDao().getPending().single()
        val opening2 = editor.capture(original.id)
        editor.save(original.id, opening2, titleSourcesValues(opening2).copy(homeCollection = false))
        val effect = TitleMetadataFixture.row().put("updated_at", TitleMetadataFixture.applied)
        val patch = titleMetadataPatch(first, TitleMetadataFixture.owner)
        patch.keys().forEach { effect.put(it, patch.get(it)) }
        effect.put("custom_watch_url", original.customWatchUrl).put("in_home_collection", true)
        val receipt = JSONObject().put("operationId", first.id).put("rows", JSONArray().put(
            JSONObject().put("table", "titles").put("key", JSONObject().put("id", original.id)).put("row", effect)))
        val current = exactMetadataObject(metadataJson(effect)).put("notes", "Current notes")
        val envelope = exactMetadataObject(metadataJson(JSONObject().put("receipt", receipt).put("current", current)))
        RoomTransactor(db).run { TitleMetadataApplier(db, TitleMetadataFixture.owner).apply(first, envelope) }
        val local = db.titleDao().getById(original.id)!!
        assertEquals(false, local.inHomeCollection); assertEquals("Current notes", local.notes)
        assertTrue(local.physicalMediaJson!!.contains("9007199254740993"))
        val values = current.titleMetadataValues()
        assertEquals(original.customWatchUrl, values.watchUrl)
        assertEquals(listOf("DVD · Region 2", "Blu-ray"), values.physicalCopies)
    }
}
