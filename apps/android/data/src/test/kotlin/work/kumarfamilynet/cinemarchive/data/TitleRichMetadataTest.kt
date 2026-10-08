package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*

@RunWith(RobolectricTestRunner::class)
class TitleRichMetadataTest {
    private val shelf = """[{"id":"copy","format":"Blu-ray","edition":"Criterion","notes":"Region B","extension":{"keep":true}}]"""
    private fun wire() = JSONObject().put("contentRating", "PG-13").put("imdbId", "tt42").put("rtUrl", "https://www.rottentomatoes.com/m/film")
        .put("rtScore", 0).put("metacriticScore", 0).put("customWatchUrl", "https://example.org/watch?q=film")
        .put("inHomeCollection", false).put("physicalMedia", JSONArray(shelf)).put("awardsCount", 0)
        .put("bechdelOutcome", "fail").put("bechdelScore", "1/3")

    @Test fun missingFieldsPreserveRichMetadataAndExplicitNullClearsEveryField() {
        val rich = TitleMetadataFixture.entity().withRichMetadata(wire())
        assertEquals(rich, rich.withRichMetadata(JSONObject()))
        assertEquals(false, rich.inHomeCollection); assertEquals(0, rich.rtScore); assertEquals(0, rich.awardsCount)
        assertTrue(sameCommandJson(JSONObject().put("shelf", JSONArray(shelf)), JSONObject().put("shelf", JSONArray(rich.physicalMediaJson))))
        val nulls = JSONObject().also { cleared -> wire().keys().forEach { cleared.put(it, JSONObject.NULL) } }
        val clear = rich.withRichMetadata(nulls)
        assertEquals(TitleMetadataFixture.entity(), clear)
        assertEquals("[]", rich.withRichMetadata(JSONObject().put("physicalMedia", JSONArray())).physicalMediaJson)
    }

    @Test fun snakeCaseCurrentAckProjectionCarriesRichFieldsWithoutChangingOtherOwnerIntent() {
        val rich = wire()
        val snake = TitleMetadataFixture.row()
        val names = mapOf("contentRating" to "content_rating", "imdbId" to "imdb_id", "rtUrl" to "rt_url", "rtScore" to "rt_score",
            "metacriticScore" to "metacritic_score", "customWatchUrl" to "custom_watch_url", "inHomeCollection" to "in_home_collection",
            "physicalMedia" to "physical_media", "awardsCount" to "awards_count", "bechdelOutcome" to "bechdel_outcome", "bechdelScore" to "bechdel_score")
        names.forEach { (camel, key) -> snake.put(key, rich.get(camel)) }
        val projected = snake.toMetadataTitle(TitleMetadataFixture.entity())
        assertEquals(TitleMetadataFixture.entity().withRichMetadata(rich), projected)
        assertEquals("Keep notes", projected.notes)
        assertEquals(projected, TitleMetadataFixture.row().toMetadataTitle(projected))
    }

    @Test fun physicalCopiesKeepNotesStableIdsAndUnknownFieldsWhileMalformedSiblingsStayInRawStorage() {
        val raw = JSONArray(shelf).put(JSONObject().put("id", "future").put("format", "Future format").put("notes", "Preserve"))
            .put(JSONObject().put("id", "malformed")).toString()
        val copies = physicalMediaItems(raw)
        assertEquals(listOf("copy", "future"), copies.map { it.id })
        assertEquals("Region B", copies.first().notes)
        assertEquals("Future format", copies.last().format)
        assertTrue(JSONObject(copies.first().sourceJson).getJSONObject("extension").getBoolean("keep"))
        val row = TitleMetadataFixture.entity().copy(physicalMediaJson = raw)
        physicalMediaItems(row.physicalMediaJson)
        assertEquals(raw, row.physicalMediaJson)
    }

    @Test fun realRoomDetailProjectionKeepsRichMetadataAndUnchangedHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val row = TitleMetadataFixture.entity().withRichMetadata(wire())
            db.titleDao().upsertAll(listOf(row))
            db.viewingDao().upsert(ViewingEntity("event", row.id, null, 4.0, "History", null))
            val box = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter { override suspend fun push(entry: OutboxEntity) = PushResult.Retry("offline") }, TitleConflictHandler(db.titleDao()), RoomTransactor(db))
            val detail = TitleMetadataFixture.library(db, box).observeTitleDetail(row.id).first()!!
            assertEquals("PG-13", detail.contentRating); assertEquals("tt42", detail.imdbId)
            assertEquals(row.rtUrl, detail.rtUrl); assertEquals(0, detail.rtScore); assertEquals(0, detail.metacriticScore)
            assertEquals(row.customWatchUrl, detail.customWatchUrl); assertEquals(false, detail.inHomeCollection)
            assertEquals("Region B", detail.physicalMedia.single().notes)
            assertEquals("copy", detail.physicalMedia.single().id)
            assertEquals(0, detail.awardsCount); assertEquals("fail", detail.bechdelOutcome); assertEquals("1/3", detail.bechdelScore)
            assertEquals("History", detail.viewings.single().notes)
            assertTrue(db.outboxDao().getPending().isEmpty())
        } finally { db.close() }
    }
}
