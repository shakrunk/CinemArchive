package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.database.RoomTransactor
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.core.model.OutingReminderRules

/** Showtime reminders (#213): one claim per reminder, and one alarm for reminder or completion. */
@RunWith(RobolectricTestRunner::class)
class OutingReminderClaimTest {
    private lateinit var db: LibraryDatabase
    private val title = OutingCommandFixture.title
    private val armed = mutableListOf<Instant?>()
    private var clock = Instant.parse("2026-11-01T12:00:00Z")
    private var lead: Int? = 60
    private var sent = emptySet<String>()

    private val store = object : OutingReminderStore {
        override suspend fun leadMinutes() = lead
        override suspend fun sentKeys() = sent
        override suspend fun saveSentKeys(keys: Set<String>) { sent = keys }
    }

    private fun repository(): OutingsRepository {
        val outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity) = PushResult.Retry("Offline")
        }, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db))
        return OutingsRepository(
            db.cinemaOutingDao(), db.viewingDao(), db.titleDao(), outbox, db.venueNoteDao(),
            alarmScheduler = object : OutingAlarmScheduler {
                override fun scheduleNext(nextTransitionAt: Instant?) { armed += nextTransitionAt }
            },
            reminders = store, now = { clock },
        )
    }

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        db.titleDao().upsertAll(listOf(TitleEntity(title, 42, "MOVIE", "Film", 2026, null, emptyList(),
            null, null, null, 90, null, "WATCHLIST", null, null, OutingCommandFixture.baseline, OutingCommandFixture.baseline)))
        db.cinemaOutingDao().upsert(OutingCommandFixture.entity().copy(
            showtime = "2026-11-01T19:00:00Z", endsAt = "2026-11-01T20:50:00Z",
        ))
    }

    @After fun close() = db.close()

    @Test fun aDueReminderIsClaimedOnceWithTheTitleAndVenueThenArmedForTheCompletionOnly() = runBlocking {
        val repo = repository()
        clock = Instant.parse("2026-11-01T18:02:00Z")
        val claimed = repo.claimDueReminders()
        assertEquals(listOf("Film"), claimed.map { it.titleName })
        assertEquals("Cinema", claimed.single().venue)
        assertEquals(Instant.parse("2026-11-01T19:00:00Z"), claimed.single().showtime)
        assertTrue(repo.claimDueReminders().isEmpty())
        assertEquals(Instant.parse("2026-11-01T20:50:00Z"), armed.last())
    }

    @Test fun theAlarmWakesForTheReminderBeforeTheCompletion() = runBlocking {
        repository().refreshAlarm()
        assertEquals(Instant.parse("2026-11-01T18:00:00Z"), armed.last())
    }

    @Test fun changingTheLeadMovesTheAlarm() = runBlocking {
        val repo = repository()
        lead = 120
        repo.refreshAlarm()
        assertEquals(Instant.parse("2026-11-01T17:00:00Z"), armed.last())
        lead = null
        repo.refreshAlarm()
        assertEquals(Instant.parse("2026-11-01T20:50:00Z"), armed.last())
    }

    @Test fun nothingIsClaimedOrRememberedWhileRemindersAreOff() = runBlocking {
        lead = null
        clock = Instant.parse("2026-11-01T18:02:00Z")
        assertTrue(repository().claimDueReminders().isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test fun aMissedReminderPastTheGraceWindowIsNotPostedLate() = runBlocking {
        clock = Instant.parse("2026-11-01T18:30:00Z")
        assertTrue(repository().claimDueReminders().isEmpty())
    }

    @Test fun sentKeysOfAnOutingThatMovedAreDroppedOnTheNextRearm() = runBlocking {
        val repo = repository()
        clock = Instant.parse("2026-11-01T18:02:00Z")
        repo.claimDueReminders()
        assertEquals(1, sent.size)
        db.cinemaOutingDao().upsert(OutingCommandFixture.entity().copy(
            showtime = "2026-11-01T21:00:00Z", endsAt = "2026-11-01T22:50:00Z",
        ))
        repo.refreshAlarm()
        assertTrue(sent.isEmpty())
        assertEquals(Instant.parse("2026-11-01T20:00:00Z"), armed.last())
        assertTrue(OutingReminderRules.isValidLead(lead))
    }
}
