package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.*

@RunWith(RobolectricTestRunner::class)
class EpisodeScheduleRefresherTest {
    private lateinit var db: LibraryDatabase
    private lateinit var box: MutationOutbox
    private val owner = UUID.randomUUID().toString()
    private val titleId = UUID.randomUUID().toString()
    private val seasonId = UUID.randomUUID().toString()
    private val now = 1_792_000_000_000L // 2026-10-14 (UTC)
    private val fetches = mutableListOf<String>()
    private val stamps = object : ScheduleCheckStore {
        var saved: Map<String, Long> = emptyMap()
        override suspend fun read() = saved
        override suspend fun write(checkedAt: Map<String, Long>) { saved = checkedAt }
    }
    private val source = object : EpisodeScheduleSource {
        override suspend fun fetchTvSchedule(tmdbId: Int): TvSchedule {
            fetches += "details:$tmdbId"
            return TvSchedule("Returning Series", 2, listOf(ScheduleSeason(1, 3, 2026), ScheduleSeason(2, 2, 2027)))
        }
        override suspend fun fetchSeason(tmdbId: Int, seasonNumber: Int): MediaSeason {
            fetches += "season:$seasonNumber"
            val episodes = when (seasonNumber) {
                1 -> listOf(MediaEpisode(1, "Pilot", "2026-10-01", 45), MediaEpisode(2, "Second", "2026-11-01", 45), MediaEpisode(3, "Third", "2026-11-08", 45))
                else -> listOf(MediaEpisode(1, "Return", "2027-01-01", 50), MediaEpisode(2, null, null, null))
            }
            return MediaSeason(seasonNumber, episodes.size, null, episodes, listOf(MediaCredit(7, "Lead", "Hero", 0)))
        }
    }
    private fun refresher(current: () -> Boolean = { true }) =
        EpisodeScheduleRefresher(db, box, source, stamps, owner, current, clock = { now }, zone = { ZoneOffset.UTC })

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        box = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter { override suspend fun push(entry: OutboxEntity) = PushResult.Retry("offline") },
            TitleConflictHandler(db.titleDao()), RoomTransactor(db))
        db.titleDao().upsertAll(listOf(TitleEntity(titleId, 42, "TV", "Show", 2026, null, emptyList(), null, null, null, null, null, "WATCHING", null, null, "2026-01-01", "2026-01-01")))
        db.seasonDao().upsertAll(listOf(SeasonEntity(seasonId, titleId, 1, 2, 1, 2026, "2026-10-01T00:00:00Z")))
        db.episodeDao().upsertAll(listOf(EpisodeEntity("e1".uuid(), titleId, seasonId, 1, "Pilot", "2026-10-01", 45),
            EpisodeEntity("e2".uuid(), titleId, seasonId, 2, "TBA", null, null)))
        db.episodeWatchEventDao().upsertAll(listOf(EpisodeWatchEventEntity("watch", "e1".uuid(), "2026-10-02", "Keep history")))
    }
    @After fun close() { db.close() }

    private fun String.uuid() = UUID.nameUUIDFromBytes(toByteArray()).toString()
    private suspend fun operations() = JSONObject(db.outboxDao().getPending().single().payloadJson).getJSONArray("operations").let { ops ->
        (0 until ops.length()).map(ops::getJSONObject)
    }

    @Test fun queuesANewSeasonMovedAirDateAndAppendedEpisodeWithoutTouchingHistory() = runBlocking {
        assertEquals(1, refresher().refresh())
        assertEquals(listOf("details:42", "season:1", "season:2"), fetches)
        val ops = operations()
        assertFalse(ops.any { it.getString("table") in setOf("episode_watch_events", "episode_ratings", "episode_reviews") })
        val moved = ops.single { it.getString("table") == "episodes" && it.getString("action") == "update" }
        assertEquals("e2".uuid(), moved.getJSONObject("key").getString("id"))
        assertEquals(setOf("air_date", "episode_name"), moved.getJSONObject("values").keys().asSequence().toSet())
        assertEquals("2026-11-01", moved.getJSONObject("values").getString("air_date"))
        val grown = ops.single { it.getString("table") == "seasons" && it.getString("action") == "update" }
        assertEquals(3, grown.getJSONObject("values").getInt("episode_count")); assertEquals("2026-10-01T00:00:00Z", grown.getString("expectedUpdatedAt"))
        val inserted = ops.filter { it.getString("action") == "insert" }.map { it.getJSONObject("key").getString("id") }
        assertEquals(listOf(catalogEpisodeId(titleId, 1, 3), catalogSeasonId(titleId, 2), catalogEpisodeId(titleId, 2, 1), catalogEpisodeId(titleId, 2, 2)), inserted)
        assertEquals(2027, ops.single { it.getString("table") == "seasons" && it.getString("action") == "insert" }.getJSONObject("values").getInt("air_year"))
        // Nothing is projected before the server confirms it.
        assertEquals(2, db.episodeDao().observeEpisodes(titleId).first().size)
        assertEquals(now, stamps.saved[titleId])
        // Stored season cast is never rewritten by the background check; the new season brings its own.
        val catalog = JSONObject(db.outboxDao().getPending().single().payloadJson).getJSONArray("catalog")
        assertEquals(0, catalog.getJSONObject(0).getJSONArray("cast").length())
        assertEquals(1, catalog.getJSONObject(1).getJSONArray("cast").length())
    }

    @Test fun checksEachSeriesAtMostOnceADayAndOnlyWithAnEmptyQueue() = runBlocking {
        box.enqueue("title", titleId, "update", JSONObject())
        assertEquals(0, refresher().refresh())
        assertTrue(fetches.isEmpty()); assertTrue(stamps.saved.isEmpty())
        db.outboxDao().getPending().forEach { db.outboxDao().remove(it.id) }
        stamps.saved = mapOf(titleId to now - 1000, "gone" to 1L)
        assertEquals(0, refresher().refresh())
        assertTrue(fetches.isEmpty())
        stamps.saved = mapOf(titleId to now - SCHEDULE_CHECK_INTERVAL_MS, "gone" to 1L)
        assertEquals(1, refresher().refresh())
        assertEquals(mapOf(titleId to now), stamps.saved) // Removed titles are forgotten.
    }

    @Test fun writesNothingForADroppedSeriesOrAnEndedSignIn() = runBlocking {
        db.titleDao().upsertAll(listOf(db.titleDao().getById(titleId)!!.copy(status = "DROPPED")))
        assertEquals(0, refresher().refresh())
        assertTrue(fetches.isEmpty())
        db.titleDao().upsertAll(listOf(db.titleDao().getById(titleId)!!.copy(status = "WATCHING")))
        assertEquals(0, refresher { false }.refresh())
        assertTrue(db.outboxDao().getPending().isEmpty())
    }
}
