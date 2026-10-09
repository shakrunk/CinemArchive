package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.UUID
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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.*

@RunWith(RobolectricTestRunner::class)
class EpisodeCatalogFillTest {
    private val titleId = UUID.randomUUID().toString()
    private val owner = UUID.randomUUID().toString()
    private val coarseId = UUID.randomUUID().toString()
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).build()
    private suspend fun seed(db: LibraryDatabase) {
        db.titleDao().upsertAll(listOf(TitleEntity(titleId,42,"TV","Show",2020,null,emptyList(),null,null,null,30,null,"WATCHING",4.0,"Keep notes","2026-01-01","2026-01-01")))
        db.seasonDao().upsertAll(listOf(SeasonEntity(coarseId,titleId,1,3,2,2020)))
    }
    private fun fresh(seasons: List<MediaSeason> = listOf(
        MediaSeason(1,3,2020,(1..3).map { MediaEpisode(it,"Episode $it",null,30,crew=listOf(MediaCrewCredit(42,"Writer","Writer",null))) }),
        MediaSeason(0,2,2019,listOf(MediaEpisode(3,"Special 3",null,10),MediaEpisode(9,"Special 9",null,20)),cast=listOf(MediaCredit(84,"Special guest",null,0))),
    )) = MediaDetails(42,MediaType.TV,"Show",2020,null,null,emptyList(),null,null,null,null,null,null,null,null,seasons=seasons)
    private fun box(db: LibraryDatabase, writer: RemoteMutationWriter) = MutationOutbox(db.outboxDao(),writer,
        TitleConflictHandler(db.titleDao()),RoomTransactor(db), AppliedMutationHandler { entry, envelope ->
            when(entry.entityType) {
                "title_catalog" -> EpisodeCatalogFillApplier(db,owner).apply(entry,envelope)
                "title_credits" -> CreditReceiptApplier(db,owner).apply(entry,envelope)
                else -> error("unexpected entry")
            }
        }, CreditReceiptApplier(db,owner)::protectionKeys)
    private suspend fun queue(db: LibraryDatabase, box: MutationOutbox, fresh: MediaDetails = fresh()) =
        CreditRefreshRepository(db,box,CreditMetadataFetcher { fresh },owner,{true}).refresh(titleId)

    /** Simulates persisted receipt replay and natural-key winners, while exercising real HTTP/writer code. */
    private inner class Server {
        val records = mutableMapOf<String,MutableMap<String,JSONObject>>()
        val receipts = mutableMapOf<String,JSONObject>()
        val requests = mutableListOf<String>()
        var loseResponse = false
        var beforeGet: (() -> Unit)? = null
        var corruptGet: ((JSONObject) -> Unit)? = null
        private fun identity(key: JSONObject) = key.keys().asSequence().sorted().joinToString { "$it=${key.get(it)}" }
        fun existing(table: String, key: JSONObject, values: JSONObject = JSONObject(), id: String = UUID.randomUUID().toString()): JSONObject {
            val row = JSONObject(values.toString())
            key.keys().forEach { row.put(it,key.get(it)) }
            row.put("id",id).put("user_id",owner)
            if(table=="seasons") { if(!row.has("episodes_watched")) row.put("episodes_watched",0); if(!row.has("episode_count")) row.put("episode_count",0) }
            records.getOrPut(table) { mutableMapOf() }[identity(key)]=row
            return row
        }
        fun receipt(entry: OutboxEntity): JSONObject = receipts.getOrPut(entry.id) {
            val operations=JSONObject(entry.payloadJson).getJSONArray("operations")
            JSONObject().put("operationId",entry.id).put("rows",JSONArray((0 until operations.length()).map { index ->
                val op=operations.getJSONObject(index); val table=op.getString("table"); val key=op.getJSONObject("key")
                val previous=records[table]?.get(identity(key))
                val row=if(op.getString("action")=="ensure" && previous!=null) previous else {
                    existing(table,key,op.getJSONObject("values"),previous?.getString("id") ?: key.optString("id").ifEmpty { UUID.randomUUID().toString() })
                }
                JSONObject().put("table",table).put("key",key).put("row",JSONObject(row.toString()))
            }))
        }
        val client = SupabaseRestClient("https://x.supabase.co","anon",OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            val response=if(request.method=="POST") {
                val body=Buffer().also { request.body!!.writeTo(it) }.readUtf8(); requests+=body
                val parsed=JSONObject(body)
                val entry=OutboxEntity(parsed.getString("p_operation_id"),"unused",titleId,"unused",JSONObject().put("operations",parsed.getJSONArray("p_operations")).toString(),0)
                val accepted=receipt(entry)
                if(loseResponse) { loseResponse=false; throw IOException("lost accepted response") }
                accepted.toString()
            } else {
                beforeGet?.invoke()
                val table=request.url.pathSegments.last()
                val ids=request.url.queryParameter("id")!!.removePrefix("in.(").removeSuffix(")").split(",")
                JSONArray(records[table].orEmpty().values.filter { it.getString("id") in ids }.map { JSONObject(it.toString()).also { row -> corruptGet?.invoke(row) } }).toString()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build())
        fun writer() = SupabaseRemoteMutationWriter(client) { SupabaseSession("token",owner) }
        fun seedCoarse() = existing("seasons",JSONObject().put("title_id",titleId).put("season_number",1),
            JSONObject().put("episode_count",3).put("episodes_watched",2).put("air_year",2020),coarseId)
    }

    @Test fun fillUsesCanonicalConcurrentParentsAndPreservesCoarseProgressAndExistingHistory() = runBlocking {
        val db=database()
        try {
            seed(db)
            val partialSeason=UUID.randomUUID().toString(); val existingEpisode=UUID.randomUUID().toString()
            db.seasonDao().upsertAll(listOf(SeasonEntity(partialSeason,titleId,2,5,4,2021)))
            db.episodeDao().upsertAll(listOf(EpisodeEntity(existingEpisode,titleId,partialSeason,1,"Keep local",null,40)))
            db.episodeWatchEventDao().upsertAll(listOf(EpisodeWatchEventEntity("watch",existingEpisode,null,"Keep history")))
            val server=Server(); server.seedCoarse()
            val special=server.existing("seasons",JSONObject().put("title_id",titleId).put("season_number",0),JSONObject().put("episode_count",2).put("episodes_watched",1))
            val canonicalEpisode=server.existing("episodes",JSONObject().put("title_id",titleId).put("season_number",0).put("episode_number",3),JSONObject().put("episode_name","Concurrent metadata"))
            val outbox=box(db,server.writer())
            val source=fresh().copy(seasons=fresh().seasons+listOf(MediaSeason(2,5,2021,listOf(MediaEpisode(2,"Not appended",null,30))),MediaSeason(3,1,2022,listOf(MediaEpisode(1,"Not added",null,30)))))
            assertTrue(queue(db,outbox,source))
            assertEquals(1,db.episodeDao().observeEpisodes(titleId).first().size)
            assertEquals(setOf(1,2),db.seasonDao().observeSeasons(titleId).first().map { it.seasonNumber }.toSet())
            val queued=db.outboxDao().getPending().first { it.entityType=="title_catalog" }
            assertFalse(queued.payloadJson.contains("episodes_watched"))
            outbox.flush()
            assertTrue(db.outboxDao().getPending().isEmpty())
            val seasons=db.seasonDao().observeSeasons(titleId).first()
            assertEquals(2,seasons.single { it.id==coarseId }.episodesWatched)
            assertEquals(special.getString("id"),seasons.single { it.seasonNumber==0 }.id)
            val episodes=db.episodeDao().observeEpisodes(titleId).first()
            assertEquals(setOf(3,9),episodes.filter { it.seasonId==special.getString("id") }.map { it.episodeNumber }.toSet())
            assertEquals("Concurrent metadata",episodes.single { it.id==canonicalEpisode.getString("id") }.episodeName)
            assertEquals("Keep local",episodes.single { it.id==existingEpisode }.episodeName)
            assertEquals("Keep history",db.episodeWatchEventDao().observeAllWatchEvents().first().single().notes)
            assertEquals(3,db.personCreditsDao().observeEpisodeCrew().first().size)
            assertEquals(special.getString("id"),db.personCreditsDao().observeSeasonCast().first().single().seasonId)
            assertEquals("Keep notes",db.titleDao().getById(titleId)!!.notes)
        } finally { db.close() }
    }

    @Test fun lostResponseRetriesImmutableCommandAfterRecreatingWriterAndDoesNotExposeProvisionalParents() = runBlocking {
        val db=database()
        try {
            seed(db); val server=Server(); server.seedCoarse(); server.loseResponse=true
            val outbox=box(db,server.writer()); queue(db,outbox)
            val queued=db.outboxDao().getPending().first()
            outbox.flush()
            assertEquals(queued.payloadJson,db.outboxDao().getPending().first().payloadJson)
            assertTrue(db.episodeDao().observeEpisodes(titleId).first().isEmpty())
            box(db,server.writer()).flush()
            assertTrue(db.outboxDao().getPending().isEmpty())
            assertEquals(server.requests[0],server.requests[1])
            assertEquals(5,db.episodeDao().observeEpisodes(titleId).first().size)
        } finally { db.close() }
    }

    @Test fun historicalReceiptCannotResurrectRemotelyDeletedOrLocallyDeletedParents() = runBlocking {
        val db=database()
        try {
            seed(db); val server=Server(); server.seedCoarse()
            val outbox=box(db,server.writer()); queue(db,outbox)
            val command=db.outboxDao().getPending().first(); server.receipt(command)
            server.beforeGet={ server.records["episodes"]?.clear(); server.records["seasons"]?.entries?.removeAll { it.value.getInt("season_number")==0 } }
            outbox.flush()
            assertTrue(db.episodeDao().observeEpisodes(titleId).first().isEmpty())
            assertEquals(listOf(coarseId),db.seasonDao().observeSeasons(titleId).first().map { it.id })
            assertTrue(db.personCreditsDao().observeEpisodeCrew().first().isEmpty())
            queue(db,outbox); db.titleDao().deleteById(titleId)
            outbox.flush()
            assertTrue(db.seasonDao().observeSeasons(titleId).first().isEmpty())
            assertTrue(db.outboxDao().getPending().isEmpty())
        } finally { db.close() }
    }

    @Test fun queueFailureRollsBackCanonicalParentsAndRetriesTheSameReceipt() = runBlocking {
        val db=database()
        try {
            seed(db); val server=Server(); server.seedCoarse(); val outbox=box(db,server.writer()); queue(db,outbox)
            val entry=db.outboxDao().getPending().first()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_credit_queue BEFORE INSERT ON mutation_outbox WHEN NEW.entityType = 'title_credits' BEGIN SELECT RAISE(ABORT, 'storage full'); END")
            outbox.flush()
            assertEquals(entry.id,db.outboxDao().getPending().single().id)
            assertTrue(db.episodeDao().observeEpisodes(titleId).first().isEmpty())
            assertEquals(1,db.seasonDao().observeSeasons(titleId).first().size)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_credit_queue")
            outbox.flush()
            assertEquals(5,db.episodeDao().observeEpisodes(titleId).first().size)
            assertTrue(db.outboxDao().getPending().isEmpty())
        } finally { db.close() }
    }

    @Test fun malformedCurrentOwnerFailsClosedAndEmptyProviderResponsesDoNotCreateShells() = runBlocking {
        val db=database()
        try {
            seed(db); val server=Server(); server.seedCoarse(); val outbox=box(db,server.writer())
            assertFalse(queue(db,outbox,fresh(listOf(MediaSeason(0,5,null),MediaSeason(1,3,2020)))))
            assertTrue(db.outboxDao().getPending().isEmpty())
            queue(db,outbox); server.corruptGet={ it.put("user_id",UUID.randomUUID().toString()) }
            outbox.flush()
            assertEquals(1,db.outboxDao().getPending().size)
            assertTrue(db.episodeDao().observeEpisodes(titleId).first().isEmpty())
        } finally { db.close() }
    }

    @Test fun existingCanonicalRowsAndLaterQueuedCreditsKeepTheirNewerLocalValues() = runBlocking {
        val db=database()
        try {
            seed(db); val server=Server(); server.seedCoarse(); val outbox=box(db,server.writer()); queue(db,outbox)
            val fill=db.outboxDao().getPending().single(); val receipt=server.receipt(fill)
            val row=receipt.getJSONArray("rows").getJSONObject(2).getJSONObject("row")
            val episodeId=row.getString("id")
            db.episodeDao().upsertAll(listOf(EpisodeEntity(episodeId,titleId,coarseId,1,"New local metadata",null,99)))
            db.episodeWatchEventDao().upsertAll(listOf(EpisodeWatchEventEntity("new-watch",episodeId,"2026-10-08","New watch")))
            val credit=CreditRow("episode_crew",UUID.randomUUID().toString(),titleId,episodeId,42,"Latest writer","Writer")
            writeCreditRows(db,listOf(credit))
            outbox.enqueue("title_credits",titleId,"refresh",JSONObject().put("ownerId",owner).put("titleId",titleId).put("operations",JSONArray()
                .put(JSONObject().put("table",credit.table).put("action","put").put("key",credit.key()).put("values",credit.values()))))
            outbox.flush()
            assertEquals("New local metadata",db.episodeDao().getById(episodeId)!!.episodeName)
            assertEquals("New watch",db.episodeWatchEventDao().observeAllWatchEvents().first().single().notes)
            assertEquals("Latest writer",db.personCreditsDao().observeEpisodeCrew().first().single { it.episodeId==episodeId }.name)
        } finally { db.close() }
    }

    @Test fun parentFillRetainsExistingSeasonProfilesForUnchangedAndUpdatedCredits() = runBlocking {
        for (changedName in listOf(false, true)) {
            val db = database()
            try {
                seed(db)
                val server = Server(); server.seedCoarse()
                val outbox = box(db, server.writer())
                val original = SeasonCastEntity(UUID.randomUUID().toString(), titleId, coarseId, 84,
                    "Stored guest", null, 0, "https://image/retained", 0)
                db.personCreditsDao().upsertSeasonCast(listOf(original))
                val name = if (changedName) "Updated guest" else original.name
                val source = fresh(listOf(MediaSeason(1, 3, 2020, listOf(MediaEpisode(1, "Episode", null, 30)),
                    cast = listOf(MediaCredit(84, name, null, 0)))))
                // Exercise the production parent-fill path independently of the ordinary refresh.
                outbox.atomically { assertTrue(enqueueMissingEpisodeCatalog(db, outbox, titleId, owner, source)) }
                outbox.flush()
                assertTrue(db.outboxDao().getPending().isEmpty())
                val saved = db.personCreditsDao().observeSeasonCast().first().single()
                assertEquals(name, saved.name); assertEquals("https://image/retained", saved.profileUrl); assertEquals(0, saved.episodeCount)
                if (changedName) assertNotEquals(original.id, saved.id) else assertEquals(original.id, saved.id)
                assertEquals(if (changedName) 2 else 1, server.requests.size)
                server.requests.map { JSONObject(it).getJSONArray("p_operations") }.forEach { operations ->
                    for (index in 0 until operations.length()) {
                        val operation = operations.getJSONObject(index)
                        if (operation.getString("table") == "season_cast") {
                            assertFalse(operation.getJSONObject("values").has("profile_url"))
                            assertFalse(operation.getJSONObject("values").has("episode_count"))
                        }
                    }
                }
            } finally { db.close() }
        }
    }
}
