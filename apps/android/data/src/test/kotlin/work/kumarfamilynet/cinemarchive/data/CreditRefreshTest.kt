package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
class CreditRefreshTest {
    private val titleId = UUID.randomUUID().toString()
    private val seasonId = UUID.randomUUID().toString()
    private val episodeId = UUID.randomUUID().toString()
    private val ownerId = UUID.randomUUID().toString()
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).build()
    private fun fresh(cast: List<MediaCredit> = (1..60).map { MediaCredit(it, "Person $it", null, it) }, seasons: List<MediaSeason> = emptyList()) =
        MediaDetails(42, MediaType.TV, "Catalog name", 2020, null, null, emptyList(), null, null, null, null, null, null, null, null, cast = cast, seasons = seasons)
    private suspend fun seed(db: LibraryDatabase) {
        db.titleDao().upsertAll(listOf(TitleEntity(titleId,42,"TV","My show",2020,null,emptyList(),null,null,null,30,null,"DROPPED",4.5,"My notes","2026-01-01","2026-01-01")))
        db.seasonDao().upsertAll(listOf(SeasonEntity(seasonId,titleId,0,1,1,2020)))
        db.episodeDao().upsertAll(listOf(EpisodeEntity(episodeId,titleId,seasonId,3,"Special",null,30)))
        db.episodeWatchEventDao().upsertAll(listOf(EpisodeWatchEventEntity("watch",episodeId,null,"Before joining")))
        db.titleCastDao().upsertAll((1..20).map { TitleCastEntity(UUID.randomUUID().toString(),titleId,it,"Person $it",null,it) })
        db.personCreditsDao().upsertSeasonCast(listOf(SeasonCastEntity(UUID.randomUUID().toString(),titleId,seasonId,90,"Season person",null,0)))
        db.personCreditsDao().upsertEpisodeCrew(listOf(EpisodeCrewEntity(UUID.randomUUID().toString(),titleId,episodeId,91,"Episode person","Writer")))
    }
    private fun outbox(db: LibraryDatabase, writer: RemoteMutationWriter, handler: AppliedMutationHandler = CreditReceiptApplier(db,ownerId)) =
        MutationOutbox(db.outboxDao(),writer,TitleConflictHandler(db.titleDao()),RoomTransactor(db),handler,
            pendingProjectionKeys = CreditReceiptApplier(db,ownerId)::protectionKeys)
    private fun repo(db: LibraryDatabase, box: MutationOutbox, result: MediaDetails, current: () -> Boolean = { true }) =
        CreditRefreshRepository(db,box,CreditMetadataFetcher { result },ownerId,current)

    private val serverIds = mutableMapOf<String,String>()
    private fun receipt(entry: OutboxEntity): JSONObject {
        val operations = JSONObject(entry.payloadJson).getJSONArray("operations")
        return JSONObject().put("operationId",entry.id).put("rows",JSONArray((0 until operations.length()).map { index ->
            val op = operations.getJSONObject(index)
            val key = op.getJSONObject("key")
            JSONObject().put("table",op.getString("table")).put("key",key).apply {
                if (op.getString("action") == "delete") put("deleted",true)
                else {
                    val row = JSONObject((op.optJSONObject("values") ?: JSONObject()).toString())
                    key.keys().forEach { row.put(it,key.get(it)) }
                    row.put("id",if (key.has("id")) key.getString("id") else serverIds.getOrPut(op.getString("table")+key.toString()) { UUID.randomUUID().toString() })
                    row.put("user_id",ownerId)
                    put("row",row)
                }
            }
        }))
    }

    @Test fun truncatedCreditsRefreshThroughActualWriterWithStableRetryAndCanonicalIds() = runBlocking {
        val db = database()
        try {
            seed(db)
            val original = readCreditRows(db,titleId)
            val sent = mutableListOf<String>()
            var offline = true
            val client = SupabaseRestClient("https://x.supabase.co","anon",OkHttpClient.Builder().addInterceptor { chain ->
                val body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                sent += body
                val request = JSONObject(body)
                val entry = OutboxEntity(request.getString("p_operation_id"),"title_credits",titleId,"refresh",JSONObject().put("operations",request.getJSONArray("p_operations")).toString(),0)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (offline) 503 else 200).message("test")
                    .body((if (offline) "{\"message\":\"offline\"}" else receipt(entry).toString()).toResponseBody("application/json".toMediaType())).build()
            }.build())
            val box = outbox(db,SupabaseRemoteMutationWriter(client) { SupabaseSession("token",ownerId) })
            assertTrue(repo(db,box,fresh()).refresh(titleId))
            val queued = db.outboxDao().getPending().single()
            assertEquals(60,db.titleCastDao().observeAllCast().first().size)
            assertEquals(original.filter { it.table == "title_cast" }.map { it.id }.toSet(),readCreditRows(db,titleId).filter { it.table == "title_cast" && it.personId <= 20 }.map { it.id }.toSet())
            assertEquals(41,JSONObject(queued.payloadJson).getJSONArray("operations").length()) // barrier + only 40 missing people
            box.flush(); assertEquals(queued.id,db.outboxDao().getPending().single().id)
            offline = false; box.flush()
            assertEquals(sent[0],sent[1]); assertTrue(db.outboxDao().getPending().isEmpty())
            assertEquals(60,db.titleCastDao().observeAllCast().first().size)
            assertEquals(serverIds.values.toSet(),readCreditRows(db,titleId).filter { it.table == "title_cast" && it.personId > 20 }.map { it.id }.toSet())
            assertEquals("DROPPED",db.titleDao().getById(titleId)!!.status)
            assertEquals("My notes",db.titleDao().getById(titleId)!!.notes)
            assertEquals("Before joining",db.episodeWatchEventDao().observeAllWatchEvents().first().single().notes)
            assertEquals(original.filter { it.table != "title_cast" },readCreditRows(db,titleId).filter { it.table != "title_cast" })
        } finally { db.close() }
    }

    @Test fun seasonAndEpisodeCreditsKeepIdentityWhileEmptyFetchPreservesPreviouslyKnownPeople() = runBlocking {
        val db=database()
        try {
            seed(db)
            val box=outbox(db,object:RemoteMutationWriter { override suspend fun push(entry:OutboxEntity)=PushResult.Retry("offline") })
            val seasons=listOf(MediaSeason(0,1,2020,listOf(MediaEpisode(3,"Special",null,30,crew=listOf(MediaCrewCredit(91,"Updated writer","Writer",null),MediaCrewCredit(92,"Updated writer","Story",null)))),cast=listOf(MediaCredit(90,"Updated actor",null,1))))
            val old=readCreditRows(db,titleId)
            assertTrue(repo(db,box,fresh(seasons=seasons)).refresh(titleId))
            val after=readCreditRows(db,titleId)
            assertEquals(old.first { it.personId==90 }.id,after.first { it.personId==90 }.id)
            assertEquals(old.first { it.personId==91 }.id,after.first { it.personId==91 }.id)
            assertEquals(setOf(91,92),after.filter { it.table=="episode_crew" }.map { it.personId }.toSet())
            assertFalse(repo(db,box,fresh()).refresh(titleId))
            assertEquals(after,readCreditRows(db,titleId))
            val pending=box.pendingEntityKeys()
            assertTrue(isProtectedFromPull(JSONObject().put("entity_type","title_cast").put("entity_id",UUID.randomUUID().toString()).put("payload",JSONObject().put("titleId",titleId)),pending))
            assertTrue(isProtectedFromPull(JSONObject().put("entity_type","tombstone").put("entity_id",after.first { it.personId==91 }.id).put("parent_id",JSONObject.NULL).put("payload",JSONObject().put("entityType","episode_crew")),pending))
            assertFalse(isProtectedFromPull(JSONObject().put("entity_type","tombstone").put("entity_id","unrelated").put("parent_id",JSONObject.NULL).put("payload",JSONObject().put("entityType","episode_crew")),pending))
            assertFalse(isProtectedFromPull(JSONObject().put("entity_type","title_cast").put("entity_id","other").put("payload",JSONObject().put("titleId","other")),pending))
        } finally { db.close() }
    }

    @Test fun staleOwnerOrDeletedTitleDuringFetchCannotSaveOrResurrectCredits() = runBlocking {
        val db=database()
        try {
            seed(db); var active=true
            val box=outbox(db,object:RemoteMutationWriter { override suspend fun push(entry:OutboxEntity)=PushResult.Success })
            val before=readCreditRows(db,titleId)
            val switched=CreditRefreshRepository(db,box,CreditMetadataFetcher { active=false; fresh() },ownerId,{active})
            assertTrue(runCatching { switched.refresh(titleId) }.isFailure)
            assertEquals(before,readCreditRows(db,titleId)); assertTrue(db.outboxDao().getPending().isEmpty())
            val deleted=CreditRefreshRepository(db,box,CreditMetadataFetcher { db.titleDao().deleteById(titleId); fresh() },ownerId,{true})
            assertTrue(runCatching { deleted.refresh(titleId) }.isFailure)
            assertTrue(readCreditRows(db,titleId).isEmpty()); assertTrue(db.outboxDao().getPending().isEmpty())
        } finally { db.close() }
    }

    @Test fun queueStorageFailureRollsBackCreditReplacementAndLeavesHistory() = runBlocking {
        val db=database()
        try {
            seed(db)
            val old=readCreditRows(db,titleId)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_queue BEFORE INSERT ON mutation_outbox BEGIN SELECT RAISE(ABORT, 'simulated full storage'); END")
            val box=outbox(db,object:RemoteMutationWriter { override suspend fun push(entry:OutboxEntity)=PushResult.Success })
            assertTrue(runCatching { repo(db,box,fresh()).refresh(titleId) }.isFailure)
            assertEquals(old,readCreditRows(db,titleId)); assertTrue(db.outboxDao().getPending().isEmpty())
            assertEquals(1,db.episodeWatchEventDao().observeAllWatchEvents().first().size)
        } finally { db.close() }
    }

    @Test fun oldReceiptRekeysCurrentRowsWithoutOverwritingNewerRefreshOrResurrectingRemovedPerson() = runBlocking {
        val db=database()
        try {
            seed(db)
            val box=outbox(db,object:RemoteMutationWriter { override suspend fun push(entry:OutboxEntity)=PushResult.Retry("hold") })
            repo(db,box,fresh()).refresh(titleId)
            val first=db.outboxDao().getPending().single()
            repo(db,box,fresh(cast=(1..59).map { MediaCredit(it,"Latest $it",null,it) })).refresh(titleId)
            val applier=CreditReceiptApplier(db,ownerId)
            val immutableCommands=db.outboxDao().getPending().associate { it.id to it.payloadJson }
            db.withCreditTransaction { applier.apply(first,receipt(first)) }
            db.outboxDao().remove(first.id)
            val rows=db.titleCastDao().observeAllCast().first()
            assertEquals(59,rows.size); assertTrue(rows.all { it.name.startsWith("Latest ") })
            assertFalse(rows.any { it.tmdbPersonId==60 })
            val later=db.outboxDao().getPending().single()
            assertEquals(immutableCommands.getValue(later.id),later.payloadJson)
            val canonicalId=rows.single { it.tmdbPersonId==21 }.id
            assertFalse(JSONObject(later.payloadJson).getJSONArray("protectedKeys").toString().contains(canonicalId))
            val tombstone=JSONObject().put("entity_type","tombstone").put("entity_id",canonicalId).put("parent_id",JSONObject.NULL).put("payload",JSONObject().put("entityType","title_cast"))
            assertTrue(db.withCreditTransaction { isProtectedFromPull(tombstone,box.pendingEntityKeys()) })
            db.outboxDao().remove(later.id)
            assertFalse(isProtectedFromPull(tombstone,box.pendingEntityKeys()))
            db.titleDao().deleteById(titleId)
            db.withCreditTransaction { applier.apply(first,receipt(first)) }
            assertTrue(readCreditRows(db,titleId).isEmpty())
        } finally { db.close() }
    }

    private suspend fun <T> LibraryDatabase.withCreditTransaction(block:suspend()->T):T = RoomTransactor(this).run(block)
}
