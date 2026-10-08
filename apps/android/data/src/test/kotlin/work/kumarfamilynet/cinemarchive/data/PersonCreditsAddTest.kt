package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
class PersonCreditsAddTest {
    private val details = MediaDetails(42, MediaType.TV, "A show", 2020, "2020-01-01", null, emptyList(), null, null, null, null, null, "en", null, null,
        cast = (1..60).map { MediaCredit(it, "Person $it", null, it) },
        crew = listOf(MediaCrewCredit(42, "Person 42", "Creator", "Writing")),
        seasons = listOf(MediaSeason(0, 1, 2020, listOf(MediaEpisode(3, "Special", "2020-01-01", 30,
            crew = listOf(MediaCrewCredit(84, "Same name", "Writer", null), MediaCrewCredit(85, "Same name", "Director", null)))),
            cast = listOf(MediaCredit(90, "Season only", null, 0)))))

    private fun repository(db: LibraryDatabase, outbox: MutationOutbox, credits: PersonCreditsDao = db.personCreditsDao()) =
        LibraryRepository(db.titleDao(), db.seasonDao(), db.episodeDao(), db.episodeWatchEventDao(), db.episodeRatingDao(), db.episodeReviewDao(),
            db.viewingDao(), db.cinemaOutingDao(), db.titleCastDao(), db.titleCrewDao(), db.theaterInterestDao(), outbox,
            object : EpisodeMetadataFetcher {
                override suspend fun fetchSeasonEpisodes(tmdbId: Int, seasonNumber: Int) = emptyList<MediaEpisode>()
                override suspend fun fetchEpisodeCast(tmdbId: Int, seasonNumber: Int, episodeNumber: Int) = EpisodeCast.EMPTY
            }, credits)

    @Test fun failedNestedCreditWriteRollsBackWholeTitleAndQueue() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).build()
        try {
            val outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
                override suspend fun push(entry: OutboxEntity) = PushResult.Success
            }, TitleConflictHandler(db.titleDao()), RoomTransactor(db))
            val failing = object : PersonCreditsDao by db.personCreditsDao() {
                override suspend fun upsertEpisodeCrew(rows: List<EpisodeCrewEntity>) { error("simulated storage failure") }
            }
            assertTrue(runCatching { repository(db, outbox, failing).addTitle(AddTitleRequest(details, LibraryStatus.WATCHLIST, null, null)) }.isFailure)
            assertEquals(0, db.titleDao().count())
            assertTrue(db.personCreditsDao().observeSeasonCast().first().isEmpty())
            assertTrue(db.outboxDao().getPending().isEmpty())
        } finally { db.close() }
    }

    @Test fun completeCreditGraphIsAtomicAndWriterRetriesRetainStableChildIds() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).build()
        try {
            var failSeasonCast = true
            val requests = mutableListOf<Pair<String, String>>()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val table = chain.request().url.pathSegments.last()
                requests += table to Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                val failed = table == "season_cast" && failSeasonCast
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (failed) 503 else 200).message("test")
                    .body((if (failed) "{\"message\":\"offline\"}" else "[{}]").toResponseBody("application/json".toMediaType())).build()
            }.build()
            val writer = SupabaseRemoteMutationWriter(SupabaseRestClient("https://x.supabase.co", "anon", http)) { SupabaseSession("token", "owner") }
            val outbox = MutationOutbox(db.outboxDao(), writer, TitleConflictHandler(db.titleDao()), RoomTransactor(db))
            val repo = repository(db, outbox)
            val id = repo.addTitle(AddTitleRequest(details, LibraryStatus.WATCHLIST, null, null))
            assertEquals(60, db.titleCastDao().observeAllCast().first().size)
            val people = repo.observeLibrary().first().single().people
            assertEquals(63, people.size)
            assertEquals(setOf(84, 85), people.filter { it.name == "Same name" }.map { it.tmdbPersonId }.toSet())
            val queued = db.outboxDao().getPending().single()
            val payload = JSONObject(queued.payloadJson)
            val seasonCast = db.personCreditsDao().observeSeasonCast().first().single()
            val episodeCrew = db.personCreditsDao().observeEpisodeCrew().first()
            assertEquals(seasonCast.id, payload.getJSONArray("seasonCast").getJSONObject(0).getString("id"))
            assertEquals(episodeCrew.map { it.id }.toSet(), (0..1).map { payload.getJSONArray("episodeCrew").getJSONObject(it).getString("id") }.toSet())
            outbox.flush()
            assertEquals(queued.id, db.outboxDao().getPending().single().id)
            assertEquals(1, db.outboxDao().getPending().single().attemptCount)
            failSeasonCast = false
            outbox.flush()
            assertTrue(db.outboxDao().getPending().isEmpty())
            val seasonSends = requests.filter { it.first == "season_cast" }.map { JSONArray(it.second).getJSONObject(0) }
            assertEquals(listOf(seasonCast.id, seasonCast.id), seasonSends.map { it.getString("id") })
            assertTrue(seasonSends.all { it.getString("user_id") == "owner" && it.getString("title_id") == id && it.getString("season_id") == seasonCast.seasonId })
            assertTrue(requests.indexOfFirst { it.first == "episodes" } < requests.indexOfFirst { it.first == "episode_crew" })
            val old = JSONObject(payload.toString()).apply { remove("seasonCast"); remove("episodeCrew") }
            requests.clear()
            assertEquals(PushResult.Success, writer.push(queued.copy(payloadJson = old.toString())))
            assertFalse(requests.any { it.first == "season_cast" || it.first == "episode_crew" })
        } finally { db.close() }
    }
}
