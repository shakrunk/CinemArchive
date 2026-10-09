package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*

@RunWith(RobolectricTestRunner::class)
class AppliedMutationTest {
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),LibraryDatabase::class.java).build()
    @Test fun failedAckRollsBackLocalRetargetKeepsCommandAndStopsDependentsThenReloadsFreshPayload() = runBlocking {
        val db=database()
        try {
            val first=OutboxEntity("first","test","row","command","{}",0)
            val next=OutboxEntity("next","test","row","command","{\"parent\":\"provisional\"}",1)
            db.outboxDao().enqueue(first); db.outboxDao().enqueue(next)
            var fail=true
            val sent=mutableListOf<OutboxEntity>()
            val writer=object:RemoteMutationWriter { override suspend fun push(entry:OutboxEntity):PushResult {
                sent+=entry
                return if(entry.id=="first") PushResult.Applied(JSONObject()) else PushResult.Success
            } }
            val box=MutationOutbox(db.outboxDao(),writer,TitleConflictHandler(db.titleDao()),RoomTransactor(db),AppliedMutationHandler { _,_ ->
                db.openHelper.writableDatabase.execSQL("UPDATE mutation_outbox SET payloadJson=? WHERE id=?",arrayOf("{\"parent\":\"canonical\"}","next"))
                if(fail) error("disk full")
            })
            box.flush()
            assertEquals(listOf("first"),sent.map { it.id })
            assertEquals(next.payloadJson,db.outboxDao().getPending().last().payloadJson)
            val pending=db.outboxDao().getPending().first()
            assertEquals(first.id,pending.id); assertEquals(first.payloadJson,pending.payloadJson)
            assertEquals(1,pending.attemptCount); assertTrue(pending.lastError!!.contains("local confirmation needs retry"))
            fail=false; box.flush()
            assertEquals(listOf("first","first","next"),sent.map { it.id })
            assertEquals("canonical",JSONObject(sent.last().payloadJson).getString("parent"))
            assertTrue(db.outboxDao().getPending().isEmpty())
        } finally { db.close() }
    }

    @Test fun unregisteredAppliedReceiptFailsClosedRatherThanDroppingPendingWork() = runBlocking {
        val db=database()
        try {
            val entry=OutboxEntity("operation","test","row","command","{}",0)
            db.outboxDao().enqueue(entry)
            val box=MutationOutbox(db.outboxDao(),object:RemoteMutationWriter { override suspend fun push(entry:OutboxEntity)=PushResult.Applied(JSONObject()) },TitleConflictHandler(db.titleDao()),RoomTransactor(db))
            box.flush()
            assertEquals(entry.payloadJson,db.outboxDao().getPending().single().payloadJson)
            assertEquals(1,db.outboxDao().getPending().single().attemptCount)
        } finally { db.close() }
    }

    @Test fun definiteConflictMarksReviewWithoutChangingPayloadOrPushingDependentCommand() = runBlocking {
        val db=database()
        try {
            val entry=OutboxEntity("operation","cinema_outing","row","command_v2","{\"intent\":42}",0)
            db.outboxDao().enqueue(entry); db.outboxDao().enqueue(entry.copy(id="later",createdAt=1))
            var calls=0
            val box=MutationOutbox(db.outboxDao(),object:RemoteMutationWriter { override suspend fun push(entry:OutboxEntity):PushResult { calls++; return PushResult.Review("changed elsewhere") } },TitleConflictHandler(db.titleDao()),RoomTransactor(db))
            box.flush()
            assertEquals(1,calls)
            val retained=db.outboxDao().getPending().first()
            assertEquals("review",retained.operation); assertEquals(entry.payloadJson,retained.payloadJson); assertEquals(entry.id,retained.id)
        } finally { db.close() }
    }
}
