package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.*

@RunWith(RobolectricTestRunner::class)
class CatalogDetailProjectionTest {
    @Test fun storedFieldsSurviveDetailProjectionAndExplicitClearsWithoutNetworkOrMutations() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),LibraryDatabase::class.java).build()
        try {
            val source=TitleEntity("one",42,"MOVIE","Film",2020,null,emptyList(),null,null,null,90,"Network","WATCHLIST",4.5,"Private note",
                "2026-10-08T00:30:00Z","2026-10-08T00:30:00Z",imdbRating=8.2,originalLanguage="ja",releaseDate="2020-01-01",
                tags=listOf("Favorite","Family"),studios=listOf("Studio A","Studio B"),collectionName="Example Collection")
            db.titleDao().upsertAll(listOf(source,source.copy(id="other",tags=listOf("Other tag"))))
            val outbox=MutationOutbox(db.outboxDao(),object:RemoteMutationWriter { override suspend fun push(entry:OutboxEntity):PushResult=error("read-only") },
                TitleConflictHandler(db.titleDao()),RoomTransactor(db))
            val repository=LibraryRepository(db.titleDao(),db.seasonDao(),db.episodeDao(),db.episodeWatchEventDao(),db.episodeRatingDao(),db.episodeReviewDao(),
                db.viewingDao(),db.cinemaOutingDao(),db.titleCastDao(),db.titleCrewDao(),db.theaterInterestDao(),outbox,
                object:EpisodeMetadataFetcher {
                    override suspend fun fetchSeasonEpisodes(tmdbId:Int,seasonNumber:Int):List<MediaEpisode> = error("read-only")
                    override suspend fun fetchEpisodeCast(tmdbId:Int,seasonNumber:Int,episodeNumber:Int):EpisodeCast = error("read-only")
                },personCreditsDao=db.personCreditsDao())
            val detail=repository.observeTitleDetail("one").first()!!
            assertEquals(source.tags,detail.tags); assertEquals(source.studios,detail.studios)
            assertEquals(source.originalLanguage,detail.originalLanguage); assertEquals(source.releaseDate,detail.releaseDate)
            assertEquals(source.addedAt,detail.addedAt); assertEquals(source.collectionName,detail.collectionName)
            assertEquals(8.2,detail.imdbRating!!,0.0); assertEquals(4.5,detail.rating!!,0.0)
            db.titleDao().upsertAll(listOf(source.copy(tags=emptyList(),studios=emptyList(),collectionName=null,imdbRating=null,originalLanguage=null,releaseDate=null)))
            val cleared=repository.observeTitleDetail("one").first()!!
            assertTrue(cleared.tags.isEmpty()); assertTrue(cleared.studios.isEmpty())
            assertNull(cleared.collectionName); assertNull(cleared.imdbRating); assertNull(cleared.originalLanguage); assertNull(cleared.releaseDate)
            assertEquals(listOf("Other tag"),repository.observeTitleDetail("other").first()!!.tags)
            assertEquals("Private note",db.titleDao().getById("one")!!.notes)
            assertTrue(db.outboxDao().getPending().isEmpty())
        } finally { db.close() }
    }
}
